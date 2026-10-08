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

1. **同源加载 + 全拦截，不联网**：主页面直接 `loadUrl("https://office.panelfm/office/index.html?kind=…")`，
   所有资源（页面 / JS / CSS / 文件字节）都由 `WebViewClient.shouldInterceptRequest`
   从 assets / 内存提供；其它 host 一律 404 —— 预览页没有任何出网路径。
   ⚠️ **不要改回 `loadDataWithBaseURL`**：它的主文档是 `data:` URL，一旦拦截器对它回了非 2xx，
   WebView 会把整页升级成 `net::ERR_HTTP_RESPONSE_CODE_FAILURE`（v1.8.0 的线上故障）。
2. **文件字节走内存**：`readCapped()` 读 ≤16 MB 到 `ByteArray`，每次请求用新的
   `ByteArrayInputStream`（拦截可能被调用多次）。大文件在读之前就被拦下给提示。
3. **只读 & 最小攻击面**：不开 DOM storage、不注册 JS 桥、`allowFileAccess=false`、
   `allowContentAccess=false`、`shouldOverrideUrlLoading` 全部拦（页面里没有可跳转的链接）。
4. **旧的 .doc / .ppt 不进 WebView**：`OfficeFormats.viewerKindOf()` 返回空串时直接渲染说明页 ——
   避免把二进制垃圾喂给解析器再报一堆看不懂的错。
5. **`shouldInterceptRequest` 的三条铁律**：
   - **非 http(s) 请求一律返回 `null`**（`data:` / `blob:` / `about:` 交回 WebView），
     绝不能回错误码 —— 主文档被拦成 404 会整页打不开（见第 1 条）；
   - 资产路径要归一化：去掉空段与 `.`、遇到 `..` 拒绝（`AssetManager` 不认 `./` 这类路径）；
   - `/office/`、`/office/index.html` 都映射到 `index.html`（页面地址带 query，按 path 匹配）。
6. **页面里引用资源用绝对路径**（`/office/vendor/xxx.js`），避免 `./` 段带来的路径歧义。
7. **拦截响应必须禁缓存**（v2.0.3 起）：全部 `WebResourceResponse` 带
   `Cache-Control: no-store`，预览页 URL 追加 `&v=<versionName>` —— 页面 / 脚本 URL 恒定，
   不设缓存头时 WebView 可能一直喂旧版 `viewer.js`（表现：升级后行为完全没变、新版逻辑没执行）。
8. **预览诊断入口**（v2.0.3 起）：预览页右上角 ⓘ = 应用 / WebView 版本 + 系统 UA + 渲染模式 +
   页面控制台全文，「复制诊断信息」可一键回传；`?mode=text`（诊断窗内「纯文本预览」）跳过
   排版渲染直接抽文字，任何引擎都能读 —— 现场排查 docx 类问题先看这三样。
9. **docx 渲染是三级链路**（v2.0.2 起）：标准排版 → **兼容排版**（去掉 section 的
   `column-flex` / `overflow:hidden` —— 部分 WebView 上「column flex + min-height + overflow:hidden」
   会把页面压扁到几十像素、内容被裁光，实机「毕业设计 docx 整页空白」即此形态）→ **纯文本兜底**。
   每一级都先在**离屏渲染台**（`#docx-stage`，参与布局但不显示）里跑完体检
   （页面高度 / 可见文字几何，`docxLooksUsable`），不可用才下探；单级超时 8s（`DOCX_RENDER_TIMEOUT_MS`），
   超时先走下一级、迟到的可用结果再换进可见区域。链路与体检都在 `viewer.js`，
   日志前缀 `[OfficePreview]`（Kotlin 侧转发到 logcat），再遇空白先看 logcat。
10. **视口体检 / 自修复**（v2.0.4 起）：渲染完成后 `checkAndRepairViewport()` 打一行
   `DIAG[…]`（innerWidth、visualViewport 含 scale、`#content` 高度、命中测试）。
   实机第三轮故障形态：**渲染完全正常但 `#content` 只剩 ~44px 高**（概览缩放的早期测量把
   绝对定位全屏容器算坏），页面被整段裁掉 —— 此时先**重写 meta viewport** 请求引擎重新应用，
   仍异常则切 `html.body-scroll`（`#content` 回归普通流、页面本体滚动）。不触发条件很保守
   （只认「容器高度 < 200px」，不受用户双指缩放影响）。

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
