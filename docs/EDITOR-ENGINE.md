# 文本编辑器（sora-editor）· 架构与维护说明

> 版本：v1.6.0 起换用 sora-editor（此前是自研 `BasicTextField` 编辑器，已在 v1.6.0 删除，
> 历史上「大文件点一下卡一下」的根因就是它：整篇文本参与 Compose 排版 +
> 每次按键整篇重跑词法分析）。
>
> **v2.0.15 起菜单与功能对齐 NP管理器 编辑器**（`edit_menu.xml` / `layout_edit_search.xml` /
> `activity_edit.xml` / `fe_layout_*.xml`，参见 `/workspace/np-analysis`），
> 并顺势把菜单 / 编辑器状态从 Composable 里抽成可测模型。对齐清单见 §2。
>
> **v2.0.16 起取消「大文件只读分段浏览」**：文本文件一律整篇进编辑器（可编辑），
> 大文件靠「自动流畅模式 / 打开前确认 / 打开上限」兜底（见 §3.6）。

## 1. 组件与职责

| 文件 | 职责 |
|---|---|
| `ui/editor/EditorScreen.kt` | 页面壳（装配）：状态 / 打开文件（档位判定）/ 菜单动作分发 / 编辑器视图同步 / 对话框编排 |
| `ui/editor/EditorFileIo.kt` | 文件读写基础设施：整篇读取 / 保存（编码闭环 + .bak 备份 + 结果结构）/ 大文件**打开档位**（`editorOpenPlan` 纯函数）/ 「外部改动」判定；编码表归 `TextEncodings` |
| `ui/editor/EditorFindController.kt` | 查找 / 替换控制器：查找条状态 + sora `EditorSearcher` 交互（延帧读结果 / lastQuery / suppress） |
| `ui/editor/EditorChrome.kt` | 顶栏（撤销 / 重做 / 查找 / 保存 / ⋮） |
| `ui/editor/EditorMenuModel.kt` | **菜单纯模型**：动作枚举 / 分组 / 条目状态（启用、勾选、标签）→ 供 `EditorMenu` 渲染，规则全部可单测 |
| `ui/editor/EditorMenu.kt` | ⋮ 菜单渲染（NP 分组：文件 → 编辑 → 查找 → 跳转 → 视图 → 编码 ▶ → 设置 ▶ → 代码整理 → 退出；二级菜单在同一个浮层内换页） |
| `ui/editor/EditorCharsets.kt` | 「文件编码」两个对话框的编码表（与 `TextEncodings` 一一对应，测试锁一致） |
| `ui/editor/EditorDialogs.kt` | 对话框：语法 / 编码 / 配色 / 字号（共用一个一行式选项对话框）+ 转到指定行 / 未保存 / 通用确认 |
| `ui/editor/EditorSearchBar.kt` | 底部查找条（查找 / 替换两行 + 上个 / 下个 / 替换 / 全部 / ⋮），焦点可落在「查找」或「替换」行 |
| `ui/editor/EditorSymbolBar.kt` | 符号输入栏（NP `menu_symbol_input`）：底部一条可横滑的小按钮，`⇥` 走编辑器缩进 |
| `ui/editor/EditorSymbols.kt` | 符号表（显示文本 → 插入文本） |
| `ui/editor/EditorValidate.kt` | 「校验语法」：严格 JSON 扫描器（自带行列号）+ XML 良好格式校验（平台解析器） |
| `ui/editor/EditorLanguages.kt` | sora 语言 / 主题注册、后缀 → scope 映射、配色创建（含「配色风格」覆盖）、wordwrap / 注释前缀助手 |
| `core/data/.../PrefsStore.kt` | 编辑器偏好持久化（自动换行三态 / 字号 / 空白符号 / 等宽 / 符号栏 / 参考线 / 放大镜 / 流畅模式 / 配色） |
| `assets/textmate/**` | TextMate 语法（22 种语言）+ 语言配置 + 4 套主题（来源与许可见 `third_party/`） |
| `core/common/.../LineOps.kt` | 行操作纯函数（复制 / 删除 / 缩进 / 注释…），与编辑器引擎解耦，仍有单测 |

引擎库：`io.github.rosemoe:editor` + `io.github.rosemoe:language-textmate`（LGPL-2.1）。

## 2. NP管理器 编辑器对齐清单（v2.0.15 起；大文件档位 v2.0.16 补）

