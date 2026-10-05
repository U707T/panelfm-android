package com.u707t.panelfm.core.ui

import kotlin.math.abs

/**
 * 文件列表行的**手势判定机**（复刻 MT 列表项的手势语义）。
 *
 * ## 为什么单独抽出来
 *
 * 旧实现把这套判定写成 `MtFileRow` 里的一坨 `awaitPointerEvent + when`：想验证「400ms 到达时
 * 该不该震」「右滑 48dp 在什么条件下弹菜单」只能上真机点，改一个阈值就可能悄悄破坏另一条分支
 * （`docs/AUDIT-2026-10-05-CODE-TRUTH.md` 里记过一次类似事故：播放器手势塞进同一个状态机）。
 *
 * 现在：**判定逻辑在这里（纯 Kotlin，可单测），指针事件的分发留在 Compose 侧（`PaneView`）**。
 * 本类不引用任何 Compose 类型，单位统一用 **dp**（与 [MtGesture] 的阈值同一坐标系）。
 *
 * ## 判定顺序（文档附录 F.5 的冲突消解顺序）
 *
 * | 优先级 | 手势 | 判定 | 派发 |
 * |---|---|---|---|
 * | 1 | 长按 | 按住 ≥ [MtGesture.LongPressMs]，位移 < [MtGesture.LongPressSlopDp] | [Decision.LongPressArmed]（震动）→ 松手 [Decision.LongPress]（弹菜单） |
 * | 2 | 扫选 | 横向 ≥ [MtGesture.SwipeSelectDp] 且横向占优 | [Decision.EnterSweep] → 逐行 [Decision.SweepTo] → 松手 [Decision.SweepEnd] |
 * | 3 | 右滑出菜单 | 已多选 + 右滑 ≥ [MtGesture.SwipeMenuDp]（仅一次） | [Decision.SwipeMenu] |
 * | 4 | 点按 | 松手时位移 < [MtGesture.LongPressSlopDp] | [Decision.Tap] |
 * | 5 | 纵向拖动 | 纵向过 touchSlop 且纵向占优 | [Decision.HandOff]（不再消费事件，交给列表滚动） |
 *
 * ## 用法
 *
 * ```kotlin
 * val machine = MtRowGesture(touchSlopDp = ..., longPressSlopDp = ..., swipeSelectDp = ..., swipeMenuDp = ...)
 * awaitEachGesture {
 *     machine.begin(downIndex = 3, selectingAtStart = false)
 *     while (true) {
 *         val event = awaitPointerEvent()
 *         when (val d = machine.update(dx = .., dy = .., elapsedMs = .., pressed = .., index = ..)) { ... }
 *     }
 * }
 * ```
 */
