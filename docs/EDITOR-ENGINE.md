# 文本编辑器引擎（sora-editor）· 架构与维护说明

> 版本：v1.6.0 起。此前是自研 `BasicTextField` 编辑器（已在 v1.6.0 删除，
> 历史上「大文件点一下卡一下」的根因就是它：整篇文本参与 Compose 排版 +
> 每次按键整篇重跑词法分析）。

## 1. 组件与职责

| 文件 | 职责 |
|---|---|
| `ui/editor/EditorScreen.kt` | 页面壳：读文件 / 编码识别 / 保存（.bak 备份）/ 行操作 / 大文件分段浏览 / 顶部菜单 / 查找条接线 |
| `ui/editor/EditorSearchBar.kt` | 底部查找条（查找 / 替换两行 + 上个 / 下个 / 替换 / 全部 / ⋮），选项在 ⋮ 里 |
| `ui/editor/EditorLanguages.kt` | sora 语言 / 主题注册表初始化、后缀 → scope 映射、配色创建 |
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
   > 640k 字符自动退化为纯文本高亮（`HL_MAX_CHARS`）。

## 3. 怎么加一种语言

1. 把语法文件放 `assets/textmate/<lang>/syntaxes/`（TextMate 的 `*.tmLanguage(.json)`，
   plist 与 JSON 两种格式都支持）；
2. 把语言配置放 `assets/textmate/<lang>/language-configuration.json`
   （**必须是严格 JSON**：VS Code 上游文件常带注释 / 尾逗号，需要去掉）；
3. 在 `assets/textmate/languages.json` 追加一条
   `{ grammar, name, scopeName, languageConfiguration }`（scopeName 必须与语法文件里的一致；
   跨语法引用用 `embeddedLanguages`，如 html → javascript/css）；
4. 在 `EditorLanguages.scopeOf()` 加后缀映射、`LABELS` 加显示名；
5. 跑 `EditorLanguagesTest`（映射用例）+ 打开一个该类型文件实机看一眼。

## 4. 已知边界

- 语法高亮依赖库的 TextMate 实现；极端病态输入（超长单行等）仍可能慢，
  此时可把 `HL_MAX_CHARS` 调小；
- 编辑器主题从 4 套内置主题里挑（浅色 quietlight / 深色 darcula），
  背景/行号栏/当前行**覆盖成应用配色**，其余 token 颜色来自主题；
- 「切换注释」仍走自研 `LineOps`（`commentPrefixOf`），没有使用库的语言配置里的注释规则。
