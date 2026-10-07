# 第三方组件与许可（PanelFM）

> 本文件记录仓库内**非自研代码 / 资源**的来源与许可证。发布的 APK 里同样包含这些组件。
> 最后更新：v1.6.0（文本编辑器改用 sora-editor 引擎）。

## 1. sora-editor —— 文本编辑器引擎

| 项 | 值 |
|---|---|
| 来源 | https://github.com/Rosemoe/sora-editor |
| 版本 | `io.github.rosemoe:editor:0.24.6`、`io.github.rosemoe:language-textmate:0.24.6`（Maven Central） |
| 许可证 | **LGPL-2.1**（全文见 `third_party/sora-editor/LICENSE-LGPL-2.1.txt`） |
| 使用范围 | 编辑器页面的编辑区：文本渲染 / 输入法 / 滚动 / 查找替换 / 语法着色（`EditorScreen.kt`、`EditorLanguages.kt`） |
| 传递依赖 | `gson`、`jcodings`、`joni`、`snakeyaml-engine`、`jdt.annotation`（随 `language-textmate` 引入，均为其上游许可证） |

**LGPL-2.1 合规说明**：PanelFM 的完整源码公开在
https://github.com/U707T/panelfm-android —— 任何人都可以拿到本仓库源码后，
用自行修改的 sora-editor 版本重新构建 APK（依赖坐标见上表，未做二次封装遮蔽）。
许可证全文随仓库与本目录一并提供。

## 2. TextMate 语法与语言配置（`app/src/main/assets/textmate/**`）

编辑器高亮使用的语法文件与语言配置，全部来自开源编辑器生态（MIT 类许可）：

| 文件 | 来源 |
|---|---|
| `kotlin/` `java/` `javascript/` `python/` `xml/` `html/` `markdown/` | 取自 sora-editor 示例工程 `app/src/main/assets/textmate/`（其上游为 VS Code 生态语法，MIT） |
| `json/` `css/` `typescript/` `shellscript/` | 取自 https://github.com/microsoft/vscode （`extensions/<语言>`，MIT） |
| `yaml/` | 取自 https://github.com/redhat-developer/vscode-yaml （MIT） |
| `darcula.json` `ayu-dark.json` `quietlight.json` `solarized_dark.json`（主题） | 取自 sora-editor 示例工程（对应上游 VS Code 主题：Darcula / Ayu / Quiet Light / Solarized，各自 MIT / Apache-2.0 许可） |

> 说明：这些语法文件只影响编辑器着色；删除它们不会影响其它功能（会退化为纯文本）。
> 若上游许可证有变动，请同步更新本表。
