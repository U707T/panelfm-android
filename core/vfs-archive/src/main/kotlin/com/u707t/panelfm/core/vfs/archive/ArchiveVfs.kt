package com.u707t.panelfm.core.vfs.archive

import com.u707t.panelfm.core.common.Fmt
import com.u707t.panelfm.core.common.Logx
import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.sortFileItems
import com.u707t.panelfm.core.vfs.FileMetadata
import com.u707t.panelfm.core.vfs.ListOptions
import com.u707t.panelfm.core.vfs.Resumability
import com.u707t.panelfm.core.vfs.SpaceInfo
import com.u707t.panelfm.core.vfs.VfsCapabilities
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsException
import com.u707t.panelfm.core.vfs.VfsReader
import com.u707t.panelfm.core.vfs.VfsState
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsWriter
import com.u707t.panelfm.core.vfs.VirtualFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 压缩包只读挂载：`archive://zip/<encoded host uri>!/inner/path`
 *
 *  - 支持 zip / jar / 7z / tar / tar.gz / tgz / tar.bz2（顺序格式在读取时按需重扫）
 *  - 挂载后就是普通 VFS：可以直接「复制到对面窗格」= 解压；可以进压缩包内的目录层层浏览
 *  - 只读（不提供写入/删除/重命名），不含任何 APK / DEX 逆向能力
 */
