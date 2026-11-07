# 浮层统一 Claude 视觉 · 变更审计（2026-10-31）

范围：v2.1.8「菜单 / 动作面板 / 路径面板」+ v2.1.9「配色收回主题色」的合并审计。

> v2.1.8 首版用了固定暖纸 `#FAF9F5` + 陶土橙 `#C96442`，实机与冷白列表 / 深色顶栏 / 蓝色进度条割裂明显；
> v2.1.9 保留 Claude 的几何与排版，颜色全部改回 `MaterialTheme.colorScheme`。下表为 v2.1.9 最终口径。

## 设计口径

只改**浮层**，不动文件列表的 MT 主题。**颜色全部从应用主题派生**，避免和白色列表 / 蓝色进度条 / 深色顶栏割裂：

| 项 | 浅色 | 深色 |
|---|---|---|
| 卡片底 | `colorScheme.surface`（白） | `colorScheme.surfaceVariant`（#262626 浮起层） |
| 主文字 | `onSurface` | `onSurface` |
| 次文字 / 图标 | `onSurfaceVariant` | `onSurfaceVariant` |
| 细边 | `outlineVariant` | `outlineVariant` |
| 分隔线 | `outlineVariant` 65% | `outlineVariant` 65% |
| 选中底 | `primary` 8% | `primary` 16% |
| 强调（勾 / 点 / 图标块） | `primary` | `primary` |

- 保留 Claude 的**形状与排版**：16dp（下拉）/ 16dp（路径面板）/ 20dp（动作面板）圆角、
  1px 细边、8–12dp 柔和阴影、40dp 行、18dp 淡图标、14sp 常规字、10.5sp 分组标题。
- 不再使用固定的暖纸 `#FAF9F5` / 陶土橙 `#C96442` —— 那套色和本应用的冷白列表、蓝色强调冲突，
  实机割裂感明显（2026-10-31 反馈）。

## 组件

- `core/ui/ClaudeMenu.kt`
  - `ClaudePalette` / `LocalClaudePalette`：颜色从 `MaterialTheme.colorScheme` 派生（surface / onSurface /
    onSurfaceVariant / outlineVariant / primary），浅深色各一套映射，不再写死暖纸色。
  - `ClaudeMenuTheme`：只提供浮层色板，不再覆盖 `MaterialTheme`（菜单行已全部显式用色板）。
  - `ClaudeDropdownMenu`：16dp 圆角、主题 surface、细边、8dp 阴影、6dp 内容内边距、宽度上限 360dp。
  - `ClaudeMenuItem`：紧凑菜单行（40dp / 18dp 图标 / 选中打勾 / 可选缩进）；`MtMenuRow` 直接委托它。
  - `ClaudeMenuSection` / `ClaudeMenuDivider`。
- `BrowserPathBar`：路径菜单从 `DropdownMenu` 改为顶部居中 `Dialog` 面板（16dp 圆角、路径标题、层级/操作分组、关闭按钮；
  轻遮罩 0.18、横屏/小屏自动收缩高度、宽度上限 360dp）。
- `DialogsActions.MtActionSheet`：长按两列动作面板换同一套卡片/主题色/18dp 图标。

## 覆盖

- **18 处 `DropdownMenu` 调用（含子菜单）全部改为 `ClaudeDropdownMenu`**：⋮、长按动作、排序、压缩格式/级别、协议选择、搜索字段、
  编辑器菜单/查找选项/历史、Diff、HTTP 方法、媒体倍速、首页顶部菜单、打开方式、最近输入等。
- **36 个 app 层 + 5 个 core/ui 原生 `DropdownMenuItem` 全部换成 `ClaudeMenuItem`**：
  统一 40dp 行高、18dp 暖灰图标、14sp 常规字、选中行暖灰底 + 陶土橙勾；不再混排 48dp 的 M3 菜单行。
- 仓库里除 `ClaudeMenu.kt` 内部的 `DropdownMenu` 包装外，已无原生 `DropdownMenu` / `DropdownMenuItem`。

## 边界

- 44 处 `AlertDialog`（表单 / 确认框 / 属性框）**本轮未动**，仍是 M3 默认外观；本轮只统一“菜单 / 弹出面板”这一类。
- 路径面板使用系统 Dialog dim，但通过 `DialogWindowProvider` 调至 **0.18**（不叠加自绘 scrim，也不把背景压黑）。

## 验证

- `:app:compileDebugKotlin` 干净（仅 `LocalClipboardManager` 既有废弃告警）。
- 全量 **666** 例单测通过（纯呈现变更）。