依据 NP 的资源取证：`edit_menu.xml`（菜单）/ `layout_edit_search.xml`（查找条）/
`activity_edit.xml`（主布局 + 符号栏）/ `fe_layout_edit.xml`、`fe_layout_select.xml`（浮动条）/
`np_strings_zh.txt`；实现手段逐项映到 sora 0.24.6 的公开 API。

| NP 项 | 资源 | panelfm 落点 | 状态 |
|---|---|---|---|
| 保存 / 重新加载 | `menu_save_file` / `menu_reload` | ⋮ 文件组；重新加载整篇重读（大文件回到第 0 段） | ✅ |
| 撤销 / 重做（顶栏常驻） | `edit_search_undo` / `edit_search_redo` | 顶栏图标（可用态随编辑器事件同步） | ✅ |
| 搜索（查找 + 替换两行 + 下个 / 上个 / 替换 / 全部 / ⋯） | `layout_edit_search.xml` | 底部查找条（v1.5.1 起已对齐 MT / NP 形态） | ✅ 已具备 |
| 格式化 | `menu_format` | 「格式化代码」（JSON / XML，见 `CodeFormatter`） | ✅ 已具备 |
| 语法 | `menu_language` | 「语法」对话框（按扩展名记忆） | ✅ 已具备 |
| 颜色风格 | `menu_colors_style` | 「配色风格」：跟随应用 / 浅色 QuietLight / 深色 Darcula | ✅ |
| 跳转（指定行 / 行首 / 行尾） | `jump2line` / `jump2header` / `jump2end` | 「跳转」组 5 项：指定行 / 行首 / 行尾 / 文件开头 / 文件结尾 | ✅ |
| 自动换行（可勾选） | `menu_text_wordwrap` | 「视图 → 自动换行」，写进设置（未设置 = 按语言默认） | ✅ |
| 流畅模式 | `menu_fluent_mode` | 「视图 → 流畅模式」：关闭语法高亮 + 当前行 / 括号高亮 | ⚠️ 语义不同（见 §5） |
| 文件编码：指定编码重新加载 / 指定保存的编码 | `load_reset_charset` / `save_reset_charset` | ⋮ 编码 ▶ 两个对话框（编码表 ↔ `TextEncodings` 有测试锁） | ✅ |
| 设置：字体大小 | `font_size` | ⋮ 设置 ▶ 字体大小（8 档，持久化） | ✅ |
| 设置：显示空白符号 | `blank_symbol` | `setNonPrintablePaintingFlags`（行首 / 行内 / 行尾 / 空行） | ✅ |
| 设置：等宽字体 | `font_monospace` | `setTypefaceText(MONOSPACE / DEFAULT)` | ✅ |
| 设置：符号输入 | `symbol_input` | 底部符号输入栏（可横滑；`⇥` = 编辑器缩进） | ✅ |
| 设置：缩进参考线 | `reference_line` | `setHighlightCurrentBlock` + `props.drawSideBlockLine` | ✅ |
| 设置：放大镜 | `magnifier` | sora `Magnifier` 组件开关 | ✅ |
| XML 校验 | `menu_xml_valid_check` | 「代码整理 → 校验语法」（JSON 严格 / XML 良好格式，报错带行列） | ✅ |
| 退出 | `menu_edit_exit` | ⋮ 末项「退出编辑器」（含未保存确认） | ✅ |
| 浮动工具条（选择 / 撤销 / 重做 / 粘贴；全选 / 复制 / 剪切 / 粘贴 / 分享 / 取消） | `fe_layout_edit.xml` / `fe_layout_select.xml` | 未单独实现：选中文本用 sora 自带文本操作浮窗，撤销 / 重做在顶栏 | ⬜ 未做 |
| 导航（方法 / 符号列表） | `edit_showMethods` + `ic_explore` | 未实现（要语法符号提取，属「语言工具链」，超出本项目边界） | ⬜ 不做 |
| 保存前「文件已被修改 / 删除」提示 | `save_inzip_tips`（NP）/ MT 设置项 | 保存前比对元数据，改动过先确认；保存成功后刷新基准 | ✅ |
| 大文件：直接进编辑器 + 流畅模式 | NP / MT 都是「打开即可编辑」，大文件靠流畅模式保流畅 | v2.0.16 起取消只读分段浏览：一律整篇可编辑；> 2 MB 自动开流畅模式、> 16 MB 先确认、> 64 MB 拒绝 | ✅ |

## 3. 关键设计点（改代码前先看）

