package com.u707t.panelfm.core.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 端口探测引擎：「单机 × 多端口」与「网段 × 多端口」共用一套实现（局域网扫描 / 网络工具箱已合并到这里）。
 *
 * ## 为什么不是旧的写法
 *
 * 旧实现是「一个端口一个协程」：`ports.forEach { launch { sem.withPermit { … } } }`。
 * 端口列表只对**单个区间**限 2048，多段区间可以拼到 65535（`1-2048,2049-4096,…`），
 * 于是一次点击就会同时创建 6 万多个协程 + 6 万多个待办探测 —— 低端机上直接 OOM 闪退；
 * 就算不 OOM，末尾几十次全量排序也足够把主线程拖到 ANR。
 *
 * 现在的形状：
 *  - **固定 worker 池**（默认 64，上限 128）+ 有界队列（容量 64）。内存只与并发数相关，
 *    与用户填了多少端口无关；填错了也只会慢，不会把 App 撑死。
 *  - 结果通过 [Flow] 送到 UI，只在**一处**（collect 里）改状态，不跨线程写 Compose 状态。
 *  - banner 读取有**字节上限**：对端不换行地灌数据时不会无限累积（旧实现用
 *    `BufferedReader.readLine()`，一个不换行的流就能把堆吃光）。
 *  - 单个探测把「超时 / 拒绝 / 解析失败 / fd 耗尽」全部收成「未开放」，不向调用方抛异常。
 */
object PortScan {

    /** 单次扫描的端口数上限（手机全扫没意义：发热 + 被网络设备限速）。 */
    const val MAX_PORTS = 256

    /** 单次扫描的探测总数上限（目标数 × 端口数）。 */
    const val MAX_PROBES = 8192

    /** banner 最多读这么多字节。SSH / FTP / SMTP / Redis 的问候行都远短于此。 */
    const val BANNER_LIMIT = 128

    /** banner 读取的等待上限：读不到就不判定（开放与否只看 connect）。 */
    private const val BANNER_TIMEOUT_MS = 250

    /** 一个已探测目标的结果。[open] 为 false 时 [banner] 必为 null。 */
    data class Result(
        val host: String,
        val port: Int,
        val open: Boolean,
        val banner: String?,
        val elapsedMs: Long,
    )

    /** 扫描过程事件（UI 按序消费，不需要自己加锁）。 */
    sealed interface Event {
        /** 进度：[done] / [total] 次探测。 */
        data class Progress(val done: Int, val total: Int) : Event

        /** 单个目标探测完成（**开放与关闭都会发**，由调用方决定是否显示）。 */
        data class Found(val result: Result) : Event

        /** 全部结束（含被 [stop] 之外的原因提前结束时也会发一次）。 */
        data class Finished(val open: Int, val total: Int, val elapsedMs: Long) : Event
    }

    /** 端口解析结果：[truncated] 表示因为上限只取了前一部分。 */
    data class Ports(val list: List<Int>, val truncated: Boolean)

    /**
     * 常用端口表：`端口 to 说明`。
     * 只收「远程连接 / 调试」真会用到的，不做 1-65535 全扫。
     */
    val CommonPorts: List<Pair<Int, String>> = listOf(
        21 to "FTP",
        22 to "SSH / SFTP",
        23 to "Telnet",
        25 to "SMTP",
        53 to "DNS",
        80 to "HTTP",
        110 to "POP3",
        143 to "IMAP",
        443 to "HTTPS",
        445 to "SMB",
        873 to "rsync",
        1080 to "SOCKS",
        1433 to "SQL Server",
        1521 to "Oracle",
        2049 to "NFS",
        2375 to "Docker",
        3306 to "MySQL",
        3389 to "RDP",
        4444 to "常见反弹端口",
        5000 to "Flask / UPnP",
        5244 to "OpenList / Alist WebDAV",
        5432 to "PostgreSQL",
        5900 to "VNC",
        6379 to "Redis",
        7001 to "WebLogic",
        8000 to "常见 HTTP 备用",
        8080 to "HTTP 代理 / Tomcat",
        8443 to "HTTPS 备用",
        8888 to "常见面板",
        9000 to "PHP-FPM / Portainer",
        9090 to "常见面板",
        9200 to "Elasticsearch",
        11211 to "Memcached",
        27017 to "MongoDB",
    )

