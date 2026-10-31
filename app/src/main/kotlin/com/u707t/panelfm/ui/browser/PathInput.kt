package com.u707t.panelfm.ui.browser

import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.VfsUris
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import com.u707t.panelfm.core.vfs.local.LocalVolume
import com.u707t.panelfm.core.vfs.local.LocalVolumes

/**
 * 顶部路径栏的**纯逻辑**（不碰 Compose / Context，便于单测）。
 *
 * 一块内容：
 *  - [PathInput]：把「跳转到路径」输入框里的各种写法解析成 [VfsUri]；
 *  - [pathCrumbs]：把当前 URI 拆成层级列表（顶栏路径菜单 / 压缩包宿主层级）；
 *  - [pathTextOf]：把 VFS URI 还原成用户能看懂、能粘贴的完整路径。
 *
 * 设计口径对齐 MT / NP：
 *  - 顶栏路径保持 MT 的单行居中观感，层级跳转收进点击菜单；最后一级是当前目录；
 *  - 路径输入支持 `~`（存储根）、相对路径（`../Music`）、系统绝对路径
 *    （`/storage/emulated/0/...`、`/sdcard/...`）与协议 URI（`sftp://…`、`s3://…`）；
 *  - 压缩包里可以直接写 `a.zip!/目录/文件`。
 */
internal object PathInput {

    /** 解析结果：成功时 [archiveHost] 非空表示目标是压缩包内部（需要先确保压缩包已挂载）。 */
    sealed interface Result {
        data class Ok(
            val uri: VfsUri,
            val label: String? = null,
            val archiveHost: VfsUri? = null,
        ) : Result

        data class Bad(val message: String) : Result
    }

    fun resolve(rawInput: String, current: VfsUri, volumes: List<LocalVolume>): Result {
        // 从剪贴板粘贴过来常带引号 / 首尾空白
        val raw = rawInput.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (raw.isEmpty()) return Result.Bad("请输入路径")

        // 压缩包内部：a.zip!/inner（宿主部分仍按普通路径解析）
        val bang = raw.indexOf("!/")
        if (bang == 0) return Result.Bad("压缩包路径缺少压缩包名（正确写法：a.zip!/目录）")
        if (bang > 0) {
            val hostPart = raw.substring(0, bang)
            val inner = raw.substring(bang + 2)
            val base = if (current.scheme == "archive") {
                archiveHostOf(current)?.parent ?: current
            } else {
                current
            }
            val host = when (val r = resolveHost(hostPart, base, volumes)) {
                is Result.Ok -> r.uri
                is Result.Bad -> return r
            }
            val kind = ArchiveVfs.ArchiveKind.ofFileName(host.name)
                ?: return Result.Bad("不是可识别的压缩包：${host.name.ifBlank { host.displayPath }}")
            return Result.Ok(
                uri = archiveUri(host, kind.id, normalizeInner(inner)),
                archiveHost = host,
            )
        }

        // 已经在压缩包里：没写协议时，绝对 / 相对路径都按「压缩包内部」解释
        if (current.scheme == "archive" && !raw.contains("://")) {
            val host = archiveHostOf(current) ?: return Result.Bad("压缩包路径格式不正确")
            val innerNow = ArchiveVfs.parseInner(current.path)
            val targetInner = when {
                raw == "~" -> ""
                raw.startsWith("~/") -> normalizeInner(raw.substring(2))
                raw.startsWith("/") -> normalizeInner(raw)
                else -> normalizeInner("$innerNow/$raw")
            }
            return Result.Ok(archiveUri(host, current.authority, targetInner), archiveHost = host)
        }

        return resolveHost(raw, current, volumes)
    }

    // ------------------------------------------------------------------ 普通路径

