# 浮层统一 Claude 视觉 · 变更审计（2026-10-31）

范围：v2.1.8「菜单 / 动作面板 / 路径面板」的视觉重构。

## 设计口径

只改**浮层**，不动文件列表的 MT 主题：

| 项 | 浅色 | 深色 |
|---|---|---|
| 卡片底 | `#FAF9F5` 暖纸 | `#262624` 深暖灰 |
| 主文字 | `#1F1E1D` | `#F3F2EC` |
| 次文字 | `#73726C` | `#A6A49B` |
| 细边 | `#E6E3D9` | `#3D3C38` |
| 选中底 | `#F0EEE6` | `#302F2C` |
| 强调（勾 / 点 / 图标块） | `#C96442` | `#D97757` |

- 卡片圆角 16dp（下拉）/ 20dp（路径面板、动作面板），1px 细边，12–18dp 柔和阴影。
- 行高 ≥40dp，10dp 圆角按压态；图标 18dp、暖灰；正文 14sp 常规，选中行才用强调色。
- 分组标题 10.5sp、微字距、暖灰。

## 组件

- `core/ui/ClaudeMenu.kt`
  - `ClaudePalette` / `ClaudeLight` / `ClaudeDark` / `LocalClaudePalette`
  - `ClaudeMenuTheme`：作用域内替换 `MaterialTheme` 的 surface / onSurface / primary，菜单内文字与控件不会回到冷灰。
  - `ClaudeDropdownMenu`：16dp 圆角、暖纸底、细边、阴影、6dp 内容内边距。
  - `ClaudeMenuItem`：紧凑菜单行（40dp / 18dp 图标 / 选中打勾 / 可选缩进）；`MtMenuRow` 直接委托它。
  - `ClaudeMenuSection` / `ClaudeMenuDivider`。
- `BrowserPathBar`：路径菜单从 `DropdownMenu` 改为顶部居中 `Dialog` 面板（20dp 圆角、路径标题、层级/操作分组、关闭按钮；
  轻遮罩 0.18、横屏/小屏自动收缩高度）。
- `DialogsActions.MtActionSheet`：长按两列动作面板换同一套卡片/配色/18dp 图标。

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
