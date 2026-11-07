# 配色主题 · 变更审计（2026-10-31）

范围：v2.2.0「五套配色主题」。参考 `/workspace/cfbrowser` 的纸墨配色（墨笺 / 黛青 / 松烟 / 赭石）。

## 主题

| id | 名称 | 灵感 | 浅色强调 | 深色强调 | 顶栏（浅 / 深） |
|---|---|---|---|---|---|
| `mt` | MT 蓝 | 原默认 | `#1976D2` | `#42A5F5` | `#151515` / `#303030` |
| `mojian` | 墨笺 | cfbrowser 朱砂 | `#B23A26` | `#E3735A` | `#1D1B18` / `#181D25` |
| `daiqing` | 黛青 | cfbrowser 黛青 | `#2C5A78` | `#84B2D3` | `#1A2733` / `#1A2733` |
| `songyan` | 松烟 | cfbrowser 松烟 | `#3B6A4E` | `#87BE9C` | `#1A241E` / `#1A241E` |
| `zheshi` | 赭石 | cfbrowser 赭石 | `#875B22` | `#DCAA66` | `#241C14` / `#241C14` |

每套给出完整明暗 `ColorScheme`：primary / primaryContainer、background / surface / onSurface、
surfaceVariant、outline / outlineVariant、五个 `surfaceContainer` 层级、fixed 系列、error / errorContainer。
顶栏始终深色，底色随主题；分割线由主题 `outlineVariant` 提供（MT 保持原灰）。

## 接线

- `core/ui/ThemePresets.kt`：`ThemePreset` 枚举 + `SchemeSpec.toScheme`。
- `core/ui/Theme.kt`：`PanelTheme(presetId)` 选择预设；新增 `LocalTopBarBackground` / `LocalDividerColor`；
  动态取色优先于预设（顶栏回落 MT 深色）。
- `MainActivity` 传 `settings.themePreset`；`PrefsStore` 存 `theme_preset`（默认 `mt`，未知回落 MT）。
- `BrowserTopBar` / `SideDrawer` 读 `LocalTopBarBackground`；`MtDividerColor` 读 `LocalDividerColor`。
- `SettingsScreen` →「配色主题」：三枚色卡（surface / primary / topBar）+ 名称说明 + 单选；
  选中预设会自动关闭动态取色。

## 审计修复

1. **深色 `surfaceDim` / `surfaceBright` 方向反了**：原实现 `surfaceDim = container`、`surfaceBright = containerLowest`，
   深色下 surfaceBright 反而更暗。改为按明暗分别取（深色 dim=containerLowest / bright=containerHighest）。
2. **`errorContainer` 被中性化**：原实现拿 `surfaceVariant` 顶替，错误态会变成灰。改为标准错误容器
   （浅 `#F9DEDC/#410E0B`，深 `#8C1D18/#F9DEDC`）。
3. **fixed 系列漏 M3 默认紫灰**：`primaryFixed*` / `secondaryFixed*` / `tertiaryFixed*` 显式收敛到主题色。
4. **MT 默认不变**：`MT` 的 primary / surface / onSurface / outline / 顶栏 / 分割线逐项保留原值；
   `ThemePreset.MT` 是未知 id 的回落。
5. **动态取色优先级**：动态开启时使用系统壁纸色，顶栏回落 MT；设置里选择预设会关闭动态取色，避免两套色打架。

## 验证

- `ThemePresetTest` 7 例：未知回落、id/名称唯一、MT 强调色、明暗与顶栏深色、surfaceDim/Bright 方向、
  errorContainer 语义、MT 分割线保持原灰。
- 全量 **673** 例单测通过；`:app:compileDebugKotlin` 干净。

## 边界

- 墨笺只取 cfbrowser 的**配色**，没有搬宋体标题 / 纸纹 / 透明背景；PanelFM 仍是 MT 列表结构。
- 44 处表单类 `AlertDialog` 仍是 M3 外观；主题换色会跟随 `MaterialTheme`，但形状没有 Claude 化。
