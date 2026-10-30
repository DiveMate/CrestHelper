package com.divelog.app.sync

import android.content.Context
import android.os.PowerManager
import com.divelog.app.DiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 进程级单例：一块表 + 一个同步执行器。
 *
 * 为什么不能放在 Composable 里：`rememberCoroutineScope()` 跟着页面走，
 * 退出同步页、旋转屏幕、切到别的 App，作用域一取消，跑到一半的下载就没了。
 * 放在进程级之后，界面只是"看状态"，谁来谁走都不影响后台在跑的活。
 */
object WatchHub {
    val session: WatchSession by lazy { WatchSession() }
    val engine: SyncEngine by lazy { SyncEngine(session) }
}

/**
 * 同步执行器：把「读计划 → 下载 → 入库」跑在**应用级**作用域里。
 *
 * 三层保障后台不被掐断：
 *  1. [SyncService] 前台服务：进程优先级拉高，锁屏后不会被随手回收；
 *  2. PARTIAL_WAKE_LOCK：屏幕关了 CPU 也照常跑（前台服务本身不保证 CPU 不睡）；
 *  3. BLE 连接挂在 [WatchHub.session] 上：退出页面、Activity 重建都不重连。
 *
 * 需要说明的边界：这里解决的是"同步过程中切后台/锁屏不中断"。
 * 它**不是**定时后台同步 —— 做到那一步要 WorkManager + 定时唤醒重连 BLE，
 * 而 CR-4 一次全量要几分钟，系统对后台常驻扫描本就有限制，强行做会很不稳。
 */
class SyncEngine internal constructor(
    /** 取当前链路；单测里换成假表，生产里就是 session.link。 */
    private val linkProvider: () -> WatchLink?,
    /** 入库入口；单测里换成内存实现（JVM 没有 native 解析器）。 */
    private val ingestor: DiveIngestor? = null,
) {
    constructor(session: WatchSession) : this({ session.link })

    data class State(
        val running: Boolean = false,
        val mode: SyncMode? = null,
        val progress: SyncProgress? = null,
        val result: SyncResult? = null,
        val error: String? = null,
        /** 每完成一次（成功/失败/取消）+1，界面拿它当"该刷新了"的信号。 */
        val finishedTick: Int = 0,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    ) {
        val fraction: Float get() = progress?.fraction ?: 0f

        val line: String
            get() = when {
                progress != null -> "第 ${progress.current}/${progress.total} 条 · #${progress.index} · ${progress.stage}"
                running -> "准备中…"
                else -> ""
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * 启动一次同步。必须在主线程调用（会拉起前台服务）。
     *
     * @return 失败原因；null 表示已开始。
     */
    fun start(ctx: Context, repo: DiveRepository, mode: SyncMode): String? {
        if (_state.value.running) return "已经在同步了"
        val link = linkProvider() ?: return "还没连上手表，先点连接"
        val app = ctx.applicationContext

        // 唤醒锁 + 前台服务：这两样是"后台不断链"的全部保障，
        // 少了任何一个，锁屏五分钟后系统都可能把进程挂起。
        val wake = acquireWakeLock(app)
        SyncService.start(app)

        runSync(repo, mode, link) {
            runCatching { wake?.release() }
            SyncService.stop(app)
            job = null
        }
        return null
    }

    /**
     * 真正的执行体：不碰任何 Android 服务，只跑「读计划 → 下载 → 入库」。
     * 抽出来是为了能在 JVM 单测里用假表跑一遍（换真表跑不了测试）。
     */
    internal fun runSync(
        repo: DiveRepository,
        mode: SyncMode,
        link: WatchLink,
        teardown: () -> Unit = {},
    ): Job {
        _state.value = State(running = true, mode = mode, startedAt = clock())
        val j = scope.launch {
            val sync = if (ingestor == null) WatchSync(link, repo)
            else WatchSync(link, repo, ingestor = ingestor)
            try {
                val bundle = withContext(Dispatchers.IO) { sync.makePlan(mode) }
                val res = withContext(Dispatchers.IO) {
                    sync.run(bundle) { p -> _state.update { it.copy(progress = p) } }
                }
                _state.update {
                    it.copy(running = false, result = res, finishedTick = it.finishedTick + 1, finishedAt = clock())
                }
            } catch (c: CancellationException) {
                _state.update {
                    it.copy(running = false, error = "已取消", finishedTick = it.finishedTick + 1, finishedAt = clock())
                }
                throw c
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        error = t.message ?: t.toString(),
                        finishedTick = it.finishedTick + 1,
                        finishedAt = clock(),
                    )
                }
            } finally {
                runCatching { teardown() }
            }
        }
        job = j
        return j
    }

    fun cancel() {
        job?.cancel()
    }

    /** 结果看过就清掉，避免下次进来还挂着上一条的结论。 */
    fun clearResult() {
        if (_state.value.running) return
        _state.update { it.copy(result = null, error = null) }
    }

    private fun acquireWakeLock(ctx: Context): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return null
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_TIMEOUT_MS)
        }
    }.getOrNull()

    private fun clock() = System.currentTimeMillis()

    private companion object {
        const val WAKE_TAG = "divelog:sync"
        /** 兜底 30 分钟：真卡死了也别一直占着唤醒锁把电耗干。 */
        const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
