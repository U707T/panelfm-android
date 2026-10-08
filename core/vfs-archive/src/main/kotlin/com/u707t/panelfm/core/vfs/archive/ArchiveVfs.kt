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
import com.github.junrar.Archive
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.NotRarArchiveException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedDictionarySizeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarMethodException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.z.ZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * 压缩包只读挂载：`archive://zip/<encoded host uri>!/inner/path`
 *
 *  - 支持 zip / jar / 7z / tar / tar.gz / tgz / tar.bz2 / tar.xz / **rar**（顺序格式在读取时按需重扫）
 *  - zip 家族（epub / whl / nupkg / vsix / crx / kmz / xapk / apks / apkm）可直接按压缩包浏览
 *  - 裸归档：cpio / ar（.deb 的壳就是 ar，进去后可再打开 data.tar.xz）
 *  - **单文件压缩流**（gz / xz / bz2 / lzma / Z / lz4）：虚拟为「一个条目」，名字 = 去掉压缩后缀的
 *    原名；解出后可直接复制走（大小未知，列表不显示体积）
 *  - 挂载后就是普通 VFS：可以直接「复制到对面窗格」= 解压；可以进压缩包内的目录层层浏览
 *  - 只读（不提供写入/删除/重命名），不含任何 APK / DEX 逆向能力
 *
 * RAR 由 junrar 8.x 提供（RAR4 / RAR5 / RAR7、口令、分卷，全部**只读**）：
 *  - **不能生成 .rar** —— 格式专有，没有任何开源实现能写（UnRAR 许可证也明确禁止拿它做压缩器），
 *    压缩入口保持 zip / 7z / tar 系（见 [ArchiveCompressor]）；
 *  - 头加密的 RAR5 在构造时就会抛口令异常；头加密的 RAR4 会「打开成功但零条目」，
 *    靠 [isPasswordProtected] 识别（见 [buildIndex]）；
 *  - 条目数据用 [RarEntryStream]（管道 + 工作线程）桥接，junrar 的解压异常会**原样上抛**。
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
     * 7z/tar/rar 容器（见该函数注释），口令必须随时可用。
     *
     * 注意 commons-compress 1.27.1 的能力边界：
     *  - 7z：`SevenZFile` 支持 `setPassword`，**能**读加密内容；
     *  - ZIP：`ZipFile` **没有** password 参数（只有一个 `PasswordRequiredException`），
     *    所以加密 ZIP 由 [ZipCryptoReader] 自行解密（见 [openEntryStream]）；
     *  - RAR：junrar 的 `Archive` 直接接收口令（数据加密 / 头加密都能读）。
     */
    val password: String? = null,
) : VirtualFileSystem {

    enum class ArchiveKind(val id: String, val label: String) {
        ZIP("zip", "ZIP"),
        SEVEN_Z("7z", "7z"),
        TAR("tar", "TAR"),
        TAR_GZ("targz", "tar.gz"),
        TAR_BZ2("tarbz2", "tar.bz2"),
        TAR_XZ("tarxz", "tar.xz"),
        RAR("rar", "RAR"),
        CPIO("cpio", "CPIO"),
        AR("ar", "AR"),
        // 单文件压缩流：内容是一个流，不是条目集合（索引里会合成一个条目）
        GZIP("gzip", "GZIP"),
        XZ("xz", "XZ"),
        BZIP2("bzip2", "bzip2"),
        LZMA("lzma", "LZMA"),
        UNIX_COMPRESS("z", "Z"),
        LZ4("lz4", "LZ4"),
        ;

        /** 单文件压缩流（内容整体压缩，无内部条目结构） */
        val isSingleStream: Boolean
            get() = this == GZIP || this == XZ || this == BZIP2 || this == LZMA ||
                this == UNIX_COMPRESS || this == LZ4

        companion object {
            fun ofFileName(name: String): ArchiveKind? {
                val n = name.lowercase()
                return when {
                    n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".xpi") ||
                        n.endsWith(".epub") || n.endsWith(".whl") || n.endsWith(".nupkg") ||
                        n.endsWith(".vsix") || n.endsWith(".crx") || n.endsWith(".kmz") ||
                        n.endsWith(".xapk") || n.endsWith(".apks") || n.endsWith(".apkm") -> ZIP
                    n.endsWith(".7z") -> SEVEN_Z
                    n.endsWith(".rar") -> RAR
                    // tar 系（复合后缀必须排在单流后缀前面）
                    n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                    n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> TAR_BZ2
                    n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                    n.endsWith(".tar") -> TAR
                    n.endsWith(".cpio") -> CPIO
                    n.endsWith(".ar") || n.endsWith(".deb") -> AR
                    // 单文件压缩流
                    n.endsWith(".gz") -> GZIP
                    n.endsWith(".bz2") -> BZIP2
                    n.endsWith(".xz") -> XZ
                    n.endsWith(".lzma") -> LZMA
                    n.endsWith(".lz4") -> LZ4
                    n.endsWith(".z") -> UNIX_COMPRESS
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
            ArchiveKind.TAR, ArchiveKind.TAR_GZ, ArchiveKind.TAR_BZ2, ArchiveKind.TAR_XZ -> {
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
            ArchiveKind.CPIO -> {
                BufferedInputStream(localFile.inputStream(), 64 * 1024).use { raw ->
                    CpioArchiveInputStream(raw).use { cpio ->
                        var e = cpio.nextEntry
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
                            e = cpio.nextEntry
                        }
                    }
                }
            }
            ArchiveKind.AR -> {
                BufferedInputStream(localFile.inputStream(), 64 * 1024).use { raw ->
                    ArArchiveInputStream(raw).use { ar ->
                        var e = ar.nextEntry
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
                            e = ar.nextEntry
                        }
                    }
                }
            }
            ArchiveKind.RAR -> {
                // 列条目不一定要口令（RAR 通常只加密数据、不加密文件名）；头加密见下方特判。
                rarArchive().use { ra ->
                    var e = ra.nextFileHeader()
                    while (e != null) {
                        val path = normalize(e.fileName, e.isDirectory)
                        if (path != null) {
                            index[path] = Entry(
                                path = path,
                                name = path.trimEnd('/').substringAfterLast('/'),
                                size = if (e.isDirectory) -1L else e.fullUnpackSize,
                                modified = e.mTime?.time ?: -1L,
                                isDirectory = e.isDirectory,
                                source = e,
                            )
                        }
                        e = ra.nextFileHeader()
                    }
                    // 头加密的 RAR4：junrar 会「打开成功但一个条目都读不出来」（不抛异常）。
                    // 用 isPasswordProtected 识别这种状态，走与 7z 相同的「输入口令」路径（第 5 批 🔴1 的接线复用）。
                    // 注意：确认不了保护状态（老版本 / 异常）时**不**当加密处理，避免把正常的空包误判成加密。
                    if (index.isEmpty() && runCatching { ra.isPasswordProtected }.getOrDefault(false)) {
                        throw VfsException.Auth(
                            if (password.isNullOrEmpty()) "该压缩包已加密，请输入口令"
                            else "压缩包口令不正确或文件已损坏"
                        )
                    }
                }
            }
            else -> {
                // 单文件压缩流：内容整体就是一个文件，无条目结构 —— 合成一个条目，
                // 名字 = 去掉压缩后缀的原名（file.txt.gz → file.txt），解出即可直接复制走。
                check(kind.isSingleStream) { "未处理的压缩类型：$kind" }
                val name = normalize(singleStreamName(host.name), isDir = false) ?: "data"
                index[name] = Entry(
                    path = name,
                    name = name,
                    size = -1L, // 解压后大小未知（gz 的 ISIZE 只对单成员 < 4 GiB 可靠，宁缺勿错）
                    modified = localFile.lastModified(),
                    isDirectory = false,
                    source = name,
                )
            }
        }
        Logx.i("ArchiveVfs", "index ${host.name}: ${index.size} entries (${Fmt.size(localFile.length())})")
        rebuildDirPaths()
    }

    /** 单文件压缩流的解出名字：file.txt.gz → file.txt（没有可剥后缀则原样返回） */
    private fun singleStreamName(rawName: String): String {
        val suffix = when (kind) {
            ArchiveKind.GZIP -> ".gz"
            ArchiveKind.XZ -> ".xz"
            ArchiveKind.BZIP2 -> ".bz2"
            ArchiveKind.LZMA -> ".lzma"
            ArchiveKind.UNIX_COMPRESS -> ".z"
            ArchiveKind.LZ4 -> ".lz4"
            else -> ""
        }
        if (suffix.isEmpty()) return rawName
        val lower = rawName.lowercase()
        return if (lower.endsWith(suffix) && rawName.length > suffix.length) {
            rawName.dropLast(suffix.length)
        } else {
            rawName
        }
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
            ArchiveKind.TAR_XZ -> TarArchiveInputStream(XZCompressorInputStream(raw))
            else -> TarArchiveInputStream(raw)
        }
    }

    /**
     * 打开 RAR 容器（口令为空时走无参构造）。junrar 的典型异常在这里落成可执行文案：
     *  - [WrongPasswordException]：头加密（RAR5）没口令 / 口令错；
     *  - [NotRarArchiveException]：不是 RAR（扩展名骗人 / 文件损坏）；
     *  - [UnsupportedRarVersionException] / [UnsupportedRarEncryptedException]：暂不支持的特性。
     */
    private fun rarArchive(): Archive {
        return try {
            if (password.isNullOrEmpty()) Archive(localFile) else Archive(localFile, password)
        } catch (e: WrongPasswordException) {
            throw VfsException.Auth(
                if (password.isNullOrEmpty()) "该压缩包已加密，请输入口令"
                else "压缩包口令不正确或文件已损坏"
            )
        } catch (e: NotRarArchiveException) {
            throw VfsException.ProtocolError("不是有效的 RAR 文件（或文件已损坏）", e)
        } catch (e: UnsupportedRarVersionException) {
            throw VfsException.Unsupported("该 RAR 版本暂不支持：${e.message}")
        } catch (e: UnsupportedRarEncryptedException) {
            throw VfsException.Unsupported("该 RAR 的加密方式暂不支持：${e.message}")
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: RarException) {
            throw VfsException.ProtocolError("无法打开 RAR：${e.message}", e)
        } catch (e: IOException) {
            throw VfsException.ProtocolError("无法打开 RAR：${e.message}", e)
        }
    }

    /** junrar 解压过程中的异常 → 可读的 [VfsException]（[RarEntryStream] 在读取端上抛）。 */
    private fun mapRarError(t: Throwable): VfsException = when (t) {
        is WrongPasswordException -> VfsException.Auth(
            if (password.isNullOrEmpty()) "该压缩包已加密，请输入口令"
            else "压缩包口令不正确或数据已损坏"
        )
        // RAR4 数据加密：没口令时 junrar 在解密码器初始化处抛这个（不是 WrongPassword）
        is InitDeciphererFailedException -> VfsException.Auth(
            if (password.isNullOrEmpty()) "该压缩包已加密，请输入口令"
            else "压缩包口令不正确或数据已损坏"
        )
        is CrcErrorException -> VfsException.ProtocolError("RAR 数据校验失败（口令错误或数据已损坏）", t)
        is UnsupportedRarMethodException -> VfsException.Unsupported("该 RAR 使用了暂不支持的方法：${t.message}")
        is UnsupportedDictionarySizeException -> VfsException.Unsupported("RAR 字典超出防护上限：${t.message}")
        is MissingNextVolumeException -> VfsException.Unsupported("这是分卷压缩包，缺少下一个分卷")
        is MissingPreviousVolumeException -> VfsException.Unsupported("这是分卷压缩包，缺少前一个分卷")
        is RarException -> VfsException.ProtocolError("RAR 解压失败：${t.message}", t)
        else -> VfsException.ProtocolError("RAR 解压失败：${t.message}", t)
    }

    /**
     * junrar 的提取是「推式」的（[Archive.extractFile] 要一个 OutputStream 一路写），
     * 而 [openEntryStream] 是「拉式」的（一路 read）—— 用管道 + 工作线程桥接。
     *
     * ⚠️ 不能用 junrar 自带的 `Archive.getInputStream()`：它内部把异常整个吞掉
     * （`catch (RarException ignored)`），口令错 / 数据坏会**静默截断成看似正常的 EOF**。
     * 这里把工作线程的异常记下来，在读取端原样上抛（EOF 前也会再查一次）。
     */
    private class RarEntryStream(
        private val archive: Archive,
        private val header: FileHeader,
        private val mapError: (Throwable) -> VfsException,
    ) : InputStream() {

        private val pipedIn = PipedInputStream(256 * 1024)
        private val pipedOut = PipedOutputStream(pipedIn)

        @Volatile
        private var failure: Throwable? = null

        private val worker = Thread({
            try {
                archive.extractFile(header, pipedOut)
            } catch (t: Throwable) {
                failure = t
            } finally {
                runCatching { pipedOut.close() }
                runCatching { archive.close() }
            }
        }, "rar-entry").apply { isDaemon = true; start() }

        private fun failIfAny() {
            failure?.let { throw mapError(it) }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            failIfAny()
            val n = try {
                pipedIn.read(b, off, len)
            } catch (e: IOException) {
                failIfAny()
                throw e
            }
            if (n < 0) failIfAny() // EOF：可能是正常读完，也可能是工作线程已失败（异常别吞）
            return n
        }

        override fun available(): Int = runCatching { pipedIn.available() }.getOrDefault(0)

        override fun close() {
            // 关闭读取端 → 工作线程的下一次写入抛 "Pipe closed" → 它退出并在 finally 里释放 Archive。
            // （消费端提前取消时，最多多留一个阻塞在写上的守护线程到这次写入返回，不会泄漏文件句柄。）
            runCatching { pipedIn.close() }
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
            ArchiveKind.RAR -> {
                // 头加密在挂载时已拦截（见 buildIndex）；这里判「数据加密」。
                // RAR4 的错口令没有早期信号（解密码器初始化永远成功，乱码只在条目读完做 CRC 时才暴露），
                // RAR5 有 pswCheck 早期信号。所以：
                //  - 没口令：读 1 字节即可（缺口令在解密器/KDF 初始化处就抛）；
                //  - 有口令：把**最小的**加密条目整个读完来验签（上限定 64MB，超了就不验，
                //    留给实际读取时报错，避免挂载时为了一个巨型条目去解几个 GB）。
                val encrypted = index.values
                    .filter { !it.isDirectory && it.size > 0 && (it.source as? FileHeader)?.isEncrypted == true }
                    .minByOrNull { it.size }
                    ?: return@withContext PasswordCheck.OK
                if (password.isNullOrEmpty()) {
                    val err = runCatching { openEntryStream(encrypted.path).use { it.read() } }.exceptionOrNull()
                    return@withContext when {
                        err == null -> PasswordCheck.OK
                        err is VfsException.Auth -> PasswordCheck.NEEDED
                        else -> PasswordCheck.NEEDED
                    }
                }
                if (encrypted.size > RAR_VERIFY_MAX_BYTES) {
                    Logx.i("ArchiveVfs", "RAR 口令跳过验签：最小加密条目 ${encrypted.name} 为 ${Fmt.size(encrypted.size)}")
                    return@withContext PasswordCheck.OK
                }
                val err = runCatching { openEntryStream(encrypted.path).use { drain(it) } }.exceptionOrNull()
                return@withContext if (err == null) PasswordCheck.OK else PasswordCheck.WRONG
            }
            else -> PasswordCheck.OK
        }
    }

    private fun drain(stream: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (stream.read(buf, 0, buf.size) >= 0) {
            // 读完为止（RAR4 的 CRC 校验发生在流的末尾）
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

        override val size: Long? get() = index[path]?.size?.takeIf { it >= 0 }

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
            ArchiveKind.TAR, ArchiveKind.TAR_GZ, ArchiveKind.TAR_BZ2, ArchiveKind.TAR_XZ -> {
                val tar = openTar()
                var e = tar.nextEntry
                while (e != null && e.name != (entry.source as org.apache.commons.compress.archivers.tar.TarArchiveEntry).name) {
                    e = tar.nextEntry
                }
                tar
            }
            ArchiveKind.CPIO -> {
                val cpio = CpioArchiveInputStream(BufferedInputStream(localFile.inputStream(), 64 * 1024))
                val target = entry.source as CpioArchiveEntry
                var match: CpioArchiveEntry? = null
                var e = cpio.nextEntry
                while (e != null) {
                    if (e.name == target.name && e.size == target.size) {
                        match = e
                        break
                    }
                    e = cpio.nextEntry
                }
                if (match == null) {
                    runCatching { cpio.close() }
                    throw VfsException.ProtocolError("CPIO 条目在重新打开后找不到了：${entry.name}")
                }
                cpio
            }
            ArchiveKind.AR -> {
                val ar = ArArchiveInputStream(BufferedInputStream(localFile.inputStream(), 64 * 1024))
                val target = entry.source as ArArchiveEntry
                var match: ArArchiveEntry? = null
                var e = ar.nextEntry
                while (e != null) {
                    if (e.name == target.name && e.size == target.size) {
                        match = e
                        break
                    }
                    e = ar.nextEntry
                }
                if (match == null) {
                    runCatching { ar.close() }
                    throw VfsException.ProtocolError("AR 条目在重新打开后找不到了：${entry.name}")
                }
                ar
            }
            ArchiveKind.GZIP, ArchiveKind.XZ, ArchiveKind.BZIP2, ArchiveKind.LZMA,
            ArchiveKind.UNIX_COMPRESS, ArchiveKind.LZ4,
            -> {
                check(kind.isSingleStream) { "未处理的压缩类型：$kind" }
                val rawIn = BufferedInputStream(localFile.inputStream(), 64 * 1024)
                when (kind) {
                    ArchiveKind.GZIP -> GzipCompressorInputStream(rawIn)
                    ArchiveKind.XZ -> XZCompressorInputStream(rawIn)
                    ArchiveKind.BZIP2 -> BZip2CompressorInputStream(rawIn)
                    ArchiveKind.LZMA -> LZMACompressorInputStream(rawIn)
                    ArchiveKind.UNIX_COMPRESS -> ZCompressorInputStream(rawIn)
                    ArchiveKind.LZ4 -> FramedLZ4CompressorInputStream(rawIn)
                    else -> error("unreachable")
                }
            }
            ArchiveKind.RAR -> {
                // junrar 的 FileHeader 没有 equals（identity 比较），跨实例按名 + 大小重新匹配；
                // 顺序扫描到目标条目由 Junrar 的 solid 逻辑负责（solid 包会从头重放）。
                val ra = rarArchive()
                val target = entry.source as FileHeader
                var match: FileHeader? = null
                var e = ra.nextFileHeader()
                while (e != null) {
                    if (e.fileName == target.fileName && e.fullUnpackSize == target.fullUnpackSize) {
                        match = e
                        break
                    }
                    e = ra.nextFileHeader()
                }
                if (match == null) {
                    runCatching { ra.close() }
                    throw VfsException.ProtocolError("RAR 条目在重新打开后找不到了：${entry.name}")
                }
                RarEntryStream(ra, match) { t -> mapRarError(t) }
            }
        }
        if (skip > 0) {
            var skipped = 0L
            val scratch = ByteArray(64 * 1024)
            while (skipped < skip) {
                val s = raw.skip(skip - skipped)
                if (s > 0) {
                    skipped += s
                    continue
                }
                // 部分解压流（gzip / xz / bz2 …）的 skip 不保证推进：退化为读掉
                val want = minOf(scratch.size.toLong(), skip - skipped).toInt()
                val n = raw.read(scratch, 0, want)
                if (n <= 0) break
                skipped += n
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
        /** RAR 口令验签上限：最小的加密条目超过它就跳过挂载期验签（留给实际读取时报错） */
        private const val RAR_VERIFY_MAX_BYTES = 64L * 1024 * 1024

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
