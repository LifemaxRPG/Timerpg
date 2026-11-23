package com.timerpg.street.night

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.timerpg.street.shot.Crumbs

/**
 * The overnight writer and Writer mode as a foreground service (in the writer's own process, ":writer"), type specialUse (Android 14+: long on-device computation fits none of
 * the other types: dataSync is for transfers and is limited to 6 hours a day from Android 15, mediaProcessing is for
 * media). A low-priority notification shows the progress, with a Stop button. Started by [NightWriter.startService]:
 * [ACTION_WRITER] from Writer mode's screen (allowed: it is in front), else by the game going out of sight or the job.
 */
class NightService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var mine: NightSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Crumbs.add("overnight writer: Stop tapped")
            NightWriter.session?.stop("stopped from the notification")
            stopSelf()
            return START_NOT_STICKY
        }
        val writer = intent?.action == ACTION_WRITER
        try {
            val n = NightWriter.notification(this, if (writer) "Writer mode" else "Writing dialogue", "Starting…")
            if (Build.VERSION.SDK_INT >= 34) startForeground(NightWriter.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NightWriter.NOTIFICATION_ID, n)
        } catch (e: Exception) {
            // (Android may still refuse here: the job then writes in its own time)
            Crumbs.add("overnight writer: could not go to the foreground (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        // (an overnight session already running here: Writer mode takes it over, the model it loaded kept)
        if (writer) NightWriter.session?.takeIf { !it.writer }?.becomeWriter()
        // (Writer mode asked while an overnight session started a moment before has just stopped, its end not handed over
        // yet: Writer mode's own session starts rather than nothing, as it used to)
        if (writer && mine?.done == true) mine = null
        if (mine == null) {
            var started: NightSession? = null
            val s = NightWriter.begin(this, if (writer) "writer" else "service") { main.post { done(started) } }
            started = s
            if (s == null) {
                // a session already runs (inside the job): this service only keeps the process in the foreground for it
                NightWriter.session?.let { running -> mine = running; running.onStopped { main.post { done(running) } } }
                    ?: done(null)
            } else mine = s
        }
        return START_NOT_STICKY
    }

    /** [s] stopped (null: nothing to run): the service ends, unless another session has taken its place meanwhile. */
    private fun done(s: NightSession?) {
        if (s != null && mine != null && mine !== s) return
        mine = null
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() {
        mine?.stop("the service was stopped")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.timerpg.street.night.STOP"
        const val ACTION_WRITER = "com.timerpg.street.night.WRITER"
    }
}
