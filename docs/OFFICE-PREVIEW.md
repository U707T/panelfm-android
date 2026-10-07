# Office 文档预览（WebView + 前端渲染库）· 架构与维护说明

> 版本：v1.8.0 起。**只读预览**，不做编辑、不联网、不写回原文件。

## 1. 能看什么 / 不能看什么

| 后缀 | 内置预览 | 引擎 |
|---|---|---|
| `.docx` | ✅ | docx-preview（分页、表格、图片、基础版式） |
| `.xlsx` / `.xls` | ✅ | SheetJS CE（多工作表用顶部标签切换） |
| `.pptx` | ✅ | @aiden0z/pptx-renderer（文字/形状/图片/表格/图表/SmartArt） |
| `.doc` / `.ppt` | ❌ 给说明页 | 旧二进制格式，前端生态没有渲染器（引导「打开方式…」） |
| 带密码的文档 | ❌ | 解析失败 → 页面内报错 |

其他边界：单文件 > **16 MB** 不进预览（给提示）；`.xls` 里极老的加密/宏特性可能失败；
复杂排版（域代码、文本框流、精确分栏）会有走样 —— 保真度上限由上游库决定。

## 2. 组件与职责

| 文件 | 职责 |
|---|---|
| `core/common/OfficeFormats.kt` | 支持矩阵：后缀 → 渲染器 kind（纯函数，有单测） |
| `ui/preview/OfficeScreen.kt` | 只读预览页：读字节（带上限）、WebView 装配、请求拦截、说明页 |
| `assets/office/index.html` `viewer.js` `viewer.css` | 页面与粘合层（调三个渲染库 + 错误兜底） |
| `assets/office/vendor/*` | 三个渲染库的发行产物（版本与许可见 `third_party/THIRD-PARTY-NOTICES.md`） |

## 3. 关键设计点（改代码前先看）

1. **页面注入 + 全拦截，不联网**：`loadDataWithBaseURL("https://office.panelfm/office/", html, ...)`。
   `baseUrl` 决定页面的 origin，页面里的相对路径、动态 `import()`、`fetch('/doc/current')`
   都由 `WebViewClient.shouldInterceptRequest` 从 assets / 内存提供；
   任何其它 host 一律 404 —— 预览页没有任何出网路径。
2. **文件字节走内存**：`readCapped()` 读 ≤16 MB 到 `ByteArray`，每次请求用新的
   `ByteArrayInputStream`（拦截可能被调用多次）。大文件在读之前就被拦下给提示。
3. **只读 & 最小攻击面**：不开 DOM storage、不注册 JS 桥、`allowFileAccess=false`、
   `allowContentAccess=false`、`shouldOverrideUrlLoading` 全部拦（页面里没有可跳转的链接）。
4. **旧的 .doc / .ppt 不进 WebView**：`OfficeFormats.viewerKindOf()` 返回空串时直接渲染说明页 ——
   避免把二进制垃圾喂给解析器再报一堆看不懂的错。
5. **`shouldInterceptRequest` 里的路径要防穿越**（`..` / 前导 `/` 直接 404）。

## 4. 怎么升级渲染库

1. 从 npm 取 tarball（`docx-preview`、`xlsx`、`@aiden0z/pptx-renderer`、`jszip`），
   把 `dist/` 里的产物拷进 `assets/office/vendor/`（保持文件名，`viewer.js` 里写死了路径）；
2. `pptx-renderer.es.js` 用的是 **浏览器 ESM 版**（自带 JSZip + ECharts），别换成 `.es.js`/`.cjs` 主入口；
3. 更新 `third_party/THIRD-PARTY-NOTICES.md` 的版本表；
4. 真机抽验（无单测可覆盖 WebView 渲染）：
   - docx：带表格 + 图片的文档，滚动、缩放（双指）正常；
   - xlsx：多工作表切换、横向滚动、合并单元格；
   - pptx：翻页（list 滚动 / slide 模式）、含图表的页不崩；
   - `.doc` / `.ppt`：出现说明页而不是报错；
   - 断网状态下预览依旧可用（确认没有出网请求）。
