package com.u707t.panelfm.core.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 全应用统一的显示格式化（大小 / 时间 / 速度 / 剩余时间）。 */
object Fmt {

    /**
     * 是否在列表时间中显示秒（设置项）。
     * MT 的默认格式本来就带秒，这个开关用于切到「不带秒」的紧凑写法。
     */
    var showSeconds: Boolean = false
        set(value) {
            field = value
            datePattern = if (value) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
        }

    /**
     * MT 的时间日期格式预设（`0x7f03000a`，三项原文）：
     *   ① `yyyy-MM-dd HH:mm:ss` ② `dd-MM-yyyy HH:mm:ss` ③ `HH:mm:ss dd-MM-yyyy`
     *
     * ⚠️ 但 MT **列表副标题实测**是 `26-10-04 13:16`（= `yy-MM-dd HH:mm`，见截图），
     * 不带秒也不带世纪 —— 这是 MT 列表的默认写法，所以这里默认用它。
     */
    var datePattern: String = "yy-MM-dd HH:mm"
        set(value) {
            field = value
            cachedListTimeFmt = null
        }

    /**
     * 列表时间格式器缓存。
     *
     * `DateTimeFormatter.ofPattern` 每次调用都要重新解析模式（有实测开销），
     * 而列表副标题在滚动时会被**每一行**反复调用 → 旧实现等于在滚动期间持续重建 formatter。
     * 这里按 [datePattern] 缓存，模式变化（`showSeconds` 切换）时置空重建。
     */
    @Volatile
    private var cachedListTimeFmt: DateTimeFormatter? = null

    private val listTimeFmt: DateTimeFormatter
        get() = cachedListTimeFmt ?: DateTimeFormatter.ofPattern(datePattern).also { cachedListTimeFmt = it }

    private val fullTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val units = arrayOf("B", "K", "M", "G", "T", "P")

    /**
     * 文件大小（MT 观感）：`384.95 G` / `1.50 M` / `512 B`。
     *
     * MT 的单位是**单字母**且**保留两位小数**（截图实测 `储存: 384.95G/479.51G`、
     * 侧拉栏 `384.71G已用 , 94.80G可用`），旧实现是 `384 GB` / `1.5 MB`（双字母 + 大数值取整）。
     */
    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[i])
    }

    /** 紧凑大小（无空格，MT 顶栏「储存: 384.9G/479.5G」的写法） */
    fun sizeCompact(bytes: Long): String = size(bytes).replace(" ", "")

    /** 列表副标题里的时间（MT 默认 `yyyy-MM-dd HH:mm:ss`） */
    fun time(epochMs: Long): String =
        if (epochMs <= 0) "" else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(listTimeFmt)

    fun fullTime(epochMs: Long): String =
        if (epochMs <= 0) "" else Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(fullTimeFmt)

    fun speed(bytesPerSec: Long): String = if (bytesPerSec <= 0) "" else size(bytesPerSec) + "/s"

    /** 剩余时间：秒 → "1:23:45" / "12:03" */
    fun eta(seconds: Long): String {
        if (seconds < 0 || seconds > 30L * 24 * 3600) return "--:--"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun duration(ms: Long): String = eta(ms / 1000)

    /** 权限：POSIX mode → "rwxr-xr-x" */
    fun mode(mode: Int): String {
        val sb = StringBuilder()
        val rwx = "rwxrwxrwx"
        for (i in 0 until 9) {
            val bit = 1 shl (8 - i)
            sb.append(if (mode and bit != 0) rwx[i] else '-')
        }
        return sb.toString()
    }

    /**
     * 完整权限串（MT 属性样式）：类型字符 + 9 位权限 + setuid/setgid/sticky 特殊位。
     * 例：`drwxrws---` / `-rw-r--r--` / `lrwxrwxrwx`
     */
    fun modeLong(mode: Int, isDirectory: Boolean, isLink: Boolean = false, isSocket: Boolean = false, isFifo: Boolean = false): String {
        val sb = StringBuilder()
        sb.append(
            when {
                isLink -> 'l'
                isDirectory -> 'd'
                isSocket -> 's'
                isFifo -> 'p'
                else -> '-'
            }
        )
        val rwx = "rwxrwxrwx"
        for (i in 0 until 9) {
            val bit = 1 shl (8 - i)
            sb.append(if (mode and bit != 0) rwx[i] else '-')
        }
        // 特殊位（位于 mode 高位）：setuid 0x800 / setgid 0x400 / sticky 0x200
        if (mode and 0x800 != 0) sb[3] = if (mode and 0x40 != 0) 's' else 'S'
        if (mode and 0x400 != 0) sb[6] = if (mode and 0x8 != 0) 's' else 'S'
        if (mode and 0x200 != 0) sb[9] = if (mode and 0x1 != 0) 't' else 'T'
        return sb.toString()
    }

    /** 传输速率的人话描述（用于任务行副标题） */
    fun transferred(done: Long, total: Long): String = when {
        total > 0 -> "${size(done)} / ${size(total)}"
        done > 0 -> size(done)
        else -> ""
    }

    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done * 100 / total).coerceIn(0, 100)).toInt()

    fun count(n: Int): String = if (n > 9999) "${n / 1000}k" else n.toString()

    /** 用于日志/调试的比较：比较两个大小差异是否 > 1%（校验辅助） */
    fun sizeDiffers(a: Long, b: Long): Boolean = if (a <= 0 || b <= 0) a != b else abs(a - b) > 0
}

/**
 * MT「文件列表显示策略」三档的副标题渲染（文案 `0x7f110200 / 201 / 202`）：
 *  - 0 = 文件列表不显示权限 → 只有时间
 *  - 1 = 非存储目录下的文件显示「权限+大小」（默认档）
 *  - 2 = 全部目录下的文件显示「时间+大小」
 *
 * 抽成纯函数是为了可单测（原实现内联在 PaneView 里，改档位只能靠肉眼验证）。
 */
object MtListSubtitle {

    /** 与 MT 一致的档位名（设置页展示用） */
    val MODE_LABELS = listOf(
        "文件列表不显示权限",
        "非存储目录下的文件显示「权限+大小」",
        "全部目录下的文件显示「时间+大小」",
    )

    /**
     * 组装一行副标题。
     *
     * @param mode 0/1/2，越界按默认档 1 处理
     * @param permissionText 权限串（如 `drwxrws---`），null = 该协议不提供
     * @param timeText 时间串（已格式化），空串 = 无时间
     * @param sizeText 大小串（已格式化），空串 = 无大小（目录/未知）
     */
    fun render(
        mode: Int,
        permissionText: String?,
        timeText: String,
        sizeText: String,
    ): String {
        fun join(vararg parts: String?): String =
            parts.filter { !it.isNullOrBlank() }.joinToString("  ·  ")

        return when (mode.coerceIn(0, 2)) {
            0 -> timeText
            2 -> join(timeText, sizeText)
            else -> {
                val withPerm = join(permissionText, sizeText)
                // 权限与大小都拿不到时退回时间（MT 的列表不会出现空副标题）
                if (withPerm.isBlank()) timeText else withPerm
            }
        }
    }
}