    /** 端口说明（找不到返回 null）。 */
    fun portLabel(port: Int): String? = CommonPorts.firstOrNull { it.first == port }?.second

    /**
     * 解析用户输入的端口列表：支持 `80,443` / `1-1024` / 混写；非法片段忽略。
     *
     * **总数量**受 [max] 限制（不是每段各限一次）：旧实现按「每个区间 2048」限，
     * 拼 32 段区间就变回 65535 个端口，正是闪退的入口。超出部分丢弃并置 `truncated`。
     */
    fun parsePorts(raw: String, max: Int = MAX_PORTS): List<Int> = parsePortsDetailed(raw, max).list

    fun parsePortsDetailed(raw: String, max: Int = MAX_PORTS): Ports {
        val limit = max.coerceAtLeast(1)
        val out = sortedSetOf<Int>()
        var truncated = false
        // 中文逗号 / 分号 / 空白都当分隔符：用户从文档里复制过来经常是半角全角混着
        raw.split(',', '，', ';', '；', ' ', '\n', '\r', '\t').forEach { part ->
            val t = part.trim()
            if (t.isEmpty()) return@forEach
            val dash = t.indexOf('-')
            if (dash > 0) {
                val a = t.substring(0, dash).trim().toIntOrNull()
                val b = t.substring(dash + 1).trim().toIntOrNull()
                if (a == null || b == null || a !in 1..65535 || b !in 1..65535) return@forEach
                val from = minOf(a, b)
                val to = maxOf(a, b)
                for (p in from..to) {
                    if (out.size >= limit) {
                        truncated = true
                        break
                    }
                    out += p
                }
            } else {
                val p = t.toIntOrNull() ?: return@forEach
                if (p !in 1..65535) return@forEach
                if (out.size >= limit) truncated = true else out += p
            }
        }
        return Ports(out.toList(), truncated)
    }

    /**
     * 单目标探测。**不抛异常**：连接被拒 / 超时 / 域名解析失败 / fd 耗尽一律返回 `open=false`。
     *
     * @param readBanner 连通后是否尝试读一行 banner（SSH / FTP / SMTP / Redis 会先说话）
     */
    fun probe(host: String, port: Int, timeoutMs: Int = 500, readBanner: Boolean = true): Result {
        val target = host.trim()
        val t0 = System.currentTimeMillis()
        var banner: String? = null
        var open = false
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(target, port), timeoutMs.coerceIn(50, 10_000))
                open = true
                if (readBanner) {
                    socket.soTimeout = BANNER_TIMEOUT_MS
                    banner = readBannerHead(socket.getInputStream())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 「这次不通」不是错误：超时、拒绝、UnknownHost、EMFILE 都走这里
        }
        return Result(
            host = target,
            port = port,
            open = open,
            banner = banner,
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }

