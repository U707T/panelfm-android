# 文本编辑器引擎（sora-editor）· 架构与维护说明

> 版本：v1.6.0 起。此前是自研 `BasicTextField` 编辑器（已在 v1.6.0 删除，
> 历史上「大文件点一下卡一下」的根因就是它：整篇文本参与 Compose 排版 +
> 每次按键整篇重跑词法分析）。

## 1. 组件与职责

| 文件 | 职责 |
|---|---|
| `ui/editor/EditorScreen.kt` | 页面壳（装配）：状态 / 读取装载 / 行操作入口 / 编辑器视图 / 对话框编排 |
| `ui/editor/EditorFileIo.kt` | 文件读写基础设施：分页读取（相邻页严格相接的行对齐）/ 保存（编码闭环 + .bak 备份）；编码表归 `TextEncodings` |
| `ui/editor/EditorFindController.kt` | 查找 / 替换控制器：查找条状态 + sora `EditorSearcher` 交互（延帧读结果 / lastQuery / suppress） |
| `ui/editor/EditorChrome.kt` | 顶栏（A- / A+ / 查找 / 保存 / ⋮）与分段浏览控制条 |
| `ui/editor/EditorMenu.kt` | ⋮ 菜单（复刻 MT 0x7f0e001b；只读时禁用改正文项）+ 分页小按钮 |
| `ui/editor/EditorDialogs.kt` | 语法 / 转到指定行 / 未保存三个对话框 |
| `ui/editor/EditorSearchBar.kt` | 底部查找条（查找 / 替换两行 + 上个 / 下个 / 替换 / 全部 / ⋮），选项在 ⋮ 里 |
| `ui/editor/EditorLanguages.kt` | sora 语言 / 主题注册表初始化、后缀 → scope 映射、配色创建、wordwrap / 注释前缀助手 |
| `assets/textmate/**` | TextMate 语法（12 种语言）+ 语言配置 + 4 套主题（来源与许可见 `third_party/`） |
| `core/common/.../LineOps.kt` | 行操作纯函数（复制/删除/缩进/注释…），与编辑器引擎解耦，仍有单测 |

引擎库：`io.github.rosemoe:editor` + `io.github.rosemoe:language-textmate`（LGPL-2.1）。

## 2. 关键设计点（改代码前先看）

1. **编辑器实例不进 Compose state**：`AndroidView.factory` 里创建，存进普通数组
   `editorHolder[0]`；Compose 侧只有「待灌入文本」`applyText = 版本号 to 内容`。
   `AndroidView.update` 的读状态会被 `snapshotObserver` 追踪（可重跑），
   所以在 update 里做「文本换入 / 语言 / 主题 / 字号 / 只读」的同步是安全的。
2. **灌文本只灌一次**：用 `textVersion` 递增 + `appliedVersion` 比对，
   避免每次重组都 `setText`（会清掉撤销栈 + 重置滚动）。
3. **行操作先归一换行符**：`LineOps` 以 `\n` 为行分隔，CRLF 文档会先把 `\r\n` 归一成 `\n`，
   算完再还原，**否则做一次行操作整个文件换行符会被改掉**。
4. **查找/替换走 `EditorSearcher`**：
   - 搜索在库内后台线程跑，结果通过 `PublishSearchResultEvent`（主线程）通知；
   - 事件回调里再 `postInLifecycle` 延后一帧 —— 事件派发时 `currentThread` 尚未置空，
     直接读 `matchedPositionCount` 会拿到 0；
   - `lastQuery` 记录「上一次真正提交的查询」，条件没变时点击「下个」只 `gotoNext()`，
     不重启搜索；
   - 结果未就绪时状态栏显示「查找中…」，**不能**把 `matchedPositionCount == 0` 当成
     「找不到文本」。
5. **`replaceAll` 的状态提示**：库的回调结束后正文变化会触发自动重搜，
   用 `suppressSearchStatus` 抑制一次状态刷新，保证「已替换 N 处」不被覆盖。
6. **大文件**：> 2 MB 仍进只读分段浏览（每段 512 KB），只是同样交给 sora 渲染；
   页头 / 页尾各在边界外 64 KB 窗口内对齐**同一行边界**，相邻页严格相接（不重叠、不漏内容，
   回归测试 `EditorFileIoTest`；UTF-16 按 2 字节码元识别换行）；> 640k 字符自动退化为纯文本高亮（`HL_MAX_CHARS`）。
7. **菜单按语言给项**：`EditorMenu` 的「格式化代码」只在 JSON / XML 可点、
   「切换注释」按 `commentPrefixOf(scope, 扩展名)` 置灰（纯 css 无行注释 → 置灰；scss / less 为 `//`）；
   「语法」可手动选择并**按扩展名记忆**（`PrefsStore.editorLangOverrides`，scope 空串 = 纯文本，
   无后缀文件同样可记）；「自动换行」默认值见 `defaultWordwrap()`（Markdown / 纯文本 / 未识别 → 开）。
   **只读（分段浏览）下禁用全部会改正文的项**；「复制行」只写剪贴板（保持可用），
   「剪切行」= 复制 + 删除，「重复行」才是原地复制一行。
8. **保存编码闭环**：写回走 `TextEncodings.encode` —— `UTF-8 (BOM)` / `UTF-16LE/BE` 重建 BOM
   （UTF-16 只靠 BOM 被识别，丢 BOM 会乱码）；GBK / ISO-8859-1 做往返校验，有字符表示不了时
   回退 UTF-8 并在状态栏注明「转存自 …」。读 / 写 / 识别共用同一张编码表。

## 3. 怎么加一种语言

1. 把语法文件放 `assets/textmate/<lang>/syntaxes/`（TextMate 的 `*.tmLanguage(.json)`，
   plist 与 JSON 两种格式都支持）；
2. 把语言配置放 `assets/textmate/<lang>/language-configuration.json`
   （**必须是严格 JSON**：VS Code 上游文件常带注释 / 尾逗号，需要去掉）；
3. 在 `assets/textmate/languages.json` 追加一条
   `{ grammar, name, scopeName, languageConfiguration }`（scopeName 必须与语法文件里的一致；
   跨语法引用用 `embeddedLanguages`，如 html → javascript/css）；
4. 在 `EditorLanguages.scopeOf()` 加后缀映射、`LABELS` 加显示名、`ORDER` 加进「语法」菜单顺序；
5. 跑 `EditorLanguagesTest`（映射用例）+ 打开一个该类型文件实机看一眼。

## 4. 已知边界

- 语法高亮依赖库的 TextMate 实现；极端病态输入（超长单行等）仍可能慢，
  此时可把 `HL_MAX_CHARS` 调小；
- 编辑器主题从 4 套内置主题里挑（浅色 quietlight / 深色 darcula），
  背景/行号栏/当前行**覆盖成应用配色**，其余 token 颜色来自主题；
- 「切换注释」仍走自研 `LineOps`（`commentPrefixOf`；css 系按扩展名区分：scss / less 为 `//`，
  纯 css 置灰），没有使用库的语言配置里的注释规则。
