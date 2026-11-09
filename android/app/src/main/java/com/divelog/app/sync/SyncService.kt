package com.divelog.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.divelog.app.MainActivity
import com.divelog.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 同步用的前台服务。
 *
 * 作用只有一个：**让系统知道"这个 App 正在干一件用户可见的正经事"**。
 * 没有它的话，锁屏几分钟后进程被回收、BLE 断链，同步就断在中间了。
 *
 * 通知内容是活的：订阅 [SyncEngine.state]，标题跟着"第 N/M 条 · #编号 · 阶段"走，
 * 通知里带一个「取消」按钮，不用切回 App 就能停。
 *
 * 同步一结束（running 变 false）立刻收摊：先画出最终状态，再 stopSelf。
 * 不常驻，也不抢任何后台权限。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    /** 当前是否真的挂在前台上。startForeground 失败时保持 false，不能假装成功。 */
    private var foregroundOk = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须在 5 秒内 startForeground，先拿当前状态画一版
        foregroundOk = enterForeground()
        if (!foregroundOk) {
            // 拿不到前台资格（权限被撤销 / connectedDevice 校验没过 / 通知被禁用）：
            // 服务退出，但同步本身照跑 —— 界面会显示降级提示，别在这里崩掉整个 App。
            stopSelf()
            return
        }
        watcher = scope.launch {
            WatchHub.engine.state.collect { st ->
                if (!st.running) {
                    // 先摘前台通知（连带进度条和「取消」按钮），再补一条可点掉的最终结果
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    runCatching { notify(build(st)) }
                    stopSelf()
                    return@collect
                }
                runCatching { notify(build(st)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            WatchHub.engine.cancel()
            return START_NOT_STICKY
        }
        // 上一次同步刚结束、stopSelf 还没真正销毁时又点同步，会复用这个实例、只走这里。
        // 不重新进前台的话，这一轮同步没有任何后台保障（还可能触发
        // ForegroundServiceDidNotStartInTimeException）。
        if (!foregroundOk) foregroundOk = enterForeground()
        // 不要系统重启服务：同步是"这一次"的事，进程都死了再拉起来也没连接
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    /**
     * 进前台。失败不抛异常（onCreate 里抛出去会直接崩），而是把原因写进 engine，
     * 由界面提示「后台保障不可用，请保持屏幕常亮」。
     */
    private fun enterForeground(): Boolean {
        val n = build(WatchHub.engine.state.value)
        val r = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
        if (r.isFailure) {
            WatchHub.engine.warn(
                "后台保障未生效（${r.exceptionOrNull()?.message ?: "前台服务启动失败"}）" +
                    "，锁屏后同步可能被系统中断，建议保持屏幕常亮"
            )
            return false
        }
        return true
    }

    private fun build(st: SyncEngine.State): Notification {
        val title = when {
            st.running -> "正在同步手表"
            st.error != null -> "同步已结束：${st.error}"
            st.result != null -> "同步完成"
            else -> "手表同步"
        }
        val text = when {
            st.running -> st.line.ifBlank { "正在读取手表…" }
            else -> st.result?.let { "新增 ${it.added} · 更新 ${it.updated} · 跳过 ${it.skipped}" } ?: "已结束"
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(st.running)
            // 进行中：点一下只会回到 App，不该消失；结束后：点开或滑掉即可清除
            .setAutoCancel(!st.running)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (st.running) {
            val p = st.progress
            if (p != null && p.total > 0) {
                builder.setProgress(p.total, p.current, false)
            } else {
                builder.setProgress(0, 0, true)
            }
            val cancel = PendingIntent.getService(
                this, 1,
                Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "取消", cancel)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "手表同步", NotificationManager.IMPORTANCE_LOW).apply {
                description = "同步过程中显示进度；结束后保留一条结果通知，点开即清除"
                setShowBadge(false)
            }
        )
    }

    private fun notify(n: Notification) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !mgr.areNotificationsEnabled()
        ) return
        mgr.notify(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "dive_sync"
        private const val NOTIF_ID = 0x5EC4
        private const val ACTION_CANCEL = "com.divelog.app.sync.CANCEL"

        /**
         * 拉起（或复用）前台服务。调用方须已把 engine 置为 running。
         * @return false 表示服务没拉起来（权限/后台限制），由调用方提示降级。
         */
        fun start(ctx: Context): Boolean = runCatching {
            val i = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }.isSuccess

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, SyncService::class.java)) }
        }
    }
}
