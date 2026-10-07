package com.u707t.panelfm.core.common

/** 扩展名 → MIME / 分类（图标着色、预览分派、S3 Content-Type 共用）。 */
object MimeTypes {

    private val map = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "svg" to "image/svg+xml", "ico" to "image/x-icon", "avif" to "image/avif",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "ogg" to "audio/ogg", "wav" to "audio/x-wav", "opus" to "audio/opus", "ape" to "audio/x-ape",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "mov" to "video/quicktime", "ts" to "video/mp2t", "flv" to "video/x-flv", "rmvb" to "video/vnd.rn-realvideo",
        "txt" to "text/plain", "log" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "xml" to "application/xml", "html" to "text/html", "htm" to "text/html", "css" to "text/css",
        "js" to "text/javascript", "kt" to "text/x-kotlin", "java" to "text/x-java", "py" to "text/x-python",
        "sh" to "text/x-sh", "properties" to "text/plain", "ini" to "text/plain", "conf" to "text/plain",
        "yml" to "text/yaml", "yaml" to "text/yaml", "csv" to "text/csv", "sql" to "text/plain",
        "zip" to "application/zip", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
        "tar" to "application/x-tar", "gz" to "application/gzip", "xz" to "application/x-xz",
        "bz2" to "application/x-bzip2", "zst" to "application/zstd", "jar" to "application/java-archive",
        "apk" to "application/vnd.android.package-archive", "dex" to "application/x-dex",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "ttf" to "font/ttf", "otf" to "font/otf", "ttc" to "font/collection", "woff" to "font/woff",
        "woff2" to "font/woff2", "db" to "application/x-sqlite3", "so" to "application/x-sharedlib",
        "bin" to "application/octet-stream", "iso" to "application/x-iso9660-image",
    )

    fun of(extension: String): String? = map[extension.lowercase()]

    enum class Kind { IMAGE, VIDEO, AUDIO, ARCHIVE, APK, TEXT, FONT, PDF, CODE, DOCUMENT, OTHER }

    fun kindOf(extension: String): Kind = when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg", "ico", "avif" -> Kind.IMAGE
        "mp4", "mkv", "webm", "avi", "mov", "ts", "flv", "rmvb", "m4v", "3gp" -> Kind.VIDEO
        "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus", "ape", "wma" -> Kind.AUDIO
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "zst", "jar" -> Kind.ARCHIVE
        "apk" -> Kind.APK
        "ttf", "otf", "ttc", "woff", "woff2" -> Kind.FONT
        "pdf" -> Kind.PDF
        // Office 文档：内置只读预览（WebView + 前端渲染库，见 OfficeFormats）
        "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> Kind.DOCUMENT
        "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh", "json", "xml", "yml", "yaml", "html", "css", "sql" -> Kind.CODE
        "txt", "log", "md", "ini", "conf", "properties", "csv" -> Kind.TEXT
        else -> Kind.OTHER
    }
}
