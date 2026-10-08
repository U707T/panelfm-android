# RAR 测试样本（fixture）

来源：[junrar](https://github.com/junrar/junrar) 仓库的 `src/test/resources`（master，v8.1.1 时期），
只取了几个**几十到几百字节**的小样本用于回归测试；文件名带 `-real-compressed` / `-solid` / `-encrypted` 的是按用途改名。

| 文件 | 原路径 | 内容 / 用途 | 口令 |
|---|---|---|---|
| `rar4.rar` | `rar4.rar` | RAR4（RAR15）：`FILE1.TXT` / `FILE2.TXT`，各 7 字节 `file1\r\n` / `file2\r\n` | — |
| `rar5.rar` | `rar5.rar` | RAR5（RAR50）：同上两个文件 | — |
| `rar5-real-compressed.rar` | `blake2/rar5-htb.rar` | RAR5 真压缩数据（`payload.bin` 2000B，压缩后 280B） | — |
| `rar4-solid.rar` | `solid/rar4-solid.rar` | RAR4 固实包：`file1.txt`…`file4.txt`，各 `fileN\n` | — |
| `rar4-content-encrypted.rar` | `password/rar4-only-file-content-encrypted.rar` | RAR4：文件名可见、**仅数据加密** | `test` |
| `rar4-data-encrypted.rar` | `password/rar4-password-junrar.rar` | RAR4 数据加密：`file1.txt` = `file1\n` | `junrar` |
| `rar4-header-encrypted.rar` | `password/rar4-encrypted-junrar.rar` | RAR4 **头加密**（无口令时 junrar「打开成功但零条目」——靠 isPasswordProtected 识别） | `junrar` |
| `rar5-data-encrypted.rar` | `password/rar5-password-junrar.rar` | RAR5 数据加密：`file1.txt` = `file1\n` | `junrar` |
| `rar5-header-encrypted.rar` | `password/rar5-encrypted-junrar.rar` | RAR5 **头加密**（无口令时构造即抛 WrongPasswordException） | `junrar` |
| `parent-dir.rar` | `parent-dir.rar` | 恶意路径条目 `..\..\tmp\existing-file`（必须被拒绝，不得进索引） | — |
| `mkdir-escape.rar` | `mkdir-escape.rar` | 恶意路径条目 `../extract_evil/../extract/payload.txt`（同上） | — |

> 样本只用于单元测试（`RarVfsTest`），不进 APK 包。junrar 代码为 UnRAR 许可证（只读；
> 不得用于开发 RAR 兼容压缩器），这些测试档为 junrar 项目的测试数据。
