package com.u707t.panelfm.core.vfs.sftp

import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.vfs.HostKeyStore
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker
import org.apache.sshd.common.SshException
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.PublicKey
import java.time.Duration
import kotlin.random.Random

/**
 * SFTP 会话：一个 SSH 会话 + 一个元数据用 SFTP channel；
 * 传输流（openRead/openWrite）各开独立 channel，避免大文件阻塞列目录。
 *
 * 跳板机：先在堡垒机上开 local port forwarding（direct-tcpip），再把目标连接指向 127.0.0.1:本地端口，
 * 从而支持多级跳板链。
 */
internal class SftpSession(
    private val cfg: SftpConfig,
    private val env: VfsEnv,
    private val hostKeys: HostKeyStore,
) : AutoCloseable {

    private var sshClient: SshClient? = null
    private var session: ClientSession? = null
    private var metaClient: SftpClient? = null

    private var jumpClient: SshClient? = null
    private var jumpSession: ClientSession? = null
    private var jumpTracker: ExplicitPortForwardingTracker? = null

    private val connectMutex = Mutex()
    val metaMutex = Mutex()

    private var lastKeyWarning: String? = null

    suspend fun connect() {
        connectMutex.withLock {
            if (session?.isOpen == true && metaClient != null) return
            withContext(env.dispatchers.vfs) { connectBlocking() }
        }
    }

    private fun connectBlocking() {
        val timeout = Duration.ofMillis(cfg.timeoutMs)
        var targetHost = cfg.host
        var targetPort = cfg.port

        // ---- 跳板机
        cfg.jumpHost?.let { jump ->
            val jc = SshClient.setUpDefaultClient()
            val jumpVerifier = TofuServerKeyVerifier(hostKeys, jump.host, jump.port)
            jc.serverKeyVerifier = jumpVerifier
            jc.start()
            jumpClient = jc
            val js = try {
                jc.connect(jump.user, jump.host, jump.port).verify(timeout).session
            } catch (e: Exception) {
                runCatching { jc.stop() }
                jumpVerifier.lastRejection?.let { throw VfsException.Auth(it) }
                throw mapError(e, "跳板机连接失败")
            }
            if (!jump.password.isNullOrEmpty()) js.addPasswordIdentity(jump.password)
            try {
                js.auth().verify(timeout)
            } catch (e: Exception) {
                runCatching { js.close() }
                runCatching { jc.stop() }
                throw mapError(e, "跳板机认证失败")
            }
            jumpSession = js

            // 本地端口转发：127.0.0.1:随机端口 → 目标主机:端口（等价 ssh -L）
            var tracker: ExplicitPortForwardingTracker? = null
            var lastError: Exception? = null
            repeat(5) {
                val port = Random.nextInt(30000, 60000)
                try {
                    tracker = js.createLocalPortForwardingTracker(
                        SshdSocketAddress("127.0.0.1", port),
                        SshdSocketAddress(cfg.host, cfg.port),
                    )
                    return@repeat
                } catch (e: Exception) {
                    lastError = e
                }
            }
            val active = tracker ?: throw VfsException.ProtocolError("跳板机端口转发失败：${lastError?.message}")
            jumpTracker = active
            val boundPort = (active.localAddress as? InetSocketAddress)?.port ?: cfg.port
            targetHost = "127.0.0.1"
            targetPort = boundPort
            Logx.i("SftpSession", "jump host ready: 127.0.0.1:$boundPort → ${cfg.host}:${cfg.port} via ${jump.host}")
        }

        // ---- 目标连接
        val client = SshClient.setUpDefaultClient()
        val verifier = TofuServerKeyVerifier(hostKeys, cfg.host, cfg.port)
        client.serverKeyVerifier = verifier
        client.start()
        sshClient = client

        val s = try {
            client.connect(cfg.user, targetHost, targetPort).verify(timeout).session
        } catch (e: Exception) {
            runCatching { client.stop() }
            throw mapError(e, "连接失败")
        }
        verifier.lastRejection?.let {
            runCatching { s.close() }
            runCatching { client.stop() }
            throw VfsException.Auth(it)
        }

        when (cfg.auth) {
            SftpAuth.PASSWORD -> {
                if (cfg.password.isNullOrEmpty()) throw VfsException.Auth("未填写密码")
                s.addPasswordIdentity(cfg.password)
            }
            SftpAuth.KEY -> {
                val keyPath = cfg.keyPath ?: throw VfsException.Auth("未选择私钥文件")
                val pairs = try {
                    SshKeys.loadKeyPairs(keyPath, cfg.keyPassphrase)
                } catch (e: Exception) {
                    throw VfsException.Auth("私钥加载失败：${e.message}")
                }
                if (pairs.isEmpty()) throw VfsException.Auth("私钥文件里没有可用的密钥")
                pairs.forEach { s.addPublicKeyIdentity(it) }
            }
        }

        try {
            s.auth().verify(timeout)
        } catch (e: Exception) {
            runCatching { s.close() }
            runCatching { client.stop() }
            throw mapError(e, "认证失败")
        }

        session = s
        // 已经在 vfs 调度器上，直接阻塞式建立 SFTP channel
        metaClient = SftpClientFactory.instance().createSftpClient(s)
        Logx.i("SftpSession", "connected ${cfg.user}@${cfg.host}:${cfg.port} (${cfg.auth})")
    }

    suspend fun meta(): SftpClient {
        connect()
        return metaClient ?: throw VfsException.Network(VfsException.Network.Kind.DISCONNECTED, "SFTP 会话已关闭")
    }

    /** 传输用新 channel（同一个 SSH 会话，互不阻塞） */
    suspend fun newChannel(): SftpClient {
        connect()
        val s = session ?: throw VfsException.Network(VfsException.Network.Kind.DISCONNECTED, "SFTP 会话已关闭")
        return withContext(Dispatchers.IO) { SftpClientFactory.instance().createSftpClient(s) }
    }

    private fun mapError(e: Exception, prefix: String): VfsException = when (e) {
        is VfsException -> e
        is SshException -> VfsException.Auth("$prefix：${e.message}")
        is java.net.UnknownHostException -> VfsException.Network(VfsException.Network.Kind.DNS, "$prefix：域名解析失败")
        is java.net.ConnectException -> VfsException.Network(VfsException.Network.Kind.REFUSED, "$prefix：连接被拒绝")
        is java.net.SocketTimeoutException -> VfsException.Network(VfsException.Network.Kind.TIMEOUT, "$prefix：连接超时")
        else -> VfsException.Network(VfsException.Network.Kind.UNREACHABLE, "$prefix：${e.message}", e)
    }

    override fun close() {
        runCatching { metaClient?.close() }
        runCatching { session?.close(true) }
        runCatching { sshClient?.stop() }
        runCatching { jumpTracker?.close() }
        runCatching { jumpSession?.close(true) }
        runCatching { jumpClient?.stop() }
        metaClient = null
        session = null
        sshClient = null
        jumpSession = null
        jumpClient = null
        jumpTracker = null
    }

    /** TOFU：首次记录指纹并放行；指纹变化则拒绝（并给出人类可读原因）。 */
    private class TofuServerKeyVerifier(
        private val store: HostKeyStore,
        private val host: String,
        private val port: Int,
    ) : org.apache.sshd.client.keyverifier.ServerKeyVerifier {

        var lastRejection: String? = null

        override fun verifyServerKey(session: ClientSession, remoteAddress: SocketAddress, serverKey: PublicKey): Boolean {
            val fp = SshKeys.fingerprint(serverKey)
            val known = store.fingerprint(host, port)
            return when {
                known == null -> {
                    store.trust(host, port, fp, SshKeys.keyType(serverKey))
                    true
                }
                known == fp -> true
                else -> {
                    lastRejection = "主机密钥已变化（可能存在中间人风险）\n已信任：$known\n服务器现在：$fp\n如需继续，请在连接设置里「忘记主机指纹」后重连。"
                    false
                }
            }
        }
    }
}
