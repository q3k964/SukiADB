package frb.axeron.manager.adb

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
import android.util.Log
import androidx.annotation.RequiresApi
import frb.axeron.api.Axeron
import frb.axeron.manager.R
import frb.axeron.manager.ui.AxActivity
import frb.axeron.shell.Axsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the activation alive.
 *
 * [BootWatchdogService] only runs once, at boot, and stops itself as soon as the server answers.
 * Nothing then watches the binder, so every later drop — adbd recycling the TCP connection,
 * HyperOS disabling wireless debugging after a network change, the server process being killed
 * under memory pressure — stays dropped until the user re-pairs by hand.
 *
 * This service closes that gap: a low-importance ongoing notification keeps the process out of
 * the background bucket, and a loop pings the binder. When the ping fails it replays the same
 * activation chain the boot watchdog uses, with exponential backoff so a genuinely broken state
 * (wireless debugging left off, adbd refusing) doesn't spin the CPU.
 *
 * It is deliberately silent when everything is fine: no toast, no re-notification, one static
 * line. The only state change announced is "reconnecting".
 */
@RequiresApi(Build.VERSION_CODES.R)
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAlive"
        private const val CHANNEL = "keep_alive"
        private const val NOTIFICATION_ID = 4

        /** How often a healthy binder is re-checked. Cheap: one binder ping. */
        private const val TICK_MS = 45_000L

        /** First retry gap after a failed replay, doubling up to [MAX_RETRY_MS]. */
        private const val MIN_RETRY_MS = 30_000L
        private const val MAX_RETRY_MS = 10 * 60_000L

        private val running = AtomicBoolean(false)

        val isRunning: Boolean get() = running.get()

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }.onFailure { Log.e(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) return START_STICKY

        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.keep_alive_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    build(getString(R.string.keep_alive_starting)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, build(getString(R.string.keep_alive_starting)))
            }
        }.onFailure { Log.e(TAG, "startForeground failed", it) }

        job = scope.launch { loop() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        var retry = MIN_RETRY_MS
        var lastText = ""
        // 自保命令每次要起 4 个 shell 进程，45 秒一轮是纯浪费电。
        // 10 分钟刷一次足够——系统的桶不会在更短时间内被重置。
        var persistTicks = 0

        while (true) {
            val alive = runCatching { Axeron.pingBinder() }.getOrDefault(false)

            if (alive) {
                retry = MIN_RETRY_MS
                // Only touch the notification when the text actually changes, so the channel
                // stays quiet instead of waking the listener every 45 seconds.
                if (lastText != "running") {
                    lastText = "running"
                    notify(getString(R.string.keep_alive_running))
                }
                // The server is up: make sure the OS stops treating this app as expendable.
                if (persistTicks <= 0) {
                    persistTicks = 13          // 13 × 45s ≈ 10 分钟
                    applySelfPersistence()
                }
                persistTicks--
                delay(TICK_MS)
                continue
            }

            if (lastText != "reconnecting") {
                lastText = "reconnecting"
                notify(getString(R.string.keep_alive_reconnecting))
            }
            if (replay() && runCatching { Axeron.pingBinder() }.getOrDefault(false)) {
                retry = MIN_RETRY_MS
                lastText = ""
                continue
            }

            notify(getString(R.string.keep_alive_retry, (retry / 1000).toInt()))
            delay(retry)
            retry = (retry * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    /** Replays the boot watchdog's chain: wireless debugging → port → adbd → server. */
    private suspend fun replay(): Boolean {
        var ok = false
        // 两个 lambda 必须显式分开：Kotlin 只允许一个尾随 lambda，
        // 写成 f(x) { } { } 是语法错误。
        runCatching {
            AdbStarter.startAdbWireless(
                context = this,
                onStep = {},
                result = { info -> ok = info is AdbStateInfo.Success }
            )
        }.onFailure { Log.e(TAG, "replay failed", it) }
        return ok
    }

    /**
     * Asks the OS to stop killing this app. Every command goes through the shell the server is
     * already running, so it needs no extra permission; each one is best effort and skipped when
     * the command isn't supported on this ROM.
     */
    private suspend fun applySelfPersistence() = withContext(Dispatchers.IO) {
        val pkg = packageName
        runCatching {
            Axsh.exec(arrayOf("cmd", "deviceidle", "whitelist", "+$pkg")).waitFor()
        }
        runCatching {
            Axsh.exec(arrayOf("am", "set-standby-bucket", pkg, "active")).waitFor()
        }
        runCatching {
            Axsh.exec(arrayOf("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")).waitFor()
        }
        runCatching {
            Axsh.exec(arrayOf("cmd", "appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow")).waitFor()
        }
        Unit
    }

    private fun notify(text: String) {
        runCatching { notificationManager.notify(NOTIFICATION_ID, build(text)) }
    }

    private fun build(text: String): Notification {
        val intent = Intent(this, AxActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_axeron)
            .setContentTitle(getString(R.string.keep_alive_title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }
}
