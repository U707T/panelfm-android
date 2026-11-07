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
| 5 | 缓冲中显示「缓冲中…（剩余可播 N%）」，卡死（8s 无进展）显示提示 +「继续播放」 | `MediaPlaybackPolicy.aheadPercent` / `isStalled` |
| 6 | 后台自动暂停 → 回前台续播；用户手动暂停不抢 | `pausedByBackground` |
| 7 | 拖动续播判定改 `playWhenReady` | `MediaScreen.onSeekStart/onSeekEnd` |
| 8 | `PlayerView` 缓冲指示改 `SHOW_BUFFERING_ALWAYS`（原 `WHEN_PLAYING` 恰好排除卡住的场景） | `MediaScreen` AndroidView factory |
| 9 | `AndroidView.update` 里 `view.player` 只挂一次（每 500ms 重组都会跑） | `playerAttached` |

## §3 发布前复审（2026-11-02 第二轮，发现并修掉 2 处）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| A | 🔴 **卡死态「继续播放」会变成"暂停"**：`planToggle` 取反 `playWhenReady`，而卡死时它已是 true → 按钮名与行为对不上 | `MediaPlaybackPolicy.planToggle` 被卡死分支复用 | 新增 `planStallRecovery(position)`：`seekTo(当前位置)` + `play()`，原地重新发起装载（Media3 会丢弃旧 SampleQueue 重读）；卡死时走它，抑制时仍走 `togglePlay` |
| B | 🟡 **「已缓冲 N%」语义错**：`bufferPercent` 算的是 `(buffered - position) / duration`，即"播放头之后还剩多少"，却标成"已缓冲" | `MediaPlaybackPolicy.bufferPercent` + 状态条文案 | 重命名 `aheadPercent`、文案改「剩余可播 N%」，并把语义写进 KDoc（它回答的是"还能往前看多久"） |

评审同时复核过但**判定无需改**的点：

- `LaunchedEffect(product, isPlaying, playWhenReady, playbackState)` 不会自激：三个输入都是回调写入的
  `mutableStateOf`，`positionMs` / `bufferedMs` 不在 key 里，轮询里写它们不触发重启。
- 卡死看门狗每秒一次、仅在 `playWhenReady` 为真时运行，且判到即 `break`；后台不额外唤醒。
- `pausedByBackground` 只在 `ON_STOP` 且确有播放意图时置位，用户手动暂停后切后台再回来不会被"抢着播"。
- `applyPlan` 里 `plan.play=false` 的分支不会碰到 `reseekToMs`（`planStallRecovery` 恒为 `play=true`）。

## §5 第三轮（v2.2.3）：v2.2.2 为什么没修好 + 真因

### v2.2.2 的两个错误

1. **看门狗判据写错**。v2.2.2 用「位置**和**缓冲都没前进」判定卡死。但用户的原话是
   **"可以缓存"** —— 缓冲一直在涨 → 被判成"有进展" → 看门狗从未触发，状态条从未出现。
   也就是说 v2.2.2 新增的全部 UI 提示在这种场景下都是死代码。
2. **把"用户要点按钮"当成了方案**。即使提示出来，`READY + !isPlaying` 的根因也不会自己消失；
   需要的是**自动重试**，不是让用户手动点。

### 真因（代码级）

`Player.isPlaying` 的语义是三者同时成立：`playWhenReady && state==READY && suppressionReason==NONE`。
只要能缓冲、能 seek 出画面却"不播"，就只能是第三项被置位 —— 也就是**播放抑制**。
Media3 的抑制来源里，`UNSUITABLE_AUDIO_OUTPUT` 是默认开启的：

```java
// ExoPlayer.Builder 里 suppressPlaybackOnUnsuitableOutput 默认 true
// 音频路由被 AudioManager 认为"不适合"（部分 ROM / 蓝牙 / USB / 模拟器上
// getAudioDevicesForAttributes 返回空）→ 整个播放被抑制
```

对一个**视频**播放器来说这是错的取舍：没有可用音频输出时应当「静音出画面」，
而不是「因为没声音就整段不播」。这就是「缓冲照涨、seek 照出帧、时钟不走」的唯一成因。

### 本轮修复

