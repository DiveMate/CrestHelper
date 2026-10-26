package com.divelog.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3 导入链路测试：真实 `.bin` -> JNI 解析 -> 落盘 -> 重新加载 -> 出现在数据集里。
 *
 * 为什么用仪器测试而不是点 SAF 选择器：
 *  - 系统文件选择器（DocumentsUI）是另一个进程的重型界面，用 adb 点坐标既脆弱，
 *    又会把被测 App 挤到后台（三星内存压力大时会直接杀掉），验证结果不可信；
 *  - 真正要证明的是"字节进 -> 数据出"这条链路，选择器只是取字节的一种方式，
 *    以后换成蓝牙下载字节，测的还是同一段代码。
 *
 * 用 golden_dive1.bin（内置真实样本）+ 2 个尾字节构造"同一次潜水的新文件"：
 * 字节数不同 => 去重签名不同 => 应被当成新记录；再导一次则应判为重复。
 */
@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {

    private val testCtx = InstrumentationRegistry.getInstrumentation().context
    private val targetCtx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = DiveRepository(targetCtx)

    @After
    fun cleanUp() = runBlocking {
        // 测试数据不能留在用户手机上
        repo.clearImported()
    }

    private fun goldenPadded(): ByteArray {
        val raw = testCtx.assets.open("golden_dive1.bin").use { it.readBytes() }
        return raw + byteArrayOf(0, 0)
    }

    @Test(timeout = 120_000)
    fun imported_bytes_are_parsed_and_visible_in_dataset() = runBlocking {
        val bytes = goldenPadded()

        // 1) 首次导入：应成功入库
        val first = repo.importBytes(listOf(bytes))
        assertEquals("首次导入应当成功 1 条（实际：${first.text}）", 1, first.added)

        // 2) 缺省 .so 或解析失败时给出清晰提示，而不是静默通过
        assertTrue("libdc 未加载，无法验证导入", DiveParser.isAvailable)

        // 3) 数据集里能取到这条记录，且数值与 golden 样本一致
        val ds = repo.load(force = true)
        val imported = ds.dives.firstOrNull { it.file.startsWith("imp_") }
        assertNotNull("重载后应能看到导入的记录", imported)
        imported!!
        assertEquals(3673, imported.divetimeS)
        assertEquals(7.5, imported.maxdepthM, 0.2)
        assertTrue("导入的记录必须有采样点，否则画不出剖面", ds.samplesOf(imported).size > 20)

        // 4) 同一条再导一次：必须判为重复，不能出现两条
        val second = repo.importBytes(listOf(bytes))
        assertEquals(1, second.duplicate)
        assertEquals(0, second.added)
        assertEquals(1, repo.load(force = true).dives.count { it.file.startsWith("imp_") })
    }

    @Test(timeout = 120_000)
    fun invalid_bytes_are_reported_as_failed_not_crash() = runBlocking {
        // 随便几个字节不是合法 CR-4 日志：应计入 failed，且不影响已有数据
        val before = repo.load(force = true).dives.size
        val summary = repo.importBytes(listOf(byteArrayOf(1, 2, 3, 4, 5)))
        assertEquals(1, summary.failed)
        assertEquals(0, summary.added)
        assertEquals(before, repo.load(force = true).dives.size)
    }
}