    private fun resolveHost(raw: String, current: VfsUri, volumes: List<LocalVolume>): Result {
        val schemeAt = raw.indexOf("://")
        if (schemeAt > 0) {
            val parsed = runCatching { VfsUri.parse(raw) }.getOrNull()
                ?: return Result.Bad("路径格式不正确：$raw")
            return when (parsed.scheme) {
                "file" -> {
                    val abs = if (parsed.authority.isBlank()) parsed.path
                    else "/" + parsed.authority + parsed.path
                    resolveAbsoluteFs(abs, current, volumes)
                }
                "local" -> {
                    if (parsed.authority.isBlank()) {
                        resolveAbsoluteFs(parsed.path, current, volumes)
                    } else {
                        // 兼容 local://emulated/0/...（旧展示里带过 /0 的写法）
                        val path = stripEmulatedUser(parsed.authority, parsed.path)
                        Result.Ok(
                            if (path == parsed.path) parsed else parsed.copy(path = path),
                            localLabel(parsed.authority, volumes),
                        )
                    }
                }
                "archive" -> {
                    val host = archiveHostOf(parsed)
                    if (host == null) Result.Bad("压缩包路径格式不正确")
                    else Result.Ok(parsed, archiveHost = host)
                }
                "http", "https", "content" ->
                    Result.Bad("不支持 ${parsed.scheme}:// 路径，请使用文件路径或存储协议")
                else -> Result.Ok(parsed)
            }
        }

        // 系统绝对路径：本地窗格按「卷」映射；网络窗格按同一挂载点内的绝对路径
        if (raw.startsWith("/")) {
            return if (current.scheme == "local") {
                resolveAbsoluteFs(raw, current, volumes)
            } else {
                Result.Ok(current.withPath(canonicalPath(raw)))
            }
        }

        // 相对路径 / ~（当前存储的根）
        val rootRelative = raw == "~" || raw.startsWith("~/")
        val base = if (rootRelative) current.withPath("/") else current
        val tail = when {
            raw == "~" -> ""
            raw.startsWith("~/") -> raw.substring(2)
            else -> raw
        }
        return Result.Ok(base.withPath(canonicalPath(base.path + "/" + tail)))
    }

    /** 系统绝对路径 → 本地卷 URI（内部存储 / 应用私有目录 / 外置卡 / 根目录兜底） */
    private fun resolveAbsoluteFs(raw: String, current: VfsUri, volumes: List<LocalVolume>): Result {
        val abs = canonicalFs(raw)
        if (abs == "/") {
            // 本地窗格里的 "/" = 当前卷的根（与 MT 一致），不是文件系统根
            val authority = if (current.scheme == "local") current.authority else LocalVolumes.AUTHORITY_EMULATED
            val query = if (current.scheme == "local") current.query else null
            return Result.Ok(VfsUri("local", authority, "/", query), localLabel(authority, volumes))
        }
        val volume = matchVolume(abs, volumes)
        if (volume != null) {
            val rel = abs.removePrefix(volume.path.trimEnd('/')).ifEmpty { "/" }
            return Result.Ok(VfsUri.of("local", volume.authority, rel), volume.label)
        }
        // 不在任何已知卷里：交给根目录卷（有权限就能进，没权限会得到明确的 VFS 报错）
        return Result.Ok(VfsUri.of("local", LocalVolumes.AUTHORITY_ROOT, abs), "根目录")
    }

    private fun matchVolume(abs: String, volumes: List<LocalVolume>): LocalVolume? = volumes
        .filter { volume ->
            val base = volume.path.trimEnd('/').ifEmpty { "/" }
            base == "/" || abs == base || abs.startsWith("$base/")
        }
        // 最长前缀优先（/storage/emulated/0 胜过 /，外置卡胜过 /）
        .maxByOrNull { it.path.length }

    // ------------------------------------------------------------------ 工具

    /** `local://emulated/0/x` → `local://emulated/x`（只对内部存储做兼容，不动真实名为 0 的子目录语义） */
    private fun stripEmulatedUser(authority: String, path: String): String {
        if (authority != LocalVolumes.AUTHORITY_EMULATED) return path
        return when {
            path == "/0" -> "/"
            path.startsWith("/0/") -> path.removePrefix("/0")
            else -> path
        }
    }

