# 本轮改动审计（2026-10-11 · 编辑器 NP 对齐 / 大文件档位 / 单一入口）

> 范围：v2.0.15 → v2.0.17 三个提交的全部改动（不是全项目审计）。
> 方法：逐文件通读改动 + 反查 sora-editor 0.24.6 字节码 / 源码与 DataStore 序列化行为；
> 所有 🔴 / 🟡 已在本轮修复（修复即附回归用例），🔵 留待排期。
> 关联文档：`EDITOR-ENGINE.md`（编辑器设计）、`AUDIT-2026-10-08-REAUDIT.md`（全量重审，既有模块）。

## §0 分诊表（按本轮改动范围）

| 模块 | 范围（路径 / 规模） | 等级 | 依据（证据） |
|---|---|---|---|
| **ui/editor** 编辑器 | `ui/editor/` · 12 文件 / ≈2,600 行（本轮 +1,400） | 🔴 | 本轮最大改动面：菜单模型 / 文件编码 / 打开档位 / 预览状态同步；`EditorScreen.kt` 884 行 |
| **ui/preview + ui/browser** 单一入口 | `PreviewScreen` / `TypeActions` / `BrowserActionBars` / `DualPaneScreen` | 🟡 | 改的是路由 / 菜单 / 手势，行为变化对用户可见；删除 `PreviewMode.TEXT` 涉及旧数据回落 |
| **core/data** 编辑器偏好 | `PrefsStore`（新增 9 项 + 1 处键类型变更） | 🟡 | 键类型变更过 = 迁移面；DataStore 的 `Key` 只按名字比较 |

---

## §1 ui/editor（🔴 · 重点查）

### 🔴 1. 读取上限**静默截断**：超过 64 MB 的文件会被当成「全文」交给用户编辑

- 位置：`EditorFileIo.kt`（`readAtMost` / `readWholeText`）、`EditorScreen.kt`（`openWhole` 调用点）
- 问题：`readAtMost(max)` 在 `total >= max` 时**正常返回**已读内容 —— 也就是说：
  ① 远端 VFS 报 `size = -1`（不知道大小）时档位判定会放行；② 用户在编辑器里停留期间文件被写大；
  两种情况都会把「前 64 MB」当全文灌进编辑器，用户改一行再保存 → **静默把大文件写小**。
- 为什么：这是本轮改动引入的风险窗口（v2.0.15 之前上限是 2 MB 且大文件走只读分段，破坏面小；
  v2.0.16 去掉分段、上限抬到 64 MB 后，触发面变大）；丢数据的 bug 优先级永远最高。
- 修复（已做）：`readAtMost` 改成**读 `max + 1` 字节**，超过就返回 `null`；`readWholeText` 随之可空；
  `EditorScreen` 的打开 / 「确认后打开」/ 重新加载三条路径各自明确拒绝并给文案。
  读取循环抽成纯函数 `readUpTo`（lambda 注入），`EditorOpenPlanTest` 新增 3 例
  （正好上限 / 超过上限 / 每 1 字节碎片读）。

### 🟡 2. JSON 校验器递归无深度上限：上万层 `[` 会把「校验」变成应用崩溃

- 位置：`EditorValidate.kt`（`JsonScanner.parseValue` ↔ `parseObject` / `parseArray` 互相递归）
- 问题：递归下降不带深度限制，`StackOverflowError` 是 `Error` 不是 `Exception`，
  外层 `catch (e: Exception)` 接不住 → 一点「校验语法」直接闪退。
- 为什么：校验是用户显式触发的操作，用户文件（尤其机器生成/恶意构造）不可信；
  崩溃比「报个错」的代价高两个量级。
- 修复（已做）：`MAX_JSON_DEPTH = 256`，超深报「嵌套层级过深」（仍带行列号）；
  `EditorValidateTest` 新增 1 例（10,000 层报错不崩 + 200 层通过）。

### 🟡 3. `EditorSymbolBar(enabled = true)` 是永远为真的开关（死参数）

- 位置：`EditorSymbolBar.kt:36`、`EditorScreen.kt`（调用点）
- 问题：v2.0.16 取消只读分段后，`enabled` 的唯一调用点固定传 `true`；
  参数与 `if (enabled) … else 半透明` 分支成了永不执行的代码 —— 读者会以为存在只读态。
- 为什么：死参数会让下一个改动（比如将来真加只读）以错误的方式接线：看起来已经支持，实际没接。
- 修复（已做）：删掉参数与分支（插入路径本身在 `insertSymbol` 里已经检查编辑器实例）。

### 🔵 4. `EditorScreen.kt` 884 行（本轮 512 → 884）

- 位置：`ui/editor/EditorScreen.kt`
- 问题：状态 40 个（含 9 个对话框开关）+ 动作分发 `onMenuAction`（26 个分支）+
  6 个选项对话框 + 编辑器视图同步，全在一个函数体里；已接近「1000 行巨型文件」红线。
