package com.u707t.panelfm.core.ui

import androidx.compose.ui.platform.ViewConfiguration

/**
 * MT 口径的 [ViewConfiguration]（v2.0.14）。
 *
 * 只改一处：长按超时 500ms（系统默认）→ [MtGesture.LongPressMs]（400ms，MT 口径）；
 * 其余（touchSlop / 双击超时等）原样委托系统值。
 *
 * 用法：在组合根部（MainActivity）以 `LocalViewConfiguration` 提供 —— Compose 的
 * `combinedClickable` / `detectTapGestures` 都从作用域里的 viewConfiguration 读取长按超时
 * （已在 foundation 1.11 的 `CombinedClickableNode` / `TapGestureDetectorKt` 字节码中核实引用），
 * 因此一处覆盖即可让**全 App 的长按**与文件列表手势机（[MtGesture.LongPressMs]）同拍。
 */
class MtViewConfiguration(base: ViewConfiguration) : ViewConfiguration by base {

    override val longPressTimeoutMillis: Long
        get() = MtGesture.LongPressMs
}