    /**
     * 有界读一行 banner。
     *
     * 只做**一次** `read()`（拿到什么算什么），最多 [BANNER_LIMIT] 字节：
     * 旧实现 `bufferedReader().readLine()` 遇到不换行的流会一直往 StringBuilder 里堆，
     * 探一个视频 / 二进制服务就能把堆吃光。这里再坏也只是「banner 少半截」。
     */
    fun readBannerHead(input: InputStream): String? {
        val buf = ByteArray(BANNER_LIMIT)
        val n = try {
            input.read(buf)
        } catch (_: Exception) {
            return null // 读超时 / 连接被重置：banner 可有可无
        }
        if (n <= 0) return null
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xFF
            if (b == 0x0A || b == 0x0D) break
            // 只留可打印 ASCII：二进制 / 非 UTF-8 噪声不该出现在结果行里
            if (b in 0x20..0x7E) sb.append(b.toChar())
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 扫描：`hosts × ports` 全组合。事件流热启动、可取消（collect 所在协程取消即停）。
     *
     * **有界**：不管 [hosts] / [ports] 多大，同时在跑的探测只有 `concurrency` 个，
     * 排队中的任务占用一个队列槽位；不会有「一个目标一个协程」的扇出。
     *
     * 调用方仍需自己按 [MAX_PROBES] 做业务校验（决定是拒绝还是提示），
     * 这里再按同一上限裁剪一次，避免被别的入口绕过。
     */
    fun scan(
        hosts: List<String>,
        ports: List<Int>,
        timeoutMs: Int = 500,
        concurrency: Int = 64,
        readBanner: Boolean = true,
    ): Flow<Event> = channelFlow {
        val targets = hosts.map { it.trim() }.filter { it.isNotEmpty() }
        val portList = ports.take(MAX_PORTS)
        val total = (targets.size * portList.size).coerceAtMost(MAX_PROBES)
        if (total <= 0) {
            send(Event.Finished(0, 0, 0))
            return@channelFlow
        }
        val t0 = System.currentTimeMillis()
        val queue = Channel<Pair<String, Int>>(capacity = 64)
        val done = AtomicInteger(0)
        val open = AtomicInteger(0)

        val workers = (1..concurrency.coerceIn(1, 128)).map {
            launch(Dispatchers.IO) {
                for ((h, p) in queue) {
                    val r = probe(h, p, timeoutMs, readBanner)
                    if (r.open) open.incrementAndGet()
                    send(Event.Found(r))
                    val n = done.incrementAndGet()
                    // 每 16 次刷一次进度：8k 次探测 = 500 次进度事件，主线程不会被事件刷屏
                    if (n % 16 == 0 || n >= total) send(Event.Progress(n, total))
                }
            }
        }
        // 生产者也在 IO：8k 个任务不能在主线程上逐条 send（队列满时会挂起主线程协程）
        launch(Dispatchers.IO) {
            var sent = 0
            for (h in targets) {
                for (p in portList) {
                    if (sent >= total) break
                    queue.send(h to p)
                    sent++
                }
            }
            queue.close()
        }
        workers.forEach { it.join() }
        send(Event.Progress(total, total))
        send(Event.Finished(open.get(), total, System.currentTimeMillis() - t0))
    }

    /** 结果排序：主机按末段数字（192.168.1.9 排在 .10 前面），同主机按端口。 */
    fun sortResults(list: List<Result>): List<Result> = list.sortedWith(
        compareBy<Result> { it.host.substringAfterLast('.').toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.host }
            .thenBy { it.port }
    )

    /** 一行汇总：`开放 3 / 共 508 次探测 · 用时 3.2s` */
    fun summary(openCount: Int, total: Int, elapsedMs: Long): String =
        "开放 $openCount / 共 $total 次探测 · 用时 ${Fmt.duration(elapsedMs)}"

    /** 结果渲染成纯文本（复制 / 存到窗格共用）。 */
    fun render(title: String, targetLine: String, results: List<Result>, elapsedMs: Long): String = buildString {
        append("# ").append(title).append('\n')
        append("# ").append(targetLine).append('\n')
        append("# ").append(summary(results.count { it.open }, results.size, elapsedMs)).append('\n')
        results.forEach { r ->
            append(if (r.open) "OPEN  " else "closed")
            append('\t').append(r.host).append(':').append(r.port)
            portLabel(r.port)?.let { append('\t').append(it) }
            r.banner?.takeIf { it.isNotBlank() }?.let { append('\t').append(it) }
            append('\t').append(r.elapsedMs).append("ms")
            append('\n')
        }
    }.trimEnd()
}