1. **编辑器实例不进 Compose state**：`AndroidView.factory` 里创建，存进普通数组
   `editorHolder[0]`；Compose 侧只有「待灌入文本」`applyText = 版本号 to 内容`。
   `AndroidView.update` 的读状态会被 `snapshotObserver` 追踪（可重跑），
   所以在 update 里做「文本换入 / 语言 / 配色 / 字号 / 只读 / 视图开关」的同步是安全的；
   **但不要在 update 里写 Compose 状态**（撤销 / 重做可用态由 `ContentChangeEvent` 回调同步）。
2. **灌文本只灌一次**：用 `textVersion` 递增 + `appliedVersion` 比对，
   避免每次重组都 `setText`（会清掉撤销栈 + 重置滚动）。首次装载 / 翻段 / 重新加载都走 `pushText`。
3. **行操作先归一换行符**：`LineOps` 以 `\n` 为行分隔，CRLF 文档会先把 `\r\n` 归一成 `\n`，
   算完再还原，**否则做一次行操作整个文件换行符会被改掉**。
4. **查找/替换走 `EditorSearcher`**：
   - 搜索在库内后台线程跑，结果通过 `PublishSearchResultEvent`（主线程）通知；
   - 事件回调里再 `postInLifecycle` 延后一帧 —— 事件派发时 `currentThread` 尚未置空，
     直接读 `matchedPositionCount` 会拿到 0；
   - `lastQuery` 记录「上一次真正提交的查询」，条件没变时点击「下个」只 `gotoNext()`，不重启搜索；
   - 结果未就绪时状态栏显示「查找中…」，**不能**把 `matchedPositionCount == 0` 当成「找不到文本」。
5. **`replaceAll` 的状态提示**：库的回调结束后正文变化会触发自动重搜，
   用 `suppressSearchStatus` 抑制一次状态刷新，保证「已替换 N 处」不被覆盖。
6. **大文件（v2.0.16 起没有分段浏览）**：文本文件一律**整篇**读进编辑器（可编辑），
   兜底只有三档（纯函数 `editorOpenPlan(size)`，回归 `EditorOpenPlanTest`）：
   - `> 2 MB`：自动开**流畅模式**（关语法高亮 + 当前行 / 括号高亮，状态栏注明，可在 ⋮ → 视图 关掉）；
   - `> 16 MB`：打开前弹确认（说明可能慢 / 占内存，取消则退出本页）；
   - `> 64 MB`：拒绝打开（整篇进内存 + sora 文档模型，再大就有 OOM 风险），给出明确文案。
   另有 `HL_MAX_CHARS`（640k 字符）自动退化为纯文本高亮的老规则，用于超长单行之类的病态输入。
7. **菜单只描述状态，不做事**（v2.0.15）：`editorMenuSections(EditorMenuInput)` 产出
   「分组 + 条目 + 启用 / 勾选 / 标签」，`EditorMenu` 只负责画与点，动作在 `EditorScreen.onMenuAction`
   里分发。只读（分段浏览）下禁用一切会改正文的项；「复制行」只写剪贴板（保持可用）；
   「切换注释」按语言置灰；「格式化」「校验语法」只对 JSON / XML 可点。
8. **保存编码闭环**：写回走 `TextEncodings.encode` —— `UTF-8 (BOM)` / `UTF-16` / `UTF-32` 重建 BOM
   （这些格式只靠 BOM 被识别，丢 BOM 会乱码）；GBK / Big5 / ISO-8859-1 做往返校验，有字符表示不了时
   回退 UTF-8 并在状态栏注明「转存自 …」。读 / 写 / 识别共用同一张编码表，
   `EditorCharsets` 的选项表由 `EditorCharsetsTest` 与它逐项对齐。
9. **「指定编码」两条路**：重新加载 = 按所选编码重解当前文件（可选「自动识别」）；
   指定保存的编码 = 按所选编码写回。会话断开时两条路都会 `resolveSession` 自动重连一次。
10. **保存前查外部改动**：`fileChangedSince(打开时的元数据, 保存前重新 stat)` ——
    大小或修改时间变了（含「文件已不存在」）→ 先弹确认；保存成功后刷新元数据基准，
    避免自己刚写完又被自己拦一次。远端给不出 mtime / size 时不误报（`EditorFileIoChangeTest`）。
11. **编辑器偏好持久化**（`PrefsStore`）：「自动换行」与「流畅模式」都是三态
    （未设置 = 换行按语言默认 / 流畅模式按文件大小自动；用户动过就听用户的），
    其余是开关 / 档位；配色只认 `light` / `dark`，脏数据回落「跟随应用」。
    流畅模式显式选过之后，菜单里会多一行「恢复自动（按文件大小）」回到默认档。