    fun localLabel(authority: String, volumes: List<LocalVolume>): String? =
        volumes.firstOrNull { it.authority == authority }?.label
            ?: when (authority) {
                LocalVolumes.AUTHORITY_EMULATED -> "内部存储"
                LocalVolumes.AUTHORITY_APP -> "应用私有目录"
                LocalVolumes.AUTHORITY_ROOT -> "根目录"
                else -> null
            }

    /**
     * 规范化 POSIX 路径：折叠 `//`、`.`、`..`；绝对路径的 `..` 不会越过根。
     *
     * 例：`/a//b/../c` → `/a/c`；`../../x` → `../../x`（相对路径保留前导 `..`）。
     */
    fun canonicalPath(path: String): String {
        val absolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> when {
                    out.isNotEmpty() && out.last() != ".." -> out.removeAt(out.lastIndex)
                    !absolute -> out.add("..")
                }
                else -> out.add(seg)
            }
        }
        val body = out.joinToString("/")
        return if (absolute) "/$body" else body
    }

    /** 系统路径规范化 + `/sdcard` 等别名归一 */
    fun canonicalFs(path: String): String {
        var p = canonicalPath(path.ifBlank { "/" })
        val aliases = listOf(
            "/mnt/sdcard",
            "/storage/self/primary",
            "/storage/emulated/legacy",
            "/sdcard",
        )
        for (alias in aliases) {
            if (p == alias) {
                p = "/storage/emulated/0"
                break
            }
            if (p.startsWith("$alias/")) {
                p = "/storage/emulated/0" + p.removePrefix(alias)
                break
            }
        }
        return p
    }

    /** 压缩包内部路径：始终相对包根，`..` 越界即夹在根上 */
    private fun normalizeInner(raw: String): String =
        canonicalPath("/" + raw.trim('/')).trim('/')

    // ------------------------------------------------------------------ 压缩包 URI

    fun archiveHostOf(uri: VfsUri): VfsUri? {
        if (uri.scheme != "archive") return null
        val encoded = ArchiveVfs.parseEncodedHost(uri.path) ?: return null
        return runCatching { VfsUri.parse(VfsUri.decodeHost(encoded)) }.getOrNull()
    }

    fun archiveUri(host: VfsUri, kindId: String, inner: String): VfsUri =
        VfsUri("archive", kindId, "/" + VfsUri.encodeHost(host.toString()) + "!/" + inner.trimStart('/'))
}

// ================================================================================================
// 路径层级
// ================================================================================================

/**
 * 一级路径层级（顶栏路径菜单用）。
 *
 * [mountLabel] 用于「从压缩包里点宿主目录」时把标签页名换回宿主存储名；
 * 普通层级为 null（保持当前标签页名）。
 */
internal data class PathCrumb(
    val label: String,
    val uri: VfsUri,
    val current: Boolean = false,
    val mountLabel: String? = null,
)

/**
 * 当前 URI → 路径层级列表（顶栏路径菜单的数据源）。
 *
 * 压缩包的拆法是关键：`/Download/a.zip!/dir/sub` 在菜单里是
 * `内部存储 / Download / a.zip / dir / sub`
 *  - `a.zip` 指向**压缩包根**（不是宿主文件本身，文件不能被 list）；
 *  - 宿主层级（内部存储 / Download）点击即退出压缩包；
 *  - 包内层级继续在 archive 里走。
 */
internal fun pathCrumbs(
    uri: VfsUri,
    mountLabel: String,
    volumes: List<LocalVolume>,
): List<PathCrumb> {
    if (uri.scheme == "archive") return archiveCrumbs(uri, volumes)

    val rootLabel = mountLabelOf(uri, mountLabel, volumes)
    val crumbs = mutableListOf(
        PathCrumb(rootLabel, uri.copy(path = "/")),
    )
    var acc = ""
    for (seg in uri.path.trim('/').split('/')) {
        if (seg.isEmpty()) continue
        acc += "/$seg"
        crumbs += PathCrumb(seg, uri.copy(path = acc))
    }
    return crumbs.markCurrent()
}

