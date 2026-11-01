# 审计：视频「无法播放、但能缓冲 / 拖动看画面」（2026-11-02）

> 现象（用户原始描述）：**视频无法播放，但是可以缓存（进度条缓冲层在涨）和拖动查看图像。**
> 范围：`app/src/main/kotlin/com/u707t/panelfm/ui/preview/` 的播放链路
> （`MediaScreen` / `MediaChrome` / `MediaGestures` / `MediaDataSource`）。
> 方法：全链路通读 + `git blame` 逐行溯源 + Media3 1.10.1 字节码核对
> （`PlayerView.setPlayer` / `Player` 常量 / `ExoPlayer.Builder` 能力）+ 单测回归。
> ⚠️ 沙箱无 `/dev/kvm`、`adb` 起不来，UI 行为结论来自代码路径 + 单元测试，**待真机复核**。

## §0 结论（一句话）

**不是播放器坏了，是「播放态」判断用了 `isPlaying`（这一帧有没有真的在出）而不是
`playWhenReady`（用户要不要它播）**。缓冲中与被音频焦点抑制时前者恒为 false、后者已经是 true，
于是「播放键」显示成 ▶、按下去 `play()` 是空操作（`playWhenReady` 本来就是 true），
进度条也因为「只在 isPlaying 时轮询」而冻住 —— 而缓冲与 seek 完全不依赖播放时钟，
所以缓冲条照涨、拖动照出画面。**三条现象是同一个根因。**

## §1 逐条取证

### 🔴 1. 播放键按 `isPlaying` 切换 → 缓冲中 / 被抑制时"点不动"

- 位置：`MediaScreen.kt:652`（旧）、`MediaGestures.kt:88`（旧）
- 代码：`onTogglePlay = { if (player.isPlaying) player.pause() else player.play() }`
- 问题：`isPlaying == false` 时调 `play()`，而 `playWhenReady` 已经是 `true` → **零状态变化**。
  同理 `doubleTapSeek` 的中间分支。
- 引入：`649cd36`（v0.6.0 初版播放器）→ `db5c139` → `3479639`（拆分时原样搬运，未审）。
- 证据：Media3 `Player.isPlaying` 的语义是 `playWhenReady && state==READY && 未被抑制`；
  项目自己在 `3e340dc`（v2.1.3）的注释里已经写明这个陷阱（"判定用 playWhenReady 而不是 isPlaying"），
  但只改了 `ON_STOP` 一处，播放键与续播逻辑没跟上。

### 🔴 2. 进度/缓冲轮询以 `isPlaying` 为开关 → 缓冲中两条进度一起冻住

- 位置：`MediaScreen.kt:241`（旧）：`LaunchedEffect(player, isPlaying) { if (!isPlaying) return ... }`
- 引入：`c161155`（v1.10.x 的 U16「250ms 无条件轮询 → 仅播放中 500ms」）。
  优化本身是对的（省电），但**把"缓冲中"一起排除了**。
- 为什么是问题：缓冲中的 `bufferedPosition` 一直在涨，可界面不刷新 —— 用户既看不到"在加载"，
  也看不到"卡住了"，两者观感完全一致。

### 🔴 3. 播放抑制（音频焦点被占 / 输出设备不可用）全仓零处理、零提示

- 全仓 grep：`onPlaybackSuppressionReasonChanged` / `playbackSuppressionReason` /
  `suppressPlaybackOnUnsuitableOutput` / `handleAudioBecomingNoisy` **命中 0 处**。
- 后果：ExoPlayer 会进入「`playWhenReady=true` 但 `isPlaying=false` 且位置不推进」的状态
  （`PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS` / `UNSUITABLE_AUDIO_ROUTE` /
  `UNSUITABLE_AUDIO_OUTPUT`），界面上与「坏掉」无法区分。
- 引入：`649cd36` 起就没有；`setAudioAttributes(attrs, handleAudioFocus = true)` 一直是开的。

### 🔴 4. v2.1.3「退到后台即暂停」只暂停、不续播 → 回来就是"停住的一帧"