| # | 修复 | 位置 |
|---|---|---|
| 1 | `setSuppressPlaybackOnUnsuitableOutput(false)` —— 根因 | `MediaScreen` 播放器构造 |
| 2 | 看门狗改为只看「出没出帧」，不再被缓冲进度骗过 | `shouldAutoRecover` |
| 3 | `READY` 无帧 0.9s → 自动 `pause()`→`play()` 重申请焦点，最多 3 次 | `MediaScreen` 看门狗 |
| 4 | 用尽 3 次才交给用户；「继续播放」= 原地重新装载 | `needsManualRecovery` / `planStallRecovery` |
| 5 | 拔耳机 / 断蓝牙自动暂停（不再静默外放） | `setHandleAudioBecomingNoisy(true)` |
| 6 | 硬解失败回退软解 | `DefaultRenderersFactory.setEnableDecoderFallback(true)` |
| 7 | 卡死文案带 `state=` / `suppression=` / 重试次数 / 剩余可播 | `describeStall` |

### 为什么不直接改「点播放重试」了事

`AUDIOFOCUS_LOSS` 之后 ExoPlayer **不会**自己重新申请焦点（这是刻意的：不抢占其他 App 的音频）。
用户上一次看到的现象里"点播放也没反应"，正是因为 `playWhenReady` 本来就是 true，
`play()` 不产生任何状态变化 —— 必须 `pause()` → `play()` 才是"重新申请"。

### 关于「图标变小」

v2.2.1 把标记从 57.6% 收到 41.3%（xxxhdpi 前景 bbox 166px → 119px）。真机上明显偏小，
本轮把 5 个密度目录 + `gen_launcher_icon.py` 整体回退到 v2.2.0：

```
git checkout 96eb252 -- app/src/main/res/mipmap-* tools/gen_launcher_icon.py
# 校验：xxxhdpi fg bbox = (133,136,299,295)，宽 166px / 432 = 38.4%（与 v2.2.0 一致）
```

### 验证

- 全仓 **711 例单测通过**（`MediaPlaybackPolicyTest` 38 例）；`:app:lintDebug` **Error 0**。
- 仍需真机确认的前置条件：沙箱无 `/dev/kvm`、无 `adb`，抑制状态在模拟器 / 真机上的
  具体取值（`suppression=` 后面的数字）只能实机读到 —— 这正是本轮把状态码打进文案的原因。

### 真机复核清单（三轮汇总）

1. **本轮重点**：找一台之前必现"能缓冲、能拖看画面、不播"的设备，直接播放同一条视频。
   预期：正常出画面（即使音频路由异常也静音出画）。若仍卡，截图状态条上的
   `state=… / suppression=…` 就能定位到具体抑制原因。
2. 播放中切到别的 App 再回来 → 自动续播；手动暂停后切走再回来 → 保持暂停。
3. 起播缓冲期间拖进度条 → 松手应继续播。
4. 让另一个 App 抢音频焦点（如微信语音）→ 约 1 秒内自动重试恢复；
   3 次仍失败才出现"音频被其他应用占用" + 「继续播放」。
5. 断网播放已缓冲片段 → 剩余可播耗尽后出现带状态码的卡死提示。
6. **图标**：确认桌面图标与 v2.2.0 观感一致（不再偏小）。


## §6 第四轮（v2.2.4）：v2.2.3 为什么还没修好 —— 只关了一个抑制源 + 看门狗自我打断

> 用户第四次反馈（v2.2.3 已装）：**「有画面，就是无法播放；可以拖动，就是无法播放。」**
> 本轮方法：把 `isPlaying` 的**字节码级语义**钉死，再逐个排查能让它「READY + 有意图 + 不出帧」
> 的来源，最后用「结构上不可能」收尾，而不是再赌一个猜测。

### §6.1 先把语义钉死（Media3 1.10.1 字节码）

```
// androidx.media3.common.BasePlayer.isPlaying()  ← javap -c 实测
return getPlaybackState() == STATE_READY
    && getPlayWhenReady()
    && getPlaybackSuppressionReason() == PLAYBACK_SUPPRESSION_REASON_NONE;
```

**位置推进只要求 `isPlaying` 为真；seek / 缓冲 / 解码都不要求。**
所以「能缓冲、能拖出画面、就是不播」在代码里等价于：

```
STATE_READY && playWhenReady && suppressionReason != NONE    // ← 症状 A
```

或者

```
playWhenReady == false                                       // ← 症状 B（画面停在第一帧 / seek 帧）
```

### §6.2 🔴 一：v2.2.3 只关了「输出不合适」，没关「音频焦点」

`computePlaybackSuppressionReason(boolean)` 的字节码只有三个返回：