private fun archiveCrumbs(
    uri: VfsUri,
    volumes: List<LocalVolume>,
): List<PathCrumb> {
    val host = PathInput.archiveHostOf(uri)
        ?: return listOf(PathCrumb("压缩包", uri, current = true))
    // 不能用当前标签页名：压缩包页的标签是「a.zip · ZIP」，不是宿主存储名。
    // 本地宿主按卷名，远程宿主退回主机名（能从层级菜单看出在哪台机器上）。
    val hostMount = mountLabelOf(host, "", volumes)
    val crumbs = mutableListOf<PathCrumb>()

    val parent = host.parent
    if (parent != null) {
        // 宿主目录层级：点哪一级就退出到哪一级
        pathCrumbs(parent, hostMount, volumes).forEach { crumbs += it.copy(mountLabel = hostMount) }
    } else {
        crumbs += PathCrumb(hostMount, host.copy(path = "/"), mountLabel = hostMount)
    }

    // 压缩包本身 = 包内根（仍在压缩包里，不能带宿主 mountLabel，否则标签页名会变回「内部存储」）
    crumbs += PathCrumb(host.name.ifBlank { "压缩包" }, PathInput.archiveUri(host, uri.authority, ""))

    var acc = ""
    for (seg in ArchiveVfs.parseInner(uri.path).split('/')) {
        if (seg.isEmpty()) continue
        acc += "/$seg"
        crumbs += PathCrumb(seg, PathInput.archiveUri(host, uri.authority, acc))
    }
    return crumbs.markCurrent()
}

private fun mountLabelOf(uri: VfsUri, fallback: String, volumes: List<LocalVolume>): String {
    if (uri.scheme == "local") {
        return PathInput.localLabel(uri.authority, volumes) ?: "本地存储"
    }
    return fallback.trim().ifBlank {
        uri.authority.substringBefore(':').ifBlank { uri.scheme }
    }
}

private fun List<PathCrumb>.markCurrent(): List<PathCrumb> =
    mapIndexed { index, crumb -> crumb.copy(current = index == lastIndex) }

/**
 * 顶栏展示用路径（MT 观感）：
 *  - 本地 → 真实文件系统路径（`/storage/emulated/0/…`）
 *  - 网络 → 存储内路径（`/home/u`，不带 `sftp://host` 前缀；连接名由标签页/侧栏表达）
 *  - 压缩包 → 宿主展示路径 + `!/` + 包内路径
 *
 * 与 [pathTextOf] 分开：复制 / 历史 / 输入框仍用可还原的完整路径。
 */
internal fun pathDisplayOf(uri: VfsUri, volumes: List<LocalVolume>): String = when (uri.scheme) {
    "local" -> pathTextOf(uri, volumes)
    "archive" -> {
        val host = PathInput.archiveHostOf(uri)
        if (host == null) VfsUris.stripped(uri).toString()
        else pathDisplayOf(host, volumes) + "!/" + ArchiveVfs.parseInner(uri.path)
    }
    else -> uri.displayPath
}

/**
 * VFS URI → 可复制的完整路径：
 *  - 本地：真实文件系统路径（`/storage/emulated/0/...`）
 *  - 网络：协议 URI（去掉内部连接参数 `?c=`）
 *  - 压缩包：宿主完整路径 + `!/` + 包内路径
 */
internal fun pathTextOf(uri: VfsUri, volumes: List<LocalVolume>): String = when (uri.scheme) {
    "local" -> {
        val base = volumes.firstOrNull { it.authority == uri.authority }?.path
            ?: when (uri.authority) {
                LocalVolumes.AUTHORITY_ROOT -> ""
                else -> null
            }
        if (base == null) {
            VfsUris.stripped(uri).toString()
        } else {
            val joined = base.trimEnd('/') + uri.path
            if (joined.isEmpty()) "/" else joined.replace(Regex("/{2,}"), "/").trimEnd('/').ifEmpty { "/" }
        }
    }
    "archive" -> {
        val host = PathInput.archiveHostOf(uri)
        if (host == null) VfsUris.stripped(uri).toString()
        else pathTextOf(host, volumes) + "!/" + ArchiveVfs.parseInner(uri.path)
    }
    else -> VfsUris.stripped(uri).toString()
}