- 修复（建议，未做）：按 §3 的既定范式拆三段——
  ① `EditorSessionState`（普通类 + `mutableStateOf` 字段：文件 / 编码 / 脏标记 / 滚动 / 对话框开关）；
  ② `EditorActions.kt`（`onMenuAction` 的 26 个分支搬成 `EditorActions(state, container, uri, editorRef)`）；
  ③ `EditorDialogHost.kt`（6 个对话框 + 4 个确认框，参数只收 state 与 pick 回调）。
  目标：`EditorScreen` ≤ 350 行（只留装配）。改动机械、无行为变化，适合单独一个提交。

### 🟢 5. 做得好的地方（本轮点名）

- **纯模型 + 纯函数先行**：`editorMenuSections` / `editorOpenPlan` / `EditorCharsets` / `EditorSymbols` /
  `saveStatusText` / `fileChangedSince` / `parseTriState` —— 菜单可见性、打开档位、编码表、
  保存文案都有回归（本轮 4 个测试类新增 36 例），这些规则以前一个都测不到。
- **库契约用字节码核验过**（`EDITOR-ENGINE.md` §3 有记录）：`setNonPrintablePaintingFlags` / `Magnifier` /
  `drawSideBlockLine` / `insertText` / `indentOrCommitTab` / `canUndo` 的语义与 0.24.6 源码一致；
  Compose 状态只在事件回调里写（`AndroidView.update` 内不写状态），避免了「布局期写状态」的老坑。
- **编码表与实现的一致性有测试锁**：`EditorCharsetsTest` 逐项断言「能选的编码 = 能原样写回的编码」，
  正是 2026-10-08 重审里 ISO-8859-1 静默转存那类漂移的防线。
- **迁移防护**：`editor_fluent_mode` 由布尔键改三态字符串键时，发现 DataStore 的 `Key` 只按**名字**比较
  （按新类型读老数据会 `ClassCastException` 崩掉整条设置流），改为 `asMap()` 取原生值兼容两种形态，
  并留了 1 例回归（`PrefsEditorPrefsTest`）。

---

## §2 ui/preview + ui/browser（🟡 · 单一入口 / 中缝）

### 🔵 1. `PreviewScreen` 的 `editing` 布尔与 `effective` 语义重复（已修）

- 位置：`PreviewScreen.kt`（原 `var editing` + `if (editing) r = PreviewMode.EDITOR`）
- 问题：两个状态描述同一件事（当前模式），菜单里还要成对地写 `effective = X; editing = false`，
  漏写一处就会「点了没反应」。
- 修复（已做）：删掉 `editing`，「编辑文本」直接 `effective = PreviewMode.EDITOR`；
  其余入口的 `editing = false` 一并删除（少 1 个状态、7 处赋值）。

### 🟢 2. 单一入口的三处一致性（复核通过）

- `TypeActions`（长按菜单 + 单击路由）、`BrowserActionBars`（底栏按类型按钮）、`PreviewScreen`（预览页 ⋮）
  三处对「文本 / 代码 / 未识别」给出的入口现在都是**同一个编辑器页面**，且：
  - 旧数据回落安全：默认打开方式里残留的 `text`（`PreviewMode.ofHandler("text")` → null）
    会回落到自动识别路径，不会崩也不会卡在空白页（`openItem` 的 `mode != null` 分支之后还有两级兜底）；
  - `TypeActionsTest` 新增/改写 6 条断言锁住「不再有查看文本」；
  - 底栏「编辑」按钮与长按菜单「编辑文本」文案一致（图标 `MtIcon.EDIT`）。

### 🟢 3. 中缝固定等分（复核通过）

- 拖动 / 双击复位 / `splitRatio` 持久化全部删除（`DualPaneScreen` / `BrowserModels` / `BrowserController` /
  `PrefsStore`）；左右 `weight(1f)` 恒等分，阴影仍锚在 1px 中线上。
- 复核点：单列模式只渲染一个窗格（`showLeft` / `showRight`）不受影响；`panesWidthPx` /
  `onGloballyPositioned` 死代码一并删除。

---

## §3 收尾与清理

- 🔵 **陈旧 import 181 条**（`DualPaneScreen` 56 / `BrowserActionBars` 73 / `BrowserDialogHost` 67 /
  `BrowserController` 16 / `BrowserControllerNav` 23 …）：多数是此前「拆文件」重构留下的，
  本次顺带清理（全部经编译器验证）。这些文件的本体仍是 2,000+ 行大山，见 §4。
- 🔵 **`EditorSymbolBar` / `PreviewScreen` / `OpenWith`** 的零散死代码已清（同上）。

## §4 下一批建议（本轮范围之外）

1. **ui/browser 主链路**（`BrowserController*` 6 文件 / `DualPaneScreen` 等，仍有两座 2,000+ 行大山）
   —— 2026-10-08 重审已列过拆分方案，本轮只是把「分隔条」从其中一块搬走。
2. **ui/preview 媒体链路**（`MediaScreen.kt` 1,886 行）。
3. §1-🔵4 的 `EditorScreen` 拆分（机械、无行为变化，随时可做）。

## 本轮验证

- 全仓单测 **553 → 557 例全绿**（本轮新增：打开档位 3 例、JSON 深度 1 例）；
- `assembleDebug` / `lintDebug` 通过；
- 未跑真机（与既有审计口径一致，实机项见 CHANGELOG 的「待实机抽验」）。
