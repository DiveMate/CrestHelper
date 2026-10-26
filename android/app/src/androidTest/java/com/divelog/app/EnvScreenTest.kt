package com.divelog.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3 界面测试：真实数据加载、筛选、合并/清理入口、详情与算法切换。
 *
 * 这就是替代「人工装 APK 点一遍」的自动化：每个用例都跑在真机上，
 * 数据来自 APK 内置的 253 条真实日志（无需连手表）。
 *
 * 注意：
 *  - 所有用例都带 @Test(timeout)，避免息屏/权限对话框导致整轮挂死；
 *  - 断言前用 waitUntil 等数据加载完成（CSV 解析需要一点时间）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EnvScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 预授予 BLE 权限，避免系统对话框盖住 Activity 导致断言永久等待。 */
    @Before
    fun grantBlePermissions() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        perms.forEach { runCatching { ui.grantRuntimePermission(target, it) } }
    }

    private fun waitForList() {
        composeRule.waitUntil(20_000) {
            composeRule.onAllNodesWithText("潜次").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFirstDive() {
        waitForList()
        // 卡片是 clickable 合并节点，第一个含「时长」的节点即可点
        composeRule.onAllNodesWithText("时长").onFirst().performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("深度剖面").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test(timeout = 90_000)
    fun test_should_show_bottom_navigation() {
        composeRule.onNodeWithText("日志").assertIsDisplayed()
        composeRule.onNodeWithText("分析").assertIsDisplayed()
        composeRule.onNodeWithText("统计").assertIsDisplayed()
        composeRule.onNodeWithText("设备").assertIsDisplayed()
    }

    @Test(timeout = 90_000)
    fun test_data_should_load_from_assets() {
        waitForList()
        composeRule.onNodeWithText("潜次").assertIsDisplayed()
        composeRule.onNodeWithText("累计时长").assertIsDisplayed()
        composeRule.onNodeWithText("最大深度").assertIsDisplayed()
    }

    @Test(timeout = 90_000)
    fun test_year_filter_updates_scope() {
        waitForList()
        composeRule.onNodeWithText("2024").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("当前范围：2024 年").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("当前范围：2024 年").assertIsDisplayed()
        // 切回全部年份
        composeRule.onNodeWithText("全部年份").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("当前范围：全部记录").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test(timeout = 90_000)
    fun test_merge_dialog_opens() {
        waitForList()
        composeRule.onNodeWithText("合并设置").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("日志合并").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("自动合并").assertIsDisplayed()
        composeRule.onNodeWithText("完成").performClick()
    }

    @Test(timeout = 90_000)
    fun test_cleanup_dialog_opens_with_preview() {
        waitForList()
        composeRule.onNodeWithText("清理垃圾").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("清理垃圾日志").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("取消").performClick()
    }

    @Test(timeout = 90_000)
    fun test_open_dive_detail_shows_profile_and_metrics() {
        openFirstDive()
        composeRule.onNodeWithText("深度剖面").assertIsDisplayed()
        composeRule.onNodeWithText("平均深度").assertIsDisplayed()
        composeRule.onNodeWithText("最低水温").assertIsDisplayed()
        composeRule.onNodeWithText("最高水温").assertIsDisplayed()
        composeRule.onNodeWithText("最小安全深度").assertIsDisplayed()
    }

    @Test(timeout = 90_000)
    fun test_detail_has_ndl_and_gf99_charts() {
        openFirstDive()
        composeRule.onNodeWithText("GF99 峰值").assertIsDisplayed()
        composeRule.onNodeWithText("NDL 最低").assertIsDisplayed()
        composeRule.onNodeWithText("NDL 出水").assertIsDisplayed()
    }

    @Test(timeout = 90_000)
    fun test_algorithm_switch_keeps_layout() {
        openFirstDive()
        // 选一个靠前、必定可见的档位（LazyRow 只组合可见项）
        composeRule.onNodeWithText("GF 80/80").performClick()
        composeRule.onNodeWithText("GF99 峰值").assertIsDisplayed()
        composeRule.onNodeWithText("减压时长").assertIsDisplayed()
    }

    @Test(timeout = 90_000)
    fun test_device_tab_offers_log_import() {
        waitForList()
        composeRule.onNodeWithText("设备").performClick()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("导入日志").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("导入日志").assertIsDisplayed()
        composeRule.onNodeWithText("选择文件导入").assertIsDisplayed()
    }
}