- 位置：`MediaScreen.kt:117-127`（旧 `ON_STOP` 观察者）
- 代码：`if (event == ON_STOP && !backgroundPlay && playWhenReady) player.pause()`
- 后果：切出去接个电话 / 系统权限页盖上来 / 熄屏再亮，回到播放器时：画面是静止的一帧、
  缓冲条继续涨、拖动进度条还能看到画面 —— **与用户描述逐字吻合**。
- 引入：`3e340dc`（v2.1.3）。当时的设计注释写的是"回前台不自动续播"，但那条取舍只在
  "用户主动退出"时成立；对"被动中断"（权限页、来电、误触 Home）必须续播，否则就是 bug。
- 修复：区分「本页自动暂停」与「用户手动暂停」，只有前者在 `ON_START` 续播。

### 🟡 5. 拖动进度条的"续播"判定同样用 `isPlaying`

- 位置：`MediaScreen.kt:629`（旧）`onSeekStart = { ui.wasPlayingBeforeSeek = player.isPlaying ... }`
- 引入：`aa5995b`（v1.2.4）。
- 后果：缓冲中拖动（`isPlaying=false`）→ 松手后不续播，等于"拖一下就再也不播了"。

## §2 本轮修复（已落码）

| # | 修复 | 位置 |
|---|---|---|
| 1 | 播放键按 `playWhenReady` 的意图切换，抑制态 `pause()`→`play()` 重新申请焦点，`IDLE` 先 `prepare()`，`ENDED` 从 0 重放 | `MediaPlaybackPolicy.planToggle` + `MediaScreen.togglePlay()` |
| 2 | 播放键图标与动作同源（`showPauseIcon`），不再出现"显示播放、按下去是暂停/空操作" | `MediaChrome.MediaBottomBar(showPause=…)` |
| 3 | 轮询开关改为 `isPlaying ∨ playWhenReady ∨ BUFFERING` | `shouldRefreshProgress` |
| 4 | 订阅 `onPlayWhenReadyChanged` / `onPlaybackSuppressionReasonChanged`，进度条上方的状态条给原因 +「继续播放」 | `MediaStatusBanner` |
| 5 | 缓冲中显示「缓冲中…（已缓冲 N%）」，卡死（8s 无进展）显示可恢复提示 | `MediaPlaybackPolicy.bufferPercent` / `isStalled` |
| 6 | 后台自动暂停 → 回前台续播；用户手动暂停不抢 | `pausedByBackground` |
| 7 | 拖动续播判定改 `playWhenReady` | `MediaScreen.onSeekStart/onSeekEnd` |
| 8 | `PlayerView` 缓冲指示改 `SHOW_BUFFERING_ALWAYS`（原 `WHEN_PLAYING` 恰好排除卡住的场景） | `MediaScreen` AndroidView factory |
| 9 | `AndroidView.update` 里 `view.player` 只挂一次（每 500ms 重组都会跑） | `playerAttached` |

## §3 验证

- `./gradlew :app:compileDebugKotlin --offline` → 干净。
- `./gradlew :app:testDebugUnitTest --offline` → **696 例全绿**（全仓），其中新增
  `MediaPlaybackPolicyTest` 23 例（四态播放键 / 抑制原因全覆盖含未知值 / 刷新开关 /
  卡死阈值边界 / 缓冲百分比边界）。
- `./gradlew :app:lintDebug --offline` → **Error 0**（顺带修掉 v2.1.3 留下的
  `StateFlowValueCalledInComposition`：`MediaScreen` 在组合期读 `settings.value`，
  这条读不会建立订阅，改订阅整个 `StateFlow`）。
- 真机复核清单（沙箱无设备）：
  1. 播放中切到别的 App 再回来 → 自动续播；手动暂停后切走再回来 → 保持暂停。
  2. 打开一首网络视频（SMB/WebDAV），起播缓冲期间拖进度条 → 松手应继续播。
  3. 播放中让另一个 App 抢音频焦点（例如微信语音）→ 顶部出现"音频被其他应用占用" + 「继续播放」。
  4. 断网播放已缓冲片段 → 8 秒无进展后出现"画面长时间没有进展（已缓冲 N%）"。
