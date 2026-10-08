package com.u707t.panelfm.tools

import com.u707t.panelfm.core.common.AppDirs
import com.u707t.panelfm.core.common.PanelDispatchers
import com.u707t.panelfm.core.vfs.VfsEnv
import com.u707t.panelfm.core.vfs.VfsUri
import com.u707t.panelfm.core.vfs.local.LocalVfs
import com.u707t.panelfm.core.vfs.local.LocalVolumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 回收站首套回归（第 8 批）。
 *
 * 覆盖：移入（含索引写入）/ 索引写失败回滚（第 8 批 🟡1 的跨卷回滚缺口对应语义）/
 * 还原重名不覆盖（(1) 后缀）/ 彻底删除。
 */
class TrashServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dirs: AppDirs
    private lateinit var service: TrashService

    private fun newService(): TrashService {
        dirs = AppDirs(
            tmp.newFolder("files").absolutePath,
            tmp.newFolder("cache").absolutePath,
        ).also { it.ensure() }
        val env = VfsEnv(
            appDirs = dirs,
            // 显式给全四个调度器：默认 main = Dispatchers.Main.immediate 在 JVM 测试里不可用
            dispatchers = PanelDispatchers(
                io = Dispatchers.IO,
                vfs = Dispatchers.IO,
                decode = Dispatchers.Default,
                main = Dispatchers.Unconfined,
            ),
        )
        service = TrashService(dirs, LocalVfs(env))
        return service
    }

    private fun localUri(file: File): VfsUri =
        VfsUri.of("local", LocalVolumes.AUTHORITY_ROOT, file.absolutePath)

    /** 工作目录里的一个真实文件（模拟用户要删除的文件） */
    private fun sourceFile(name: String = "a.txt", content: String = "hello"): File {
        val f = File(tmp.newFolder("workspace"), name)
        f.writeText(content)
        return f
    }

    @Test
    fun moveToTrash_movesFileAndWritesIndex() = runTest {
        val svc = newService()
        val src = sourceFile(content = "v1")

        assertEquals(1, svc.moveToTrash(listOf(localUri(src))))

        assertFalse("源文件应已移走", src.exists())
        val entry = svc.list().single()
        assertEquals("a.txt", entry.name)
        assertEquals(src.absolutePath, entry.originalPath)
        assertTrue("回收站里应有文件实体", File(dirs.trashDir, entry.trashName).exists())
        assertTrue("索引文件应已落盘", File(dirs.trashDir, "index.json").exists())
    }

    @Test
    fun moveToTrash_rollsBackWhenIndexSaveFails() = runTest {
        val svc = newService()
        val src = sourceFile(content = "v1")
        // 构造索引写失败：index.json.tmp 位置被占为目录 → writeText 必失败
        File(dirs.trashDir, "index.json.tmp").mkdirs()

        assertEquals("索引写失败应返回 0 并回滚", 0, svc.moveToTrash(listOf(localUri(src))))

        assertTrue("回滚后源文件必须还在原处", src.exists())
        assertEquals("v1", src.readText())
        assertTrue("不应留下索引条目", svc.list().isEmpty())
    }

    @Test
    fun restore_keepsBothWhenOriginalNameTaken() = runTest {
        val svc = newService()
        val src = sourceFile(content = "old")
        assertEquals(1, svc.moveToTrash(listOf(localUri(src))))
        val entry = svc.list().single()

        // 原位置又出现同名文件（用户重新创建 / 其它来源）
        src.writeText("new")
        assertTrue(svc.restore(entry))

        assertEquals("还原不得覆盖新文件", "new", src.readText())
        val sibling = File(src.parentFile, "a (1).txt")
        assertTrue("被还原的内容应落在「a (1).txt」", sibling.exists())
        assertEquals("old", sibling.readText())
        assertTrue("还原后索引应清空该条目", svc.list().isEmpty())
    }

    @Test
    fun purge_removesFileAndIndexEntry() = runTest {
        val svc = newService()
        val src = sourceFile()
        assertEquals(1, svc.moveToTrash(listOf(localUri(src))))
        val entry = svc.list().single()

        svc.purge(entry)

        assertFalse(File(dirs.trashDir, entry.trashName).exists())
        assertTrue(svc.list().isEmpty())
    }
}
