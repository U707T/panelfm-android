package com.u707t.panelfm.ui.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import com.u707t.panelfm.AppContainer
import com.u707t.panelfm.core.vfs.VfsUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.util.zip.GZIPInputStream

/**
 * SVG / SVGZ 渲染（图片查看器专用）。
 *
 * 背景：`.svg` 一直被分到 [com.u707t.panelfm.core.common.MimeTypes.Kind.IMAGE]，
 * 但图片管线走 `BitmapFactory` —— 它**不支持 SVG**，等于「登记了却打不开」。
 * 这里用 AndroidSVG（Apache-2.0，~200KB）补齐：
 *  - 渲染到一张长边 [SVG_MAX_EDGE] 的位图（与照片预览的采样上限一致），
 *    之后缩放 / 翻页 / 双击放大全部复用现有图片查看器；
 *  - **SVGZ 按内容识别**（gzip 魔数 1f 8b）再解压，不只看后缀；
 *  - 只做「渲染成位图」，不支持交互（脚本不会执行；AndroidSVG 本身不执行脚本）。
 */
internal const val SVG_MAX_EDGE = 2048

/**
 * SVG 渲染目标尺寸（**纯函数**，有单测）：
 *  - [aspect]（宽/高）有效且 ≥ 1 → 宽 = maxEdge、高 = maxEdge / aspect；
 *  - < 1 → 高 = maxEdge、宽 = maxEdge × aspect；
 *  - 比例未知（-1 / 0 / NaN，例如只有百分比尺寸又没有 viewBox）→ 正方形兜底。
 * 矢量图不放大没有意义，所以统一按长边渲染（小图标也清晰）。
 */
internal fun svgRenderSize(aspect: Float, maxEdge: Int = SVG_MAX_EDGE): Pair<Int, Int> {
    val a = if (aspect.isFinite() && aspect > 0f) aspect else 1f
    return if (a >= 1f) {
        maxEdge to (maxEdge / a).toInt().coerceAtLeast(1)
    } else {
        (maxEdge * a).toInt().coerceAtLeast(1) to maxEdge
    }
}

/** gzip 魔数（SVGZ 的判定，纯函数） */
internal fun isGzipMagic(b0: Int, b1: Int): Boolean = b0 == 0x1F && b1 == 0x8B

/** 解析 + 渲染 SVG/SVGZ 为位图。失败抛 [IllegalStateException]（调用方统一成「图片预览失败：…」）。 */
internal suspend fun decodeSvg(container: AppContainer, uri: VfsUri): Bitmap =
    withContext(Dispatchers.IO) {
        val svg = try {
            openPreviewStream(container, uri).use { raw ->
                val buffered = BufferedInputStream(raw, 64 * 1024)
                buffered.mark(2)
                val b0 = buffered.read()
                val b1 = buffered.read()
                buffered.reset()
                val source = if (isGzipMagic(b0, b1)) GZIPInputStream(buffered) else buffered
                SVG.getFromInputStream(source)
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("SVG 文档为空或根元素缺失")
        } catch (e: Exception) {
            throw IllegalStateException("SVG 解析失败：${e.message ?: e.javaClass.simpleName}")
        }
        val aspect = runCatching { svg.documentAspectRatio }.getOrDefault(-1f)
        val (w, h) = svgRenderSize(aspect)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            // renderToCanvas 会把文档按 preserveAspectRatio 缩放到整张画布（canvas 即视口）
            svg.renderToCanvas(Canvas(bitmap))
        } catch (e: Exception) {
            bitmap.recycle()
            throw IllegalStateException("SVG 渲染失败：${e.message ?: e.javaClass.simpleName}")
        }
        bitmap
    }