12. **流畅模式是「关高亮」而不是换编辑器**：NP / MT 的流畅模式会切到更轻的编辑器实现
    （IME 能力受限）；本项目只有 sora 一个引擎，所以这里等价于「关语法高亮 + 关当前行 / 括号高亮」，
    状态栏会写明，避免误解。**大文件（> 2 MB）默认自动开**（MT 的「自动开启流畅模式阀值」语义）。

## 4. 怎么加一种语言

1. 把语法文件放 `assets/textmate/<lang>/syntaxes/`（TextMate 的 `*.tmLanguage(.json)`，
   plist 与 JSON 两种格式都支持）；
2. 把语言配置放 `assets/textmate/<lang>/language-configuration.json`
   （**必须是严格 JSON**：VS Code 上游文件常带注释 / 尾逗号，需要去掉）；
3. 在 `assets/textmate/languages.json` 追加一条
   `{ grammar, name, scopeName, languageConfiguration }`（scopeName 必须与语法文件里的一致；
   跨语法引用用 `embeddedLanguages`，如 html → javascript/css）；
4. 在 `EditorLanguages.scopeOf()` 加后缀映射、`LABELS` 加显示名、`ORDER` 加进「语法」菜单顺序；
   若该语言有行注释前缀，补 `commentPrefixOf`；若可格式化 / 可校验，补 `CodeFormatter` / `EditorValidate`；
5. 跑 `EditorLanguagesTest`（映射用例）+ `TextMateAssetsTest`（**资产一致性**：languages.json ↔
   实际文件、scopeName、严格 JSON、菜单与清单完全一致）+ 打开一个该类型文件实机看一眼。

## 5. 已知边界

- 语言包总量约 2.7 MB（22 种）：`loadGrammars` 首次使用时在 **IO 线程**加载一次（桌面 JVM 约 1.7s，
  真机更慢）；单个语言的正则编译发生在**首次分词**时（cpp 这类大语法桌面 JVM 也要 ~0.9s）。
  ⚠️ `ensureInitialized` 必须留在 IO 线程里（`EditorScreen` 的 `LaunchedEffect` 已如此），别挪到主线程；
- 语法 / 语言配置里的正则由 sora 内置的 Oniguruma（Joni）执行：个别「JS 里合法」的写法
  （可变长 look-behind、空字符类 `[]` 等）会编译失败 —— **分词阶段**失败会被库内部记录并跳过该规则，
  但**加载阶段**（语言配置）的失败会连累**整个注册表**（所有语言一起没高亮）。新增 / 升级语言包后
  必须跑 `TextMateGrammarLoadTest`（真加载 + 每种语言分词一行）；上一轮的 `go` 配置 `[]` 就是这样抓到的；
- 语法高亮依赖库的 TextMate 实现；极端病态输入（超长单行等）仍可能慢，
  此时可开「流畅模式」或把 `HL_MAX_CHARS` 调小；
- **大文件没有分段兜底了（v2.0.16 起）**：> 2 MB 自动关高亮、> 16 MB 先确认、> 64 MB 拒绝；
  中间档（几 MB ~ 十几 MB）在低端机上一次装载 / 首次分词可能顿一下（状态栏会写「流畅模式」）。
  真遇到「打不开的大文本」诉求，再按需加回分页——分页实现（行对齐不变式那套）已从代码里删除，
  但思路与测试设计留在这份文档与 git 历史里（v2.0.15 及之前）；
- 编辑器主题从 4 套内置主题里挑（浅色 quietlight / 深色 darcula），
  「跟随应用」时把背景 / 行号栏 / 当前行**覆盖成应用配色**；固定「配色风格」时用主题自带配色
  （强行套应用底色会出现两块拼色）；
- **流畅模式**只关高亮，不换编辑器实现：NP / MT 的流畅模式会让部分输入法能力受限（复制剪切走自带菜单），
  本项目没有第二条编辑器路径，故不承诺同样的「输入法降级」；
- 「切换注释」仍走自研 `LineOps`（`commentPrefixOf`；css 系按扩展名区分：scss / less 为 `//`，
  纯 css 置灰），没有使用库的语言配置里的注释规则；
- 「校验语法」只覆盖 JSON / XML：JSON 用自研严格扫描器（`{a:1}`、尾逗号都会报错），
  XML 只查「格式良好」（不解析 DTD / 外部实体，也不做 schema 校验）；
- **未做**（NP 有、需要时再立项）：导航（方法 / 符号列表）、浮动编辑工具条（`fe_layout_*.xml`）。
