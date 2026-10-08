package com.u707t.panelfm.core.common

/** 扩展名 → MIME / 分类（图标着色、预览分派、S3 Content-Type 共用）。 */
object MimeTypes {

    private val map = mapOf(
        // ---- 图片 ----
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "jfif" to "image/jpeg",
        "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "svg" to "image/svg+xml", "svgz" to "image/svg+xml", "ico" to "image/x-icon", "avif" to "image/avif",
        // ---- 音频（m4b/m4r 是 mp4 家族；amr/awb 为平台必带解码器）----
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "m4b" to "audio/mp4", "m4r" to "audio/mp4",
        "aac" to "audio/aac", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "oga" to "audio/ogg", "wav" to "audio/x-wav", "opus" to "audio/opus",
        "ape" to "audio/x-ape", "wma" to "audio/x-ms-wma", "amr" to "audio/amr", "awb" to "audio/amr-wb",
        "mka" to "audio/x-matroska",
        // ---- 视频 ----
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "ts" to "video/mp2t", "m2ts" to "video/mp2t",
        "flv" to "video/x-flv", "rmvb" to "video/vnd.rn-realvideo",
        "3gp" to "video/3gpp", "3gpp" to "video/3gpp", "f4v" to "video/x-f4v",
        // ---- 文本 / 代码 ----
        "txt" to "text/plain", "log" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown", "json" to "application/json",
        "xml" to "application/xml", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "mjs" to "text/javascript", "cjs" to "text/javascript",
        "kt" to "text/x-kotlin", "kts" to "text/x-kotlin", "java" to "text/x-java", "py" to "text/x-python",
        "c" to "text/x-c", "h" to "text/x-c",
        "cc" to "text/x-c++", "cpp" to "text/x-c++", "cxx" to "text/x-c++", "hpp" to "text/x-c++", "hxx" to "text/x-c++",
        "cs" to "text/x-csharp", "go" to "text/x-go", "rs" to "text/x-rust", "rb" to "text/x-ruby",
        "php" to "text/x-php", "lua" to "text/x-lua", "swift" to "text/x-swift", "dart" to "text/x-dart",
        "scala" to "text/x-scala", "groovy" to "text/x-groovy", "pl" to "text/x-perl", "r" to "text/x-r",
        "jl" to "text/x-julia", "ex" to "text/x-elixir", "exs" to "text/x-elixir", "erl" to "text/x-erlang",
        "sh" to "text/x-sh", "ps1" to "text/x-powershell", "bat" to "text/x-batch", "cmd" to "text/x-batch",
        "properties" to "text/plain", "ini" to "text/plain", "conf" to "text/plain",
        "toml" to "text/x-toml", "gradle" to "text/x-groovy", "plist" to "application/xml",
        "yml" to "text/yaml", "yaml" to "text/yaml", "csv" to "text/csv", "sql" to "text/plain",
        "srt" to "application/x-subrip", "ass" to "text/x-ssa", "vtt" to "text/vtt", "lrc" to "text/plain",
        "diff" to "text/x-diff", "patch" to "text/x-diff",
        // ---- 压缩包（zip 家族可直接按压缩包浏览：epub / whl / nupkg / vsix / crx / kmz / xapk / apks / apkm）----
        "zip" to "application/zip", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "tar" to "application/x-tar", "gz" to "application/gzip", "xz" to "application/x-xz",
        "bz2" to "application/x-bzip2", "zst" to "application/zstd", "jar" to "application/java-archive",
        "lzma" to "application/x-lzma", "z" to "application/x-compress", "lz4" to "application/x-lz4",
        "cpio" to "application/x-cpio", "ar" to "application/x-archive", "deb" to "application/vnd.debian.binary-package",
        "tgz" to "application/gzip", "tbz2" to "application/x-bzip2", "txz" to "application/x-xz",
        "epub" to "application/epub+zip", "whl" to "application/zip", "nupkg" to "application/zip",
        "vsix" to "application/zip", "crx" to "application/x-chrome-extension",
        "kmz" to "application/vnd.google-earth.kmz", "xapk" to "application/zip",
        "apks" to "application/zip", "apkm" to "application/zip", "xpi" to "application/x-xpinstall",
        "apk" to "application/vnd.android.package-archive", "dex" to "application/x-dex",
        // ---- PDF / Office（含 OOXML 模板与宏格式、ODF 表格，见 OfficeFormats）----
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "docm" to "application/vnd.ms-word.document.macroEnabled.12",
        "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "dotm" to "application/vnd.ms-word.template.macroEnabled.12",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "xlsm" to "application/vnd.ms-excel.sheet.macroEnabled.12",
        "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "xltm" to "application/vnd.ms-excel.template.macroEnabled.12",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "fods" to "application/vnd.oasis.opendocument.spreadsheet-flat-xml",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "pptm" to "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
        "potm" to "application/vnd.ms-powerpoint.template.macroEnabled.12",
        // ---- 字体 / 其它 ----
        "ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection", "woff" to "font/woff",
        "woff2" to "font/woff2", "db" to "application/x-sqlite3", "sqlite" to "application/x-sqlite3",
        "sqlite3" to "application/x-sqlite3", "so" to "application/x-sharedlib",
        "bin" to "application/octet-stream", "iso" to "application/x-iso9660-image",
    )

    fun of(extension: String): String? = map[extension.lowercase()]

    enum class Kind { IMAGE, VIDEO, AUDIO, ARCHIVE, APK, TEXT, FONT, PDF, CODE, DOCUMENT, OTHER }

    fun kindOf(extension: String): Kind = when (extension.lowercase()) {
        "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "svgz", "ico", "avif" -> Kind.IMAGE
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts", "flv", "rmvb", "3gp", "3gpp", "f4v" -> Kind.VIDEO
        "mp3", "m4a", "m4b", "m4r", "aac", "flac", "ogg", "oga", "wav", "opus", "ape", "wma", "amr", "awb", "mka" -> Kind.AUDIO
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst", "jar", "lzma", "z", "lz4", "cpio", "ar", "deb",
        "tgz", "tbz2", "txz", "xpi",
        "epub", "whl", "nupkg", "vsix", "crx", "kmz", "xapk", "apks", "apkm",
        -> Kind.ARCHIVE
        "apk" -> Kind.APK
        "ttf", "otf", "ttc", "woff", "woff2" -> Kind.FONT
        "pdf" -> Kind.PDF
        // Office 文档：内置只读预览（WebView + 前端渲染库，见 OfficeFormats）
        "doc", "docx", "docm", "dotx", "dotm",
        "xls", "xlsx", "xlsm", "xltx", "xltm", "ods", "fods",
        "ppt", "pptx", "pptm", "ppsx", "potx", "potm",
        -> Kind.DOCUMENT
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "c", "h", "cc", "cpp", "cxx", "hpp", "hxx",
        "cs", "go", "rs", "rb", "php", "lua", "swift", "dart", "scala", "groovy", "pl", "r", "jl", "ex", "exs", "erl",
        "sh", "ps1", "bat", "cmd", "json", "xml", "yml", "yaml", "html", "htm", "css", "scss", "less",
        "sql", "toml", "gradle", "plist", "diff", "patch", "vue", "svelte",
        -> Kind.CODE
        "txt", "log", "md", "markdown", "ini", "conf", "properties", "csv", "srt", "ass", "vtt", "lrc" -> Kind.TEXT
        else -> Kind.OTHER
    }
}
