# 变更审计（2026-11-20 · v2.5.0 设置页重构 + Linear 主题）

> 范围：设置页重写、Linear 预设、顶栏前景/状态栏适配、单测与文档。
> 方法：逐行读 diff；对旧/新 SettingsScreen 做 `prefs.set*` 集合差；沙箱
> `testDebugUnitTest`（821 例）+ `assembleDebug` + `git diff --check`。
> 结论：**4 条修复项 + 1 条已知限制**，修复项已在发版前改掉；无未决阻断。

## 1. 修复项

### 1.1 Switch 行出现两个可点语义节点

- 位置：`SettingsScreen.kt` `SettingsSwitchRow`
- 原实现：整行 `clickable` + `Switch(onCheckedChange = ...)`；整行和开关各接一次点击，
  TalkBack 会读成两个开关，快速连点还可能双触发。
- 修法：整行改 `toggleable(value, role = Switch, onValueChange)`，`Switch(onCheckedChange = null)`
  只负责显示状态——一个语义节点、一个点击入口。

### 1.2 底部 sheet 选中后「啪」地消失

- 位置：`ChoiceSheet` / `PaletteSheet`
- 原实现：`onSelect` 直接把父层 `sheet` 置空，sheet 没播收起动画。
- 修法：选中后先 `sheetState.hide()`，动画结束再 `onSelect`；`onDismissRequest`（遮罩/返回）保持原样。

### 1.3 值列 190dp 在 320dp 屏上挤压标签

- 位置：`SettingsRow`
- 原实现：`value` 的 `widthIn(max = 190.dp)`；长 UA + 长标签（「全局 User-Agent」）在窄屏会把标签压到省略。
- 修法：上限收到 **160dp**；UA 仍单行省略，完整值在编辑对话框里看。

### 1.4 顶栏「始终深色」的过期注释

- 位置：`Theme.kt` / `ThemePresets.kt`
- 现状：Linear 浅色是白底顶栏，注释仍写「始终深色」，会误导后续改动。
- 修法：注释改成「底色随预设；除 Linear 浅色外为深色」，与 `topBarContent()` 的亮度分支一致。

## 2. 行为兼容性

- **22 个设置项一个不少**：对旧/新文件做 `prefs.set[A-Za-z]+` 集合差，`missing in new = []`；
  `browser.setBrowseMode` 仍在。
- 设置项只换布局：多选从横排 `TextButton` 改为底部 sheet；值放行尾；开关仍是原来的 DataStore key。
- `listDisplayMode` 保留（「列表副标题」→ 时间/权限），没有借重构删功能。
- 主题默认仍是 MT；Linear 只在用户主动选择后生效。选择 Linear 会顺手关掉动态取色（与原「选预设自动关动态取色」一致）。

## 3. 验证

- `:app:compileDebugKotlin --offline`：通过。
- `testDebugUnitTest --offline`：**821 例 / 0 失败**（120 个 suite）。
  `ThemePresetTest` 7 条，含新增「顶栏正文 ≥ 4.5:1、次文 ≥ 3:1」的 WCAG 对比度断言。
- `assembleDebug --offline`：通过，三 ABI 各 ~30 MB。
- `git diff --check`：通过；全仓无 `ghp_` / `github_pat_` / 明文口令。
- 顶栏前景：主界面 ☰ / 路径 / tab / ＋ / ⋮ 与抽屉头全部改走 `LocalTopBarForeground`，
  `grep MtSpec.TopBarText` 在 app 代码中已无消费方（只剩 core 的默认值）。

## 4. 已知限制（需真机）

- 沙箱无模拟器/Robolectric：Linear 白顶栏在真实屏幕上的观感、状态栏图标切换、
  `ModalBottomSheet` 收起动画只能真机验收。
- `SettingsScreen` 仍 `collectAsState()` 整个 browser state（只为拿 `browseMode`）——
  与旧实现一致，不是本次引入；设置页打开时若有目录加载会多几次重组，暂不动。
- 320dp 窄屏下的标签省略需要真机/预览确认（已把值列上限从 190 收到 160）。

## 5. 范围外

- 双列/抽屉/首页的布局重构仍是 `docs/UI-CLEAN.md` 的提案，本次**没有**动布局；
  主界面/抽屉只改了顶栏前景色与状态栏图标判定。