| 值 | 判据（字节码） | 由谁开 |
|---|---|---|
| 4 | `scrubbingModeEnabled` | 未启用 |
| 3 | `suitableOutputChecker != null && !isSelectedOutputSuitableForPlayback()` | `setSuppressPlaybackOnUnsuitableOutput`（v2.2.3 已关） |
| 1 | `playbackInfo.playbackSuppressionReason == 1 && !playWhenReady` | `AudioFocusManager`（**一直是开的**） |

`androidx.media3.common.audio.AudioFocusManager.updateAudioFocus()` 实测：

```
LOSS_TRANSIENT        → PLAYER_COMMAND_WAIT_FOR_CALLBACK → suppressionReason = 1（时钟不走）
LOSS                  → PLAYER_COMMAND_DO_NOT_PLAY       → playWhenReady = false
requestAudioFocus 失败 → PLAYER_COMMAND_DO_NOT_PLAY       → suppressionReason = 1
```

而 `requestAudioFocus()` 只在「`audioFocusState == HAVE_FOCUS`」时提前返回 ——
**收到 LOSS 之后 ExoPlayer 不会自己重试申请**，用户点播放键也没用
（`playWhenReady` 本来就是 true，`play()` 不产生状态变化）。这正是 v2.2.2 那条
「点了播放没反应」的 API 层最后一环。

- 位置：`MediaScreen.kt` 播放器构造（旧：`.apply { setAudioAttributes(audioAttributes, true) }`）
- 修复：`handleAudioFocus = false`，焦点交给新增 `MediaAudioFocus`，且**只映射到音量**：
  GRANTED → 1.0 / DELAYED·LOST·LOST_TRANSIENT → 0（**静音继续播**）/ DUCK → 0.2 /
  DENIED → 1.0（被拒不等于不许出声）。音量映射是纯函数 `volumeForFocus`，6 例回归。
- 效果：`suppressionReason` 从此不可能为 1 或 3 →
  **`isPlaying ≡ (READY && playWhenReady)`，症状 A 在结构上消失。**

### §6.3 🔴 二：v2.2.3 的看门狗每 0.9 秒 `pause()` 一次，把慢起播打死

```kotlin
LaunchedEffect(player, playWhenReady) {      // ← key 里有 playWhenReady
    ...
    if (shouldAutoRecover(...)) {
        player.pause(); player.play()        // ← 改的正是 playWhenReady
    }
}
```

自愈 → `playWhenReady` 变 false → **effect 被取消并重启** → `retries` / `noProgressMs`
全部归零 → 新 effect 里再 `play()` → 再重启…… 上限 `AutoRecoverLimit = 3` 永远到不了。
净效果：**每 0.9 秒把播放器 pause 一次**。4K / HEVC / 网络存储的首帧本来就要 1–3 秒，
被这么打断就永远出不了第一帧 —— 「有画面（第一帧 / seek 帧）、就是不播」。

- 位置：`MediaScreen.kt` 看门狗（旧 `LaunchedEffect(player, playWhenReady)`）
- 修复：effect 只以 `player` 为 key；判据改成**位置有没有前进**；
  自救动作改成 `seekTo(当前位置)`（`planStallRecovery` 语义，不 pause、不改意图）；
  首帧窗口 `StallTimeoutMs = 8s`、已出帧后冻结 `AutoRecoverDelayMs = 1.2s`、
  最多 `AutoRecoverLimit = 2` 次，用尽才弹状态条。判定收进纯函数 `watchdogAction`（7 例回归）。

### §6.4 🔴 三：播放键在「卡住」时取反成 pause（按钮名与动作相反）

`planToggle` 在 `playWhenReady=true && READY && !isPlaying` 时返回 `play = false` ——
用户看到「暂停」图标、按下却只想让画面动，实际得到 `pause()`。

- 修复：`planToggle` 增加 `isPlaying` / `currentPositionMs` 入参，该状态直接返回
  `planStallRecovery(当前位置)`；`showPauseIcon` 同步显示「播放」。2 例回归。

### §6.5 🟡 四：起播时机（后台出声 / 后台申请焦点）

`preflightMedia` 是挂起的。打开视频瞬间按 Home / 熄屏时，装载可能在 `ON_STOP` 之后才完成，
这时 `playWhenReady = true` 会（a）在后台出声，（b）在后台申请焦点（必被拒）。
修复：`armPlayback()` 只在 `lifecycle >= STARTED` 时置位，否则挂起到 `ON_START` 补发。

### §6.6 🟡 五：`playerAttached` 一次性开关 = 视频面黑屏隐患

