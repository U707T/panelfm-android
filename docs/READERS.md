# 阅读器（SQLite 浏览 / EPUB）· 架构与维护说明

> 版本：v2.0.7 起。两者都是**只读**：不改数据库、不改电子书、不联网。

## 1. SQLite 只读浏览（`ui/preview/SqliteScreen.kt`）

| 项 | 说明 |
|---|---|
| 入口 | `.db` / `.sqlite` / `.sqlite3`：自动识别（AUTO）/ 预览页菜单「SQLite 浏览」/「打开方式…」；长按菜单也有 |
| 打开方式 | 本地文件直接 `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)`；**远程 / 压缩包内**先流式复制到缓存临时文件（上限 256 MB），退出页面时删除 |
| 界面 | 表列表（含行数，逐张回填）→ 点表进入「列 + 前 200 行」；单元格点击弹全文（可选可复制）；顶栏返回 = 先回表列表再去退出 |
| 安全 | 表名来自数据库文件（不可信）→ 拼 SQL 前一律走 `SqliteFormats.quoteIdentifier()`（`"` 翻倍）；**不提供 SQL 控制台** |
| 边界 | 加密库（SQLCipher 等）与损坏文件：给「不是有效的 SQLite 数据库」提示；BLOB 只显示类型（不读内容，避免大字段撑爆内存） |

纯函数（后缀识别、标识符加引号）在 `core/common/SqliteFormats.kt`，有单测；数据库本身依赖 Android API，走实机抽验。

## 2. EPUB 只读阅读（`ui/preview/EpubScreen.kt` + `EpubBook.kt`）

| 项 | 说明 |
|---|---|
| 入口 | `.epub`：长按菜单「阅读电子书」；「打开方式…」可设为默认（不设默认时点击仍按压缩包浏览 —— 它本身就是 zip） |
| 解析 | `META-INF/container.xml` → OPF（`dc:title` + manifest + spine）→ 章节标题：NCX（EPUB2）优先，其次 EPUB3 nav，都没有退化为文件名（`EpubParser`，纯函数 + 单测） |
| 渲染 | WebView **关 JS**（书是不可信输入：`<script>` / `on*` 不执行）；`https://epub.panelfm/<压缩包内路径>` 由拦截器映射到挂载好的 `ArchiveVfs`，其余 host / 协议一律 404 —— **不联网** |
| 编码 | xhtml/css/xml/svg 先过 `TextEncodings.decode()` 再转 UTF-8 —— 中文 GBK 的电子书不乱码 |
| 导航 | 上一章 / 下一章 / 目录（标题来自 NCX / nav）；书内锚点可跳，外链拦下 |
| 路径 | `EpubParser.resolveEpubPath()`：去 fragment/query、百分号解码（UTF-8、`+` 不当空格）、消化 `.`/`..`，**越出压缩包根返回 null**（防目录穿越） |
| 不做 | DRM、阅读进度、字体/主题设置（先保证能读）；单文件 > 8 MB 的书内资源不加载 |

解析层在 JVM 上有 6 例单测（container/OPF/NCX/nav/路径穿越/MIME）；WebView 渲染部分无 JVM 单测，走实机抽验。
