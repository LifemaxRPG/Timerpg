package com.timerpg.street.night

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.timerpg.street.shot.Crumbs
import org.json.JSONObject

/**
 * The overnight writer: while the phone charges and the game is closed, the dialogue model keeps writing the town's
 * lines into the bank ([com.timerpg.street.dialogue.ReplyCache]), so the game needs little live generation.
 *
 * How it starts (Android 15 limits foreground services started from the background):
 * - the game goes out of sight while the phone is plugged in: [gameClosed] starts [NightService] at once (an app that
 *   was just in front may start one);
 * - the phone is plugged in later, the game closed: [NightJob] (JobScheduler, requires charging, persisted across
 *   reboots) runs and starts [NightService]. Android 12+ allows that only when the app's battery use is
 *   "Unrestricted" (exempt from battery optimisation, the AI panel's button asks); otherwise the job writes itself, for
 *   the job's own time (about 10 minutes), and is scheduled again: slower, but it still works.
 * It stops when unplugged, when the game opens (it writes its own lines), when switched off, or when the bank is
 * complete ([NightRules]). One [NightSession] at a time.
 */
object NightWriter {
    /** The game's activity is in sight (onStart to onStop). */
    @Volatile var gameOpen = false
        private set
    /** The session running now, if any. */
    @Volatile var session: NightSession? = null
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("night", Context.MODE_PRIVATE)

    /** The AI panel's "Write dialogue while charging" (on unless switched off). */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        Crumbs.add("overnight writer " + if (on) "switched on" else "switched off")
        if (on) schedule(ctx) else { session?.stop("switched off in the AI panel"); cancel(ctx) }
    }

    /** The owner's choice to send their own words in night reports (off: counts and theme labels only). */
    fun shareWords(ctx: Context): Boolean = prefs(ctx).getBoolean("shareWords", false)
    fun setShareWords(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("shareWords", on).apply() }

    /** The town the game plays (as the renderer's settings say, read without starting the renderer). */
    fun townDir(ctx: Context): String {
        val p = ctx.getSharedPreferences("renderer", Context.MODE_PRIVATE)
        val town = p.getString("town", com.timerpg.street.TownSession.DEFAULT) ?: com.timerpg.street.TownSession.DEFAULT
        return if (p.getBoolean("filament", true) && town != "crossroads") "town/$town/" else ""
    }

    // ---- the game's side

    /** The game is in sight: it takes over (a session stops and lets go of a model it loaded). */
    fun gameOpened() {
        gameOpen = true
        session?.stop("the game opened (it writes its own lines)")
    }

    /** The game went out of sight: on the charger, start now; else at the next charge. */
    fun gameClosed(ctx: Context) {
        gameOpen = false
        if (!enabled(ctx) || com.timerpg.street.NpuTune.active(ctx)) return
        schedule(ctx)
        if (plugged(ctx) && session == null) startService(ctx)
    }

    // ---- starting

    /** Starts [NightService] in the foreground; false if Android does not allow it from here (the background). */
    fun startService(ctx: Context): Boolean = try {
        val i = Intent(ctx, NightService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        true
    } catch (e: Exception) {
        // Android 12+: ForegroundServiceStartNotAllowedException (an IllegalStateException) from the background
        Crumbs.add("overnight writer: the service may not start from here (${e.javaClass.simpleName})")
        false
    }

    /** A session for [how] ("service" or "job"); null if one is already running. [onEnd] runs when it stops. */
    @Synchronized
    fun begin(ctx: Context, how: String, onEnd: (NightStep.Stop) -> Unit): NightSession? {
        if (session != null) return null
        val s = NightSession(ctx.applicationContext, how) { stop -> ended(ctx.applicationContext, stop); onEnd(stop) }
        session = s
        s.start()
        return s
    }

    private fun ended(ctx: Context, stop: NightStep.Stop) {
        synchronized(this) { session = null }
        if (stop.again && enabled(ctx)) schedule(ctx)
    }

    /** The job that starts the writer at the next charge (kept across reboots). */
    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NightJob::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
            .apply { if (Build.VERSION.SDK_INT >= 26) setRequiresBatteryNotLow(true) }
            .build()
        runCatching { js.schedule(job) }.onFailure { Crumbs.add("overnight writer: could not schedule its job: $it") }
    }

    fun cancel(ctx: Context) { runCatching { ctx.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } }

    // ---- the phone

    /** Plugged in (a charger), whether or not it is charging now (Samsung's battery protection stops at 80-85%). */
    fun plugged(ctx: Context): Boolean {
        val b = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false
        return b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    fun battery(ctx: Context): Int = runCatching {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    /** The app is exempt from battery optimisation ("Unrestricted"): the service may start from the background. */
    fun unrestricted(ctx: Context): Boolean = Build.VERSION.SDK_INT < 23 ||
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Android's own dialog for the exemption (shown from the AI panel's button). */
    @android.annotation.SuppressLint("BatteryLife")
    fun askUnrestricted(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---- the notification

    const val NOTIFICATION_ID = 7301
    private const val JOB_ID = 7301
    private const val CHANNEL = "night"

    fun notification(ctx: Context, text: String, sub: String?): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Writing dialogue overnight", NotificationManager.IMPORTANCE_LOW).apply {
                description = "While the phone charges, the game's people get their lines written ahead"
                setShowBadge(false)
            })
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, NightService::class.java).setAction(NightService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val m = Regex("""(\d+) of (\d+)""").find(text)
        return b.setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(text)
            .apply { if (sub != null) setContentText(sub) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply { if (m != null) setProgress(m.groupValues[2].toInt(), m.groupValues[1].toInt(), false) }
            .apply { if (open != null) setContentIntent(open) }
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    fun notify(ctx: Context, text: String, sub: String?) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(ctx, text, sub)) }
    }

    fun clearNotification(ctx: Context) { runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } }

    // ---- last night

    /** The last run's summary (the report's JSON), for the AI panel. */
    fun lastRun(ctx: Context): JSONObject? = prefs(ctx).getString("last", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun lastSummary(ctx: Context): String? = prefs(ctx).getString("lastLine", null)

    internal fun saveLast(ctx: Context, json: String, line: String) { prefs(ctx).edit().putString("last", json).putString("lastLine", line).apply() }
}
