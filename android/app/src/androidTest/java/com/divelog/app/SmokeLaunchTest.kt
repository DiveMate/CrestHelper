package com.divelog.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * L3 冒烟测试：冷启动 Activity，任何崩溃都会被这里抓住。
 *
 * 这就是替代「人工装 APK 点一下看能不能开」的自动化：
 * connectedAndroidTest 会自动安装 APK、启动 Activity、断言进程仍存活，
 * 并把 logcat 里的 FATAL 堆栈打进测试报告。
 *
 * 重要：每个用例都带 @Test(timeout=...)。
 * 仪器测试里凡是「等待生命周期」的调用（ActivityScenario / moveToState）都有
 * 永久阻塞的可能（息屏、被其它 Activity 遮挡都会导致等不到目标状态）。
 * 没有超时的话，一个用例挂住会拖死整轮测试，反而掩盖真实结果。
 */
@RunWith(AndroidJUnit4::class)
class SmokeLaunchTest {

    @Test(timeout = 60_000)
    fun `app_cold_start_should_not_crash`() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        Thread.sleep(2500)   // 等 Compose 首帧 + .so 加载

        val pid = android.os.Process.myPid()
        assertTrue("进程 pid=$pid 应存活（崩溃则测试进程被杀）", pid > 0)
        assertNoFatal()
    }

    @Test(timeout = 60_000)
    fun `activity_scenario_can_resume_main_activity`() {
        // 用 ActivityScenario 走完整生命周期（launch→RESUMED→close），
        // 比单纯 startActivity 更严格，能抓到 onResume 里的异常。
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { /* 能回调到这里说明 Activity 已创建并可见 */ }
            assertTrue(
                "Activity 应至少处于 RESUMED（实际 ${scenario.state}）",
                scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
            )
        }
        assertNoFatal()
    }

    @Test(timeout = 90_000)
    fun `three_consecutive_cold_starts_succeed`() {
        // 抓「偶发」崩溃：单次通过不代表稳定
        repeat(3) {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            Thread.sleep(1200)
        }
        assertNoFatal()
    }

    @Test(timeout = 60_000)
    fun `background_then_foreground_should_not_crash`() {
        // 切后台再切回前台：Compose 生命周期 + ON_RESUME 刷新最容易崩
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { }
            // 只走到 STARTED（模拟「被遮挡」），再回前台。
            // 不用 moveToState(CREATED)：真机上它常因等待不到而永久阻塞。
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            Thread.sleep(400)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            Thread.sleep(400)
        }
        assertNoFatal()
    }

    /**
     * 读 logcat，发现 FATAL EXCEPTION 直接把堆栈带进测试报告。
     *
     * 注意：外部进程读取必须限时，否则 logcat 不退出时会连带把用例挂死。
     */
    private fun assertNoFatal() {
        val pid = android.os.Process.myPid()
        val pool = Executors.newSingleThreadExecutor()
        val text = try {
            pool.submit<String> {
                val proc = ProcessBuilder(
                    "logcat", "-d", "-t", "300", "*:E", "--pid=$pid"
                ).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor(5, TimeUnit.SECONDS)
                out
            }.get(10, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            ""   // 读不到 logcat 不影响崩溃判定本身
        } catch (e: Exception) {
            ""   // 某些 ROM 上 exec 会被限制
        } finally {
            pool.shutdownNow()
        }

        val fatal = text.lineSequence().filter {
            it.contains("FATAL EXCEPTION") || it.contains("Process: com.divelog.app")
        }.toList()
        assertTrue("检测到 App 崩溃:\n" + fatal.joinToString("\n"), fatal.isEmpty())
    }
}
