package com.u707t.panelfm

import com.u707t.panelfm.core.common.MimeTypes
import com.u707t.panelfm.core.vfs.archive.ArchiveVfs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「什么算压缩包」有两份注册表：
 *  - `MimeTypes.Kind.ARCHIVE`：决定类型过滤 / 图标 / 列表分类；
 *  - `ArchiveVfs.ArchiveKind.ofFileName`：决定点开时能不能真的进压缩包浏览。
 *
 * 两份表漂移过一次：`zst` 只在 MimeTypes 里，于是类型过滤能筛出 `.zst`，
 * 点开却按「非压缩包」走预览/二级菜单。这个测试把两边钉在一起。
 *
 * 放在 app 模块是因为只有它同时依赖 core:common 与 core:vfs-archive
 * （core:common 不能反向依赖压缩包实现）。
 */
class ArchiveKindConsistencyTest {

    @Test
    fun `MimeTypes 的压缩包扩展名必须都能被 ArchiveVfs 识别`() {
        val missing = MimeTypes.extensionsOf(MimeTypes.Kind.ARCHIVE)
            .filter { ArchiveVfs.ArchiveKind.ofFileName("sample.$it") == null }
        assertEquals(
            "这些扩展名被类型过滤当成压缩包，但 ArchiveVfs 打不开（要么补实现，要么从 Kind.ARCHIVE 移出）：$missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `zst 目前不归入压缩包（没有 zstd 解码器，不能给一个点开就报错的分类）`() {
        assertEquals(MimeTypes.Kind.OTHER, MimeTypes.kindOf("zst"))
        // 补上 zstd 解析后，把这条断言连同 Kind.ARCHIVE 的 zst 一起改 —— 两个表必须同时动
        assertEquals(null, ArchiveVfs.ArchiveKind.ofFileName("sample.zst"))
    }
}
