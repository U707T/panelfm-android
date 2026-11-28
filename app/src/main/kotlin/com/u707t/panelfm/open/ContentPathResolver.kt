package com.u707t.panelfm.open

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.u707t.panelfm.core.common.Logx

/** 反查结果。 */
internal sealed interface ContentLocation {
    /** 可定位的真实路径（已规范化）。 */
    data class Located(val absolutePath: String) : ContentLocation

    /** 真实路径存在但不可浏览（`Android/data` 等）：只能定位副本。 */
    data class Protected(val absolutePath: String) : ContentLocation

    /** 完全反查不到：保持原有「复制到接收目录」行为。 */
    data object Unknown : ContentLocation
}

/**
 * 把外部 `content://` 反查成本地路径。
 *
 * Android 没有「定位这个 URI」的系统能力，只能靠 provider 自己暴露的信息推断：
 *  1. `DocumentsContract` 的 document id（`raw:` / `primary:` / `home:` / 卷 id）；
 *  2. `MediaStore` 的 `_data` 列（需要「所有文件访问」，本项目已申请）；
 *  3. 都没有 → [ContentLocation.Unknown]，调用方降级为复制副本。
 *
 * 任何一步抛异常都只记日志并继续下一步：**反查失败绝不能影响收文件本身**。
 */
internal class ContentPathResolver(private val resolver: ContentResolver) {

    fun resolve(uri: Uri): ContentLocation {
        documentIdPath(uri)?.let { path ->
            return when (localPathVisibility(path)) {
                LocalPathVisibility.BROWSABLE -> ContentLocation.Located(path)
                LocalPathVisibility.PROTECTED -> ContentLocation.Protected(path)
                LocalPathVisibility.UNKNOWN -> ContentLocation.Unknown
            }
        }
        mediaStorePath(uri)?.let { path ->
            return when (localPathVisibility(path)) {
                LocalPathVisibility.BROWSABLE -> ContentLocation.Located(path)
                LocalPathVisibility.PROTECTED -> ContentLocation.Protected(path)
                LocalPathVisibility.UNKNOWN -> ContentLocation.Unknown
            }
        }
        return ContentLocation.Unknown
    }

    /**
     * ⚠️ 不能先调 `DocumentsContract.isDocumentUri(context, uri)` 再取 id：
     * 那个方法在 scheme 为 content 时会走 `context.getPackageManager()`，
     * 传 null Context 直接 NPE（被 runCatching 吞掉 → 反查永远失败，等于功能全废）。
     * `getDocumentId` 本身只做 pathSegments 的字符串检查，不需要 Context；
     * 不是 document URI 时它会抛 IllegalArgumentException，由外层降级。
     */
    private fun documentIdPath(uri: Uri): String? = runCatching {
        documentIdToAbsolutePath(DocumentsContract.getDocumentId(uri))
    }.onFailure {
        Logx.w("Incoming", "document id lookup failed: ${it.message}", it)
    }.getOrNull()

    private fun mediaStorePath(uri: Uri): String? = runCatching {
        // MediaStore 的 _data 在 Android 10+ 仍可读（本应用持有 MANAGE_EXTERNAL_STORAGE），
        // 但部分 ROM 会直接拒绝这一列，所以必须 try/catch 并接受 null。
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
        }
    }.onFailure {
        Logx.w("Incoming", "media store data lookup failed: ${it.message}", it)
    }.getOrNull()
}