class MtRowGesture(
    /** 系统 touch slop（dp）：纵向拖动越过它即让位给列表滚动 */
    private val touchSlopDp: Float,
    /** 长按 / 点按的位移容差（dp）：超过即认为「拖动过」，松手不再算点按 */
    private val longPressSlopDp: Float = MtGesture.LongPressSlopDp,
    /** 左右滑动进入多选的阈值（dp） */
    private val swipeSelectDp: Float = MtGesture.SwipeSelectDp,
    /** 已多选态右滑出更多操作的阈值（dp） */
    private val swipeMenuDp: Float = MtGesture.SwipeMenuDp,
    /** 长按触发时间（ms） */
    private val longPressMs: Long = MtGesture.LongPressMs,
) {

    /** 手势所处阶段（调用方只需在 [Phase.SWEEP] 期间消费事件） */
    enum class Phase {
        /** 已按下，还没定性 */
        PRESS,

        /** 扫选进行中（滑动进入多选之后） */
        SWEEP,

        /** 长按已成立，等松手弹菜单 */
        LONG_PRESS,

        /** 让位给列表滚动：此后不再接收判定 */
        HANDOFF,

        /** 本次手势结束 */
        DONE,
    }

    /** 一次手势的判定结果 */
    sealed interface Decision {
        /** 无事发生（含已让位 / 已结束阶段的事件） */
        data object None : Decision

        /** 点按（打开 / 预览；多选态 = 切换选中） */
        data object Tap : Decision

        /** 长按成立（400ms 到达）：调用方给一次震动反馈 */
        data object LongPressArmed : Decision

        /** 长按完成（松手）：调用方弹动作菜单 */
        data object LongPress : Decision

        /** 进入扫选（第一次越过滑动阈值）：调用方按「按下那一行」进入多选 */
        data object EnterSweep : Decision

        /** 手指（或边缘自动滚动）落到新的一行：调用方重算区间 */
        data class SweepTo(val index: Int) : Decision

        /** 扫选结束（松手）：调用方收尾（丢弃扫选会话、动效回弹） */
        data object SweepEnd : Decision

        /** 已多选态右滑出更多操作 */
        data object SwipeMenu : Decision

        /** 纵向拖动：调用方停止消费事件，交给列表滚动 */
        data object HandOff : Decision
    }

    var phase: Phase = Phase.DONE
        private set

    /** 按下时所在行（-1 = 没按在真实行上）；扫选锚点用它，保证锚点稳定 */
    var downIndex: Int = -1
        private set

    private var lastIndex = -1
    private var menuFired = false
    private var selectingAtStart = false

    /** 一次新手势开始（在 `awaitFirstDown` 之后调用） */
    fun begin(downIndex: Int, selectingAtStart: Boolean) {
        this.downIndex = downIndex
        this.selectingAtStart = selectingAtStart
        lastIndex = downIndex
        menuFired = false
        phase = Phase.PRESS
    }

    /**
     * 手势被外部终结（切单/双列、切目录、组合被销毁……）。
     *
     * 与「松手」的区别：不再派发任何 [Decision]（调用方已经在收尾了），
     * 只是让状态机回到可复用的初始态。
     */
    fun cancel() {
        phase = Phase.DONE
    }

    /**
     * 喂一个指针事件。
     *
     * @param dx 相对按下点的横向位移（dp，右正左负）
     * @param dy 相对按下点的纵向位移（dp，下正上负）
     * @param elapsedMs 距按下的毫秒数
     * @param pressed 该指针是否仍按下
     * @param index 当前手指所在行（-1 = 未命中真实行）
     */
    fun update(dx: Float, dy: Float, elapsedMs: Long, pressed: Boolean, index: Int): Decision =
        when (phase) {
            Phase.DONE, Phase.HANDOFF -> Decision.None

            Phase.LONG_PRESS ->
                if (pressed) Decision.None
                else {
                    phase = Phase.DONE
                    Decision.LongPress
                }

            Phase.SWEEP -> when {
                // MT 0x7f110697「右滑列表项可进行更多操作」：只在**按下时就已多选**的情况下生效，
                // 且要求纵向位移仍然很小（否则向下扫选区间时会误弹菜单）；一次手势只触发一次。
                !menuFired && selectingAtStart && MtGesture.isSwipeMenu(dx, dy) && abs(dy) < swipeMenuDp -> {
                    menuFired = true
                    phase = Phase.DONE
                    Decision.SwipeMenu
                }

                !pressed -> {
                    phase = Phase.DONE
                    Decision.SweepEnd
                }

                else -> sweepMove(index)
            }

            Phase.PRESS -> when {
                // 松手：先定性（长按 / 点按 / 拖过一段距离后松手 = 什么都不做）。
                // 必须放在长按阈值之前：事件可能很稀疏（按住 500ms 后直接松手，
                // 中途一个 MOVE 都没有），这时要在这一帧里直接把长按判掉。
                !pressed -> {
                    phase = Phase.DONE
                    when {
                        downIndex >= 0 && elapsedMs >= longPressMs -> Decision.LongPress
                        // 防误触：判定「拖动过」用长按位移容差（12dp）而不是系统 touchSlop（约 8dp）
                        abs(dx) > longPressSlopDp || abs(dy) > longPressSlopDp -> Decision.None
                        else -> Decision.Tap
                    }
                }

                // 长按阈值到达：先震动提示（MT 的 400ms 比系统默认 500ms 灵敏）。
                // 只有按在**真实行**上才进入长按态：按在列表空白处不该震动 / 弹菜单。
                downIndex >= 0 && elapsedMs >= longPressMs -> {
                    phase = Phase.LONG_PRESS
                    Decision.LongPressArmed
                }

                // 纵向过 touchSlop 且纵向占优 → 列表滚动（不消费事件，让 LazyColumn 接管）
                abs(dy) > touchSlopDp && abs(dy) >= abs(dx) -> {
                    phase = Phase.HANDOFF
                    Decision.HandOff
                }

                // 左右滑动（≥ 24dp 且横向 > 纵向×2）→ 进入多选。
                // 两个方向都能进（`0x7f1106f3`「左右滑动文件可直接选择」）；
                // 但必须是「按在真实行上」——按在列表空白处滑动不算。
                downIndex >= 0 && MtGesture.isSwipeSelect(dx, dy) -> {
                    phase = Phase.SWEEP
                    Decision.EnterSweep
                }

                else -> Decision.None
            }
        }

    /**
     * 手指静止、但列表被**边缘自动滚动**带着跑时用它重算区间：
     * 位置没变、下标变了（长列表连选必须能滚过屏幕之外）。
     */
    fun indexChanged(index: Int): Decision =
        if (phase == Phase.SWEEP) sweepMove(index) else Decision.None

    /** 扫选期间的「落到新行」判定：只在**真的换行**时派发，避免同一行反复重算 */
    private fun sweepMove(index: Int): Decision {
        if (index < 0 || index == lastIndex) return Decision.None
        lastIndex = index
        return Decision.SweepTo(index)
    }
}
