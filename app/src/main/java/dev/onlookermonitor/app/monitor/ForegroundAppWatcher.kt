package dev.onlookermonitor.app.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process

/**
 * Polls [UsageStatsManager] for the current foreground app and reports the package on every change.
 *
 * Requires the PACKAGE_USAGE_STATS special access (granted via Settings, checked with
 * [hasUsageAccess]). When it is not granted [start] returns false and the caller must fall back to
 * always-on monitoring — this component never silently no-ops.
 *
 * Polling runs on a dedicated background thread (the `queryEvents` binder call must not sit on the
 * main thread); [onForegroundPackage] is always delivered back on the main thread so the caller can
 * touch camera/UI state directly.
 */
class ForegroundAppWatcher(
    private val context: Context,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val onForegroundPackage: (String) -> Unit,
) {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var lastPackage: String? = null

    @Volatile
    private var running = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler?.postDelayed(this, pollIntervalMillis)
        }
    }

    fun hasUsageAccess(): Boolean {
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Starts polling. Returns false (and starts nothing) when Usage Access is not granted, so the
     * caller can fall back to always-on behaviour. The first poll emits the current foreground
     * package so the caller can make its initial bind/standby decision without a special case.
     */
    fun start(): Boolean {
        if (!hasUsageAccess()) return false
        if (running) return true
        running = true
        lastPackage = null
        val started = HandlerThread("fg-app-watcher").also { it.start() }
        thread = started
        handler = Handler(started.looper).also { it.post(pollRunnable) }
        return true
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - QUERY_WINDOW_MILLIS, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }
        if (latestPackage != null && latestPackage != lastPackage) {
            lastPackage = latestPackage
            val pkg = latestPackage
            mainHandler.post { if (running) onForegroundPackage(pkg) }
        }
    }

    companion object {
        // CALIBRATION (placeholder, NOT tuned): polling cadence. Needs on-device measurement of the
        // battery-vs-foreground-detection-latency trade-off. The camprobe probe showed 700ms works;
        // 1000ms trades a little latency for fewer background wakeups.
        const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L

        // How far back each poll looks for ACTIVITY_RESUMED events; comfortably covers the poll
        // interval plus OS event-batching lag.
        private const val QUERY_WINDOW_MILLIS = 10_000L
    }
}