旧代码用 `var playerAttached = remember { mutableStateOf(false) }` 只挂一次播放器。
`AndroidView` 一旦重建（Surface 重建 / 父容器重建），新 `PlayerView` 永远拿不到播放器，
**整面黑屏而 `player.isPlaying` 仍是 true**。`PlayerView.setPlayer()` 对同一实例是提前返回
（字节码实测 `if_acmpne → return`），所以直接「不同才赋」既正确又无额外开销。

### §6.7 本轮修复清单

| # | 修复 | 位置 |
|---|---|---|
| 1 | `handleAudioFocus = false` + 自管焦点（只改音量） | `MediaScreen` 播放器构造 / `MediaAudioFocus.kt` |
| 2 | 音量映射纯函数（GRANTED/DELAYED/LOST/DUCK/DENIED） | `MediaPlaybackPolicy.volumeForFocus` |
| 3 | 看门狗单实例、只判位置、自救改为原地重装 | `MediaScreen` + `watchdogAction` |
| 4 | 首帧窗口 8s / 冻结窗口 1.2s / 上限 2 次 | `watchdogAction` |
| 5 | 卡住时播放键 = 原地重装（不再取反成 pause） | `planToggle` / `showPauseIcon` |
| 6 | 不可见时挂起起播，`ON_START` 补发 | `armPlayback` / lifecycle 观察者 |
| 7 | `PlayerView` 挂载不再用一次性开关 | `MediaScreen` AndroidView |
| 8 | 状态条带 意图/实况/suppression/focus/重试/剩余可播 + 静音播放说明 | `describeStall` / `describeFocusAudio` |

### §6.8 验证

- `MediaPlaybackPolicyTest` **41 例**通过（新增：看门狗 7 / 焦点音量 6 / 播放键卡住语义 2 / 图标 1）。
  （第五轮自查又补 1 例「播完重播」，最终 42 例，见 §6.10。）
- 全量单测通过；`:app:lintDebug` **Error 0**（72 条既有 warning，无新增）。
- 仍需真机确认：沙箱无 `/dev/kvm`、无 `adb`，本轮结论来自
  **Media3 1.10.1 字节码 + 全链路通读**，不是设备日志。

### §6.9 真机复核清单（第四轮）

1. 直接播之前必现「有画面不播」的那条视频 → 应立刻出画面并推进。
2. 让另一个应用占住音频焦点（音乐 / 语音），再打开视频 →
   **画面照常播，底部提示「正在静音播放」**；对方放手后自动恢复声音。
3. 播放中切走再回来 → 自动续播；手动暂停后切走再回来 → 保持暂停。
4. 4K / HEVC / SMB 上的大文件 → 首帧慢不再被看门狗打断（8 秒内不动作）。
5. 若仍卡：截图状态条上的 `state= / 意图= / 实况= / suppression= / focus=` —— 能直接指认来源。

### §6.10 第五轮自查（发布前复审，补掉 2 处）

| # | 问题 | 位置 | 修法 |
|---|---|---|---|
| A | 🔴 **播完再点播放键要按两下**：`planToggle` 的结束态分支条件写成 `!playWhenReady && ENDED`，而 ExoPlayer 播完**不复位** `playWhenReady`（仍是 true）→ 分支永不命中 → 落到取反分支变 `pause()`。用户看到 ▶、按一下没反应，第二下才重播 | `MediaPlaybackPolicy.planToggle` | 结束态不看 `playWhenReady`，一律 `seekTo(0)` + `play()`；新增 1 例回归（`playWhenReady=true` 时也必须 `restartFromStart`） |
| B | 🟡 **`AudioManager` 为 null 时永久静音**：`request()` 直接 `return`，一个状态回调都不发 → `focusState` 停在 `FocusNone`，而 `volumeForFocus(FocusNone) = 0f` → 画面在走、永远没声音，状态条也不会解释 | `MediaAudioFocus.request()` | 拿不到服务时 `onState(FocusGranted)`，按「有焦点」处理（与「申请被拒也照常出声」的设计一致） |

复审过但**判定不改**的点：`STATE_ENDED` 时仍持有音频焦点。`AUDIOFOCUS_GAIN` 不是排他锁 ——
别的应用申请时系统会把焦点转给它（本页收到 `LOSS` → 静音继续画），播完攥着不会挡住别人。

### §6.11 验证（第五轮后）

- `:app:testDebugUnitTest --offline` → **360 例全绿**（`MediaPlaybackPolicyTest` 42 例）。
- `:app:lintDebug --offline` → **Error 0**（`lint-results-debug.xml` 中 `severity="Error"` 计数为 0）。
- 仍未做：真机 / 模拟器复核（沙箱无 `/dev/kvm`，`adb` 起不来）。
