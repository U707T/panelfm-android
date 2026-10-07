package com.u707t.panelfm.core.ui

import kotlin.math.abs

/**
 * 文件列表行的**手势判定机**（复刻 MT 列表项的手势语义，用户实机确认版）。
 *
 * ## 判定顺序
 *
 * | 优先级 | 手势 | 判定 | 派发 |
 * |---|---|---|---|
 * | 1 | 长按 | 按住 ≥ [MtGesture.LongPressMs] | [Decision.LongPressMenu]：震动 + 弹该项**二级菜单**（不改选择） |
 * | 2 | 滑动选中 | 横向 ≥ [MtGesture.SwipeSelectDp] 且横向占优 | [Decision.SwipeSelect]：震动 + 进入多选 / 与锚点连成区间 |
 * | 3 | 点按 | 松手时位移 < [MtGesture.LongPressSlopDp] | [Decision.Tap] |
 * | 4 | 纵向拖动 | 纵向过 touchSlop 且纵向占优 | [Decision.HandOff]（交给列表滚动） |
 *
 * ## 与 v1.3.0 实现（已废弃）的差别
 *
 * 旧实现是「左右滑动进入**跟手扫选**（按住一路刷）+ 长按松手弹菜单 + 已多选右滑出菜单」。
 * 用户实机对照 MT 后确认：**跟手扫选是误触来源**、长按应当**立刻**弹二级菜单、
 * 右滑出菜单在文件列表上并不存在。现在：
 *
 *  - **滑动是离散动作**：一次滑动 = 选中那一行；再滑动另一行 = 连成区间。
 *    手势在滑动触发后就"结束"（吞掉后续事件到手指抬起），不会一路刷过去；
 *  - **长按立即弹菜单**（到达阈值那一刻，不等松手），且完全不碰选择；
 *  - 没有「右滑出菜单」这一条。
 *
 * 本类不引用任何 Compose 类型，单位统一用 **dp**（与 [MtGesture] 的阈值同一坐标系），可单测。
 */
class MtRowGesture(
    /** 系统 touch slop（dp）：纵向拖动越过它即让位给列表滚动 */
    private val touchSlopDp: Float,
    /** 长按 / 点按的位移容差（dp）：超过即认为「拖动过」，松手不再算点按 */
    private val longPressSlopDp: Float = MtGesture.LongPressSlopDp,
    /** 左右滑动选中的阈值（dp） */
    private val swipeSelectDp: Float = MtGesture.SwipeSelectDp,
    /** 长按触发时间（ms） */
    private val longPressMs: Long = MtGesture.LongPressMs,
) {

    /** 手势所处阶段（调用方在 [Phase.MENU] / [Phase.SWIPED] 期间消费事件） */
    enum class Phase {
        /** 已按下，还没定性 */
        PRESS,

        /** 二级菜单已弹出：吞掉后续事件，等手指抬起 */
        MENU,

        /** 滑动选中已触发：吞掉后续事件，等手指抬起 */
        SWIPED,

        /** 让位给列表滚动：此后不再接收判定 */
        HANDOFF,

        /** 本次手势结束 */
        DONE,
    }

    /** 一次手势的判定结果 */
    sealed interface Decision {
        /** 无事发生（含已让位 / 已结束阶段的事件） */
        data object None : Decision

        /** 点按（打开 / 预览；多选态 = 加/减选） */
        data object Tap : Decision

        /** 长按成立：震动 + 弹该项二级菜单（不改选择、不进多选） */
        data object LongPressMenu : Decision

        /** 滑动选中：进入多选 / 与滑动锚点连成区间；[towardRight] = 滑动方向（动效用） */
        data class SwipeSelect(val towardRight: Boolean) : Decision

        /** 纵向拖动：调用方停止消费事件，交给列表滚动 */
        data object HandOff : Decision
    }

    var phase: Phase = Phase.DONE
        private set

    /** 按下时所在行（-1 = 没按在真实行上）；判定用它的项 */
    var downIndex: Int = -1
        private set

    /** 一次新手势开始（在 `awaitFirstDown` 之后调用） */
    fun begin(downIndex: Int) {
        this.downIndex = downIndex
        phase = Phase.PRESS
    }

    /**
     * 手势被外部终结（切单/双列、切目录、组合被销毁……）。
     * 不再派发任何 [Decision]，只让状态机回到可复用的初始态。
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
     */
    fun update(dx: Float, dy: Float, elapsedMs: Long, pressed: Boolean): Decision =
        when (phase) {
            Phase.DONE, Phase.HANDOFF -> Decision.None

            // 菜单 / 滑动已触发：不派发新动作，等手指抬起（松手后回到 DONE）
            Phase.MENU, Phase.SWIPED -> {
                if (!pressed) phase = Phase.DONE
                Decision.None
            }

            Phase.PRESS -> when {
                // 松手：先定性（长按 / 点按 / 拖过一段距离后松手 = 什么都不做）。
                // 必须放在长按阈值之前：事件可能很稀疏（按住 500ms 后直接松手、
                // 中途一个 MOVE 都没有），这时要在这一帧里把长按判掉。
                !pressed -> {
                    phase = Phase.DONE
                    when {
                        downIndex >= 0 && elapsedMs >= longPressMs -> Decision.LongPressMenu
                        // 防误触：判定「拖动过」用长按位移容差（12dp）而不是系统 touchSlop（约 8dp）
                        abs(dx) > longPressSlopDp || abs(dy) > longPressSlopDp -> Decision.None
                        else -> Decision.Tap
                    }
                }

                // 长按阈值到达 → 立即弹菜单（MT 是"快速出来二级菜单"，不等松手）。
                // 只有按在**真实行**上才算：按在列表空白处不该弹菜单。
                downIndex >= 0 && elapsedMs >= longPressMs -> {
                    phase = Phase.MENU
                    Decision.LongPressMenu
                }

                // 纵向过 touchSlop 且纵向占优 → 列表滚动（不消费事件，让 LazyColumn 接管）
                abs(dy) > touchSlopDp && abs(dy) >= abs(dx) -> {
                    phase = Phase.HANDOFF
                    Decision.HandOff
                }

                // 左右滑动（≥ 24dp 且横向 > 纵向×2）→ 选中（或连区间）。
                // 必须「按在真实行上」——按在列表空白处滑动不算。
                downIndex >= 0 && MtGesture.isSwipeSelect(dx, dy) -> {
                    phase = Phase.SWIPED
                    Decision.SwipeSelect(towardRight = dx > 0f)
                }

                else -> Decision.None
            }
        }
}