class ArchiveVfs(
    val host: VfsUri,
    val kind: ArchiveKind,
    /** 本地可随机访问的副本（ZIP 增量写时需要重写它并回传到 host） */
    val localFile: File,
    private val env: VfsEnv,
    /**
     * 压缩包口令（加密包必需）。
     *
     * 为什么放在构造器而不是 [connect]：`openEntryStream` 每次顺序读都要**重新打开**
     * 7z/tar 容器（见该函数注释），口令必须随时可用。
     *
     * 注意 commons-compress 1.27.1 的能力边界：
     *  - 7z：`SevenZFile` 支持 `setPassword`，**能**读加密内容；
     *  - ZIP：`ZipFile` **没有** password 参数（只有一个 `PasswordRequiredException`），
     *    所以加密 ZIP 由 [ZipCryptoReader] 自行解密（见 [openEntryStream]）。
     */
    val password: String? = null,
) : VirtualFileSystem {

    enum class ArchiveKind(val id: String, val label: String) {
        ZIP("zip", "ZIP"),
        SEVEN_Z("7z", "7z"),
        TAR("tar", "TAR"),
        TAR_GZ("targz", "tar.gz"),
        TAR_BZ2("tarbz2", "tar.bz2"),
        ;

        companion object {
            fun ofFileName(name: String): ArchiveKind? {
                val n = name.lowercase()
                return when {
                    n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".xpi") -> ZIP
                    n.endsWith(".7z") -> SEVEN_Z
                    n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                    n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> TAR_BZ2
                    n.endsWith(".tar") -> TAR
                    else -> null
                }
            }
        }
    }

    override val id: String = "archive:${host}:${kind.id}"
    override val scheme: String = "archive"
    override val label: String = "${host.name} · ${kind.label}"

    override val capabilities = VfsCapabilities(
        rename = false,
        serverSideCopy = false,
        rangeRead = false,
        rangeWrite = false,
        resumable = Resumability.NONE,
        permissions = false,
        space = false,
        symlinks = false,
        recursiveDelete = false,
        touch = false,
        streamingList = false,
        writable = false,
    )

    private val _state = MutableStateFlow<VfsState>(VfsState.Idle)
    override val state: StateFlow<VfsState> = _state

    private val mutex = Mutex()
    private var zipFile: ZipFile? = null
    private var sevenZ: SevenZFile? = null

    /** 索引已就绪（与「索引为空」区分：0 条目的压缩包也只应构建一次，第 5 批审计 🔵7） */
    private var indexed = false

    /** ZIP：是否存在加密条目（决定是否需要口令、探测能否走中央目录，第 5 批 🔴1） */
    private var zipHasEncryptedEntries = false

    /** 条目索引：`/dir/file` → 元数据 */
    private val index = LinkedHashMap<String, Entry>()

    /**
     * 所有「目录路径」集合（含合成目录）。构建索引时一次性算好：
     * 旧实现在 list() 里对每个条目做 `items.none { ... }` 线性扫描 → O(n²)，
     * 几万条目的压缩包列目录会卡住。
     */
    private val dirPaths = HashSet<String>()

    private fun rebuildDirPaths() {
        dirPaths.clear()
        index.values.forEach { entry ->
            if (entry.isDirectory) dirPaths.add(entry.path.trimEnd('/'))
            // 逐级补全中间目录（zip 里可能没有显式目录条目）
            var p = entry.path.trimEnd('/')
            var idx = p.lastIndexOf('/')
            while (idx > 0) {
                p = p.substring(0, idx)
                if (!dirPaths.add(p)) break // 已存在则上级也已存在，提前退出
                idx = p.lastIndexOf('/')
            }
        }
    }

    data class Entry(
        val path: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val isDirectory: Boolean,
        val source: Any,          // ZipArchiveEntry / SevenZArchiveEntry / TarArchiveEntry
    )

    override suspend fun connect() {
        if (indexed) return
        _state.value = VfsState.Connecting
        try {
            withContext(env.dispatchers.io) { buildIndex() }
            indexed = true
            _state.value = VfsState.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.value = VfsState.Idle
            throw e
        } catch (e: VfsException) {
            // 口令类错误原样上抛：应用侧据此弹「输入压缩包口令」（第 5 批 🔴1）
            _state.value = VfsState.Error(e.message ?: "无法打开压缩包")
            throw e
        } catch (e: Exception) {
            _state.value = VfsState.Error(e.message ?: "无法打开压缩包")
            throw VfsException.ProtocolError("无法打开压缩包：${e.message}", e)
        }
    }

    private fun buildIndex() {
        // 重开前先放掉旧句柄（空包重建 / close 后重连场景，第 5 批审计 🔵7）：
        // 旧实现直接覆盖 zipFile/sevenZ 字段，旧句柄只能等 finalizer 兜底。
        runCatching { zipFile?.close() }
        runCatching { sevenZ?.close() }
        zipFile = null
        sevenZ = null
        zipHasEncryptedEntries = false
        index.clear()
        when (kind) {
            ArchiveKind.ZIP -> {
                // 列目录只需中央目录，加密包也能列出（但内容读不出来 → 由 openEntryStream 解密）
                val zf = ZipFile.builder().setFile(localFile).get()
                zipFile = zf
                zf.entries.asSequence().forEach { e ->
                    if (e.generalPurposeBit.usesEncryption()) zipHasEncryptedEntries = true
                    val path = normalize(e.name, e.isDirectory)
                    if (path != null) {
                        index[path] = Entry(
                            path = path,
                            name = path.trimEnd('/').substringAfterLast('/'),
                            size = if (e.isDirectory) -1L else e.size,
                            modified = e.time,
                            isDirectory = e.isDirectory,
                            source = e,
                        )
                    }
                }
            }
            ArchiveKind.SEVEN_Z -> {
                // 7z 的口令在构造时给出：加密包的头也可能是加密的（MT 的「同时加密文件名」），
                // 不给口令连条目名都读不出来。
                val sz = sevenZFile()
                sevenZ = sz
                var e = sz.nextEntry
                while (e != null) {
                    val path = normalize(e.name, e.isDirectory)
                    if (path != null) {
                        index[path] = Entry(
                            path = path,
                            name = path.trimEnd('/').substringAfterLast('/'),
                            size = if (e.isDirectory) -1L else e.size,
                            modified = e.lastModifiedDate?.time ?: -1L,
                            isDirectory = e.isDirectory,
                            source = e,
                        )
                    }
                    e = sz.nextEntry
                }
            }
            ArchiveKind.TAR, ArchiveKind.TAR_GZ, ArchiveKind.TAR_BZ2 -> {
                openTar().use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        val path = normalize(e.name, e.isDirectory)
                        if (path != null) {
                            index[path] = Entry(
                                path = path,
                                name = path.trimEnd('/').substringAfterLast('/'),
                                size = if (e.isDirectory) -1L else e.size,
                                modified = e.lastModifiedDate?.time ?: -1L,
                                isDirectory = e.isDirectory,
                                source = e,
                            )
                        }
                        e = tar.nextEntry
                    }
                }
            }
        }
        Logx.i("ArchiveVfs", "index ${host.name}: ${index.size} entries (${Fmt.size(localFile.length())})")
        rebuildDirPaths()
    }

    private fun normalize(raw: String, isDir: Boolean): String? {
        // 压缩包条目名是不可信输入。拒绝绝对路径与 `..` 段，避免解压时
        // 通过 `destDir.child(entry.name)` 穿出用户选择的目标目录。
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith('/')) return null
        val safePath = normalized.trimStart('/')
        if (safePath.isEmpty()) return null
        val parts = safePath.split('/')
        if (parts.any { it == ".." }) return null
        val cleaned = parts.filter { it.isNotEmpty() && it != "." }.joinToString("/")
        if (cleaned.isEmpty()) return null
        // 目录统一以 '/' 结尾
        return if (isDir && !cleaned.endsWith("/")) "$cleaned/" else cleaned
    }

    /** 按是否需要口令打开 7z（口令为空时走无参构造，行为与旧实现一致）。 */
    private fun sevenZFile(): SevenZFile {
        val builder = SevenZFile.builder().setFile(localFile)
        if (!password.isNullOrEmpty()) builder.setPassword(password)
        return try {
            builder.get()
        } catch (e: PasswordRequiredException) {
            // 头加密的 7z（少见）：没口令连条目名都读不出来 → 走「需要口令」路径（第 5 批 🔴1）
            throw VfsException.Auth("该压缩包已加密，请输入口令")
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            // 已给口令仍打不开（头解密失败）→ 口令错误或文件损坏：也走口令重试流程
            if (!password.isNullOrEmpty()) throw VfsException.Auth("压缩包口令不正确或文件已损坏")
            throw e
        }
    }

    private fun openTar(): TarArchiveInputStream {
        val raw = BufferedInputStream(localFile.inputStream(), 64 * 1024)
        return when (kind) {
            ArchiveKind.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            ArchiveKind.TAR_BZ2 -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
            else -> TarArchiveInputStream(raw)
        }
    }

    // ------------------------------------------------------------------ 口令探测（第 5 批 🔴1）

    /** 口令探测结果：应用侧据此决定弹不弹「输入压缩包口令」。 */
    enum class PasswordCheck { OK, NEEDED, WRONG }

    /**
     * 探测当前口令状态（挂载后由应用侧调用）：
     *  - ZIP：加密标志来自中央目录；口令已给出时试读一个加密条目 —— ZipCrypto 的校验字节
     *    在流打开时即验证，所以这一步就能判出口令对错；
     *  - 7z：试读第一个文件条目。未给口令 = [PasswordRequiredException]（内容加密，
     *    commons-compress 不加密文件头，条目名可见）；已给口令仍失败 = 口令错误或数据损坏；
     *  - 其它格式：永远 OK（没有口令概念）。
     */
    suspend fun checkPassword(): PasswordCheck = withContext(env.dispatchers.io) {
        when (kind) {
            ArchiveKind.ZIP -> when {
                !zipHasEncryptedEntries -> PasswordCheck.OK
                password.isNullOrEmpty() -> PasswordCheck.NEEDED
                runCatching { readFirstEncryptedZipEntry() }.isSuccess -> PasswordCheck.OK
                else -> PasswordCheck.WRONG
            }
            ArchiveKind.SEVEN_Z -> {
                val first = index.values.firstOrNull {
                    !it.isDirectory && it.size > 0 &&
                        it.source is org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
                } ?: return@withContext PasswordCheck.OK
                val err = runCatching { openEntryStream(first.path).use { it.read() } }.exceptionOrNull()
                when {
                    err == null -> PasswordCheck.OK
                    err is VfsException.Auth -> PasswordCheck.NEEDED
                    password.isNullOrEmpty() -> PasswordCheck.NEEDED
                    else -> PasswordCheck.WRONG
                }
            }
            else -> PasswordCheck.OK
        }
    }

    /** 试读第一个加密 ZIP 条目（读 1 字节即触发流打开时的校验字节比对）。 */
    private suspend fun readFirstEncryptedZipEntry() {
        val entry = index.values.firstOrNull {
            !it.isDirectory && (it.source as? org.apache.commons.compress.archivers.zip.ZipArchiveEntry)
                ?.generalPurposeBit?.usesEncryption() == true
        } ?: return
        openEntryStream(entry.path).use { it.read() }
    }

    // ------------------------------------------------------------------ 列表

    private fun innerPath(uri: VfsUri): String = parseInner(uri.path)

    override suspend fun list(uri: VfsUri, options: ListOptions): List<FileMetadata> {
        connect()
        val base = innerPath(uri)
        val prefix = if (base.isEmpty()) "" else "$base/"
        val items = ArrayList<FileMetadata>()
        mutex.withLock {
            // 当前层的合成目录集合（用 dirPaths 一次性判定，避免逐条线性查重 = O(n²)）
            val childDirs = LinkedHashSet<String>()
            val filter = options.filter
            // 目录同样要过「隐藏 / 过滤」两道语义（与 LocalVfs 对齐，第 5 批审计 🟡3）：
            // 旧实现只过滤「叶子是文件」的条目，隐藏目录与搜索不匹配的目录会被合成出来。
            fun visibleDir(name: String): Boolean =
                (options.showHidden || !name.startsWith(".")) &&
                    (filter.isNullOrBlank() || name.contains(filter, ignoreCase = true))
            index.values.forEach { entry ->
                if (!entry.path.startsWith(prefix) || entry.path == base) return@forEach
                val rest = entry.path.removePrefix(prefix).trimEnd('/')
                if (rest.isEmpty()) return@forEach
                val slash = rest.indexOf('/')
                if (slash >= 0) {
                    // 深层条目：只贡献一个合成目录名
                    val dirName = rest.substring(0, slash)
                    if (visibleDir(dirName)) childDirs.add(dirName)
                    return@forEach
                }
                if (entry.isDirectory && visibleDir(entry.name)) childDirs.add(entry.name)
                if (!options.showHidden && entry.name.startsWith(".")) return@forEach
                if (!filter.isNullOrBlank() && !entry.name.contains(filter, ignoreCase = true)) return@forEach
                items.add(
                    FileMetadata(
                        uri = uri.child(entry.name),
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        lastModified = entry.modified,
                        mimeType = if (entry.isDirectory) null else MimeTypes.of(entry.name.substringAfterLast('.', "")),
                    )
                )
            }
            // 合成目录（没有显式目录条目时补齐）
            childDirs.forEach { dirName ->
                if (items.none { it.name == dirName }) {
                    items.add(FileMetadata.dir(uri.child(dirName), dirName))
                }
            }
        }
        return sortFileItems(items.distinctBy { it.name }, options.sort)
    }

    override suspend fun stat(uri: VfsUri): FileMetadata {
        connect()
        val path = innerPath(uri)
        if (path.isEmpty()) return FileMetadata.dir(uri, host.name)
        val entry = index[path] ?: index["$path/"]
        if (entry != null) {
            return FileMetadata(
                uri = uri,
                name = entry.name,
                isDirectory = entry.isDirectory,
                size = entry.size,
                lastModified = entry.modified,
                mimeType = if (entry.isDirectory) null else MimeTypes.of(entry.name.substringAfterLast('.', "")),
            )
        }
        // 合成目录（压缩包里没有显式的目录条目）：用一次性构建的 dirPaths 判定，O(1)
        if (dirPaths.contains(path.trimEnd('/'))) return FileMetadata.dir(uri, uri.name)
        throw VfsException.NotFound(uri)
    }

    // ------------------------------------------------------------------ 只读

    override suspend fun mkdir(uri: VfsUri, parents: Boolean) = throw readOnly()
    override suspend fun touch(uri: VfsUri) = throw readOnly()
    override suspend fun delete(uris: List<VfsUri>, onProgress: com.u707t.panelfm.core.vfs.ProgressCallback?) = throw readOnly()
    override suspend fun rename(from: VfsUri, to: VfsUri): Boolean = throw readOnly()
    override suspend fun openWrite(uri: VfsUri, size: Long?, offset: Long): VfsWriter = throw readOnly()
    override suspend fun space(uri: VfsUri): SpaceInfo? = null

    private fun readOnly() = VfsException.Unsupported("压缩包是只读的；用 ⇄ 复制到对面窗格即为解压")

    override fun openRead(uri: VfsUri, offset: Long, length: Long): VfsReader =
        ArchiveReader(innerPath(uri), offset)

    private inner class ArchiveReader(private val path: String, private val start: Long) : VfsReader {

        private var stream: InputStream? = null
        private var pos = start

        /**
         * 加密 ZIP 条目的 CRC 校验器。
         *
         * ZipCrypto 的「口令是否正确」只靠加密头里**一个字节**判定，256 次里有 1 次会放过错误口令，
         * 之后读出的是乱码；数据损坏也一样无声。CRC 是唯一能真正判定内容对错的手段，
         * 所以在读完（EOF）时比对中央目录里的 CRC。
         */
        private var crc: java.util.zip.CRC32? = null
        private var crcExpected: Long = -1L

        override val size: Long? get() = index[path]?.size

        override val supportsSeek: Boolean get() = kind == ArchiveKind.ZIP
        override val position: Long get() = pos

        override suspend fun seek(position: Long) {
            if (position == pos) return
            if (kind != ArchiveKind.ZIP) throw VfsException.Unsupported("该压缩格式不支持随机读取")
            pos = position
            runCatching { stream?.close() }
            stream = null
        }

        override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            ensureOpen()
            val n = stream!!.read(buffer, offset, length)
            if (n > 0) {
                pos += n
                crc?.update(buffer, offset, n)
            } else if (n < 0) {
                verifyCrcOnce()
            }
            return n
        }

        /** 读尽后校验一次（重复调用只校验一次，EOF 会被反复探测） */
        private fun verifyCrcOnce() {
            val c = crc ?: return
            crc = null
            if (crcExpected >= 0 && c.value != crcExpected) {
                throw VfsException.ProtocolError(
                    "加密条目内容校验失败：${path.substringAfterLast('/')}（口令错误或数据已损坏）",
                )
            }
        }

        override suspend fun readFullyAt(position: Long, length: Int): ByteArray = withContext(env.dispatchers.io) {
            if (kind != ArchiveKind.ZIP) throw VfsException.Unsupported("该压缩格式不支持随机读取")
            val entry = index[path] ?: throw VfsException.NotFound(VfsUri.of("archive", kind.id, "/$path"))
            val zf = zipFile ?: throw VfsException.ProtocolError("压缩包未打开")
            // 统一走 openEntryStream：加密条目在那里被解密（readFullyAt 不能直接用
            // ZipFile.getInputStream，否则加密包在随机读路径上会再次抛 UnsupportedZipFeatureException）
            openEntryStream(path, 0L).use { input ->
                var skipped = 0L
                while (skipped < position) {
                    val s = input.skip(position - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val out = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(out, read, length - read)
                    if (n < 0) break
                    read += n
                }
                if (read == length) out else out.copyOf(read)
            }
        }

        private suspend fun ensureOpen() {
            // 顺序读：只要流还开着就继续读（之前用 `pos == start` 判断，
            // 导致每读一块都重开流并 skip 到当前位置 → 大文件 O(n²) 灾难）
            if (stream != null) return
            stream = withContext(env.dispatchers.io) { openEntryStream(path, pos) }
            // 只在「从头顺序读到尾」时校验 CRC；seek 后续读会重开流、CRC 不完整，不校验
            val ze = index[path]?.source as? org.apache.commons.compress.archivers.zip.ZipArchiveEntry
            if (pos == 0L && ze != null && ze.generalPurposeBit.usesEncryption() && !ze.isDirectory) {
                crc = java.util.zip.CRC32()
                crcExpected = ze.crc
            }
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    /** 打开某个条目的输入流；[skip] 用于非 zip 顺序格式的续读 */
    fun openEntryStream(path: String, skip: Long = 0L): InputStream {
        val entry = index[path] ?: throw VfsException.NotFound(VfsUri.of("archive", kind.id, "/$path"))
        val raw: InputStream = when (kind) {
            ArchiveKind.ZIP -> {
                val zf = zipFile ?: throw VfsException.ProtocolError("压缩包未打开")
                val ze = entry.source as org.apache.commons.compress.archivers.zip.ZipArchiveEntry
                if (ze.generalPurposeBit.usesEncryption()) {
                    // 加密条目：commons-compress 读不了（1.27.1 无加密读实现），走自研解密。
                    // getRawInputStream 给出的正是「12 字节加密头 + 密文」，与写侧格式对齐。
                    val rawStream = zf.getRawInputStream(ze)
                    val check = if (ze.generalPurposeBit.usesDataDescriptor()) {
                        // bit3 置位时校验字节是 **DOS 时间的高字节**（APPNOTE 允许的写法，写侧即如此）。
                        // 注意 `ze.time` 是 epoch 毫秒、不是 DOS 时间，必须经 ZipUtil 转换 ——
                        // 直接右移 8 位会得到完全不同的字节，导致「正确口令被判成错误口令」。
                        org.apache.commons.compress.archivers.zip.ZipUtil.toDosTime(ze.time)[1]
                    } else {
                        ZipCrypto.checkByteFor(ze.crc)
                    }
                    ZipCryptoReader.decrypt(
                        raw = rawStream,
                        password = password ?: throw VfsException.Auth("该压缩包已加密，请输入口令"),
                        checkByte = check,
                        method = ze.method,
                    )
                } else {
                    zf.getInputStream(ze)
                }
            }
            ArchiveKind.SEVEN_Z -> {
                // 7z 顺序读取：重新打开并跳到目标条目
                val sz = sevenZFile()
                var e = sz.nextEntry
                while (e != null && e.name != (entry.source as org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry).name) {
                    e = sz.nextEntry
                }
                SevenZInputStream(sz, password)
            }
            ArchiveKind.TAR, ArchiveKind.TAR_GZ, ArchiveKind.TAR_BZ2 -> {
                val tar = openTar()
                var e = tar.nextEntry
                while (e != null && e.name != (entry.source as org.apache.commons.compress.archivers.tar.TarArchiveEntry).name) {
                    e = tar.nextEntry
                }
                tar
            }
        }
        if (skip > 0) {
            var skipped = 0L
            while (skipped < skip) {
                val s = raw.skip(skip - skipped)
                if (s <= 0) break
                skipped += s
            }
        }
        return raw
    }

    override fun close() {
        runCatching { zipFile?.close() }
        runCatching { sevenZ?.close() }
        zipFile = null
        sevenZ = null
        indexed = false // 句柄已关：下次 connect 必须重建索引（第 5 批审计 🔵7）
        _state.value = VfsState.Idle
    }

    /** 把 SevenZFile 适配成 InputStream（commons-compress 的 SevenZFile 不是 InputStream） */
    private class SevenZInputStream(private val zf: SevenZFile, private val password: String?) : InputStream() {
        override fun read(): Int {
            val b = ByteArray(1)
            val n = zf.read(b, 0, 1)
            return if (n <= 0) -1 else b[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            zf.read(b, off, len)
        } catch (e: PasswordRequiredException) {
            // 内容加密且未给口令：给出可执行文案（第 5 批 🔴1）
            throw VfsException.Auth("该压缩包已加密，请输入口令")
        } catch (e: org.tukaani.xz.CorruptedInputException) {
            // 错误口令会用错误密钥解出乱码 → LZMA 层报 corrupt；与真实损坏同型，文案覆盖两种可能
            if (password.isNullOrEmpty()) {
                throw VfsException.ProtocolError("压缩包数据已损坏（${e.message}）")
            }
            throw VfsException.ProtocolError("压缩包口令不正确或数据已损坏（${e.message}）")
        }

        override fun close() {
            runCatching { zf.close() }
        }
    }

    companion object {
        /** `archive://zip/<encoded host>!/inner` → `inner` */
        fun parseInner(path: String): String {
            val idx = path.indexOf("!/")
            if (idx < 0) return ""
            return path.substring(idx + 2).trim('/')
        }

        /** `archive://zip/<encoded host>!/...` → host 的编码串 */
        fun parseEncodedHost(path: String): String? {
            val idx = path.indexOf("!/")
            val raw = if (idx < 0) path.trimStart('/') else path.substring(0, idx).trimStart('/')
            return raw.ifEmpty { null }
        }

        fun uriFor(host: VfsUri, kind: ArchiveKind, inner: String = ""): VfsUri =
            VfsUri("archive", kind.id, "/" + VfsUri.encodeHost(host.toString()) + "!/" + inner.trimStart('/'))
    }
}
