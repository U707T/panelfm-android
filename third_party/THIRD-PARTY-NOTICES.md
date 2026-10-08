# 第三方组件与许可（PanelFM）

> 本文件记录仓库内**非自研代码 / 资源**的来源与许可证。发布的 APK 里同样包含这些组件。
> 最后更新：v2.0.2（RAR 解压：junrar）。

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

## 3. Office 文档预览（`app/src/main/assets/office/**`）

「文档预览」页（`ui/preview/OfficeScreen.kt`）里的 WebView 渲染栈，全部为宽松许可（无 copyleft）：

| 文件 | 组件 | 版本 | 许可证 | 体积 |
|---|---|---|---|---|
| `vendor/jszip.min.js` | [JSZip](https://github.com/Stuk/jszip) | 3.10.2 | MIT **或** GPL-3.0（本仓库按 **MIT** 使用） | 96 KB |
| `vendor/docx-preview.min.js` | [docx-preview](https://github.com/VolodymyrBaydalka/docxjs) | 0.4.1 | Apache-2.0 | 76 KB |
| `vendor/xlsx.full.min.js` | [SheetJS CE](https://git.sheetjs.com/SheetJS/sheetjs) | 0.18.5（npm 最后一个 CE 版） | Apache-2.0 | 864 KB |
| `vendor/pptx-renderer.es.js` | [@aiden0z/pptx-renderer](https://github.com/aiden0z/pptx-renderer)（浏览器版，自带 JSZip + ECharts） | 1.3.0 | Apache-2.0 | 1.8 MB |
| `index.html` / `viewer.js` / `viewer.css` | PanelFM 自写（粘合与样式） | — | 本仓库 | ~7 KB |

许可证全文见本目录（`LICENSE-*.txt` / `LICENSE-jszip-MIT.markdown`）。

**更新步骤**：从 npm 取对应 tarball，替换 `dist/` 里的 min 产物即可；
替换后按 `docs/OFFICE-PREVIEW.md` 的检查清单做一次真机抽验，并同步更新上表版本号。

> 说明：曾评估 `pptx-preview`（体积接近），但其授权条款限定「源码不开放、不得改源码转自有项目」，
> 不符合「开源依赖」的要求，故改用 Apache-2.0 的 `@aiden0z/pptx-renderer`。

## 4. junrar —— RAR 解压（核心库 `core/vfs-archive`）

| 项 | 值 |
|---|---|
| 来源 | https://github.com/junrar/junrar |
| 版本 | `com.github.junrar:junrar:8.1.1`（Maven Central，纯 Java、无原生依赖） |
| 许可证 | **UnRAR License**（宽松但有一条限制：**不得**用它开发 RAR（WinRAR）兼容的压缩器） |
| 使用范围 | `ArchiveVfs`（`core/vfs-archive`）：`.rar` 的列目录 / 浏览 / 解压（RAR4 / RAR5 / RAR7、带口令、分卷），**只读** |
| 传递依赖 | `org.slf4j:slf4j-api`（项目已有 2.0.20） |

**与许可一致的用法**：PanelFM 只把 junrar 用于**读取 / 解压** `.rar`；**不提供也不计划提供
.rar 的创建**（压缩入口保持 zip / 7z / tar 系）。这也与格式现状一致 —— 没有任何开源实现能生成 .rar。

