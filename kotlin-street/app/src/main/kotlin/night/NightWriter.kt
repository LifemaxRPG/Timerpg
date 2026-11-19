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
 * The overnight writer and Writer mode: the dialogue model writes the town's lines into the bank
 * ([com.timerpg.street.dialogue.ReplyCache]) with the game closed, so the game needs little live generation.
 *
 * Both run in their own process (":writer": [NightService], [NightJob], [WriterActivity]) with nothing of the game in
 * it (no town, no Filament, no voices, no speech or memory model), and close the game's own process when it sits in the
 * background ([closeGame]: Writer mode at once, the overnight writer after [GAME_GRACE_MS] out of sight, unless there is room beside it already), so the main model's copy
 * on the NPU has the most room. The game's process tells them when it opens ([gameOpened]: a broadcast, and its process
 * coming to the front); they stop, save, let the model go and release [WriterLock], and the game reads the bank after.
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
    /** The game's activity is in sight (onStart to onStop), as the game's own process knows it. */
    @Volatile var gameOpen = false
        private set
    /** The last session's screen state, kept after it stops (Writer mode's screen shows how it ended). */
    @Volatile var lastView: WriterView? = null
    /** The session running now, if any. */
    @Volatile var session: NightSession? = null
        private set

    // (read by two processes: the AI panel's switch is set in the game's, read in the writer's; reloaded when changed)
    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("night", Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS)

    /** The AI panel's "Write dialogue while charging" (on unless switched off). */
    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
        Crumbs.add("overnight writer " + if (on) "switched on" else "switched off")
        if (on) schedule(ctx) else { session?.stop("the overnight writer was switched off in the AI panel"); stopElsewhere(ctx, ACTION_SWITCHED_OFF); cancel(ctx) }
    }

    /** Writer mode's choices: write on battery too (else only plugged in), and keep the screen on (dimmed). */
    fun writerOnBattery(ctx: Context): Boolean = prefs(ctx).getBoolean("writerOnBattery", false)
    fun setWriterOnBattery(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("writerOnBattery", on).apply() }
    fun writerScreenOn(ctx: Context): Boolean = prefs(ctx).getBoolean("writerScreenOn", true)
    fun setWriterScreenOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("writerScreenOn", on).apply() }

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

    /** The game is in sight: it takes over (a session, here or in the writer's process, stops, saves and lets go of its
     *  model; the game reads the bank once it has, [WriterLock]). */
    fun gameOpened(ctx: Context? = com.timerpg.street.AppInit.context) {
        gameOpen = true
        session?.stop(GAME_OPENED)
        if (ctx != null) stopElsewhere(ctx, ACTION_GAME_OPENED)
    }

    /** Tells a session in the other process to stop (an explicit broadcast inside the app). */
    private fun stopElsewhere(ctx: Context, action: String) {
        runCatching { ctx.sendBroadcast(Intent(action).setPackage(ctx.packageName)) }
    }

    /** The game's process (the app's main one), if it runs. */
    fun gameProcess(ctx: Context): android.app.ActivityManager.RunningAppProcessInfo? = runCatching {
        ctx.getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses?.firstOrNull { it.processName == ctx.packageName }
    }.getOrNull()

    /** The game is in sight: as this process knows it, or (from the writer's process) its process is in front. */
    fun gameVisible(ctx: Context): Boolean {
        if (gameOpen) return true
        val p = gameProcess(ctx) ?: return false
        return p.pid != android.os.Process.myPid() && p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    /**
     * Ends the game's process when it sits out of sight (from the writer's process), so its town, renderer, voices and
     * speech models give their memory back to the dialogue model. Not a crash: the game's next start does not report it
     * ([takeClosedGame]). Returns the MB of free memory it gave (measured a moment after), -1 if there was none to close.
     */
    fun closeGame(ctx: Context): Long {
        val p = gameProcess(ctx) ?: return -1
        if (p.pid == android.os.Process.myPid() || gameVisible(ctx)) return -1
        val before = com.timerpg.street.llm.Ram.freeMb(ctx)
        runCatching { java.io.File(ctx.filesDir, CLOSED_MARK).writeText(System.currentTimeMillis().toString()) }
        Crumbs.add("writer: closing the game's process (pid ${p.pid}, out of sight) to free its memory; $before MB free")
        android.os.Process.killProcess(p.pid)
        try { Thread.sleep(2000) } catch (e: InterruptedException) { }
        val after = com.timerpg.street.llm.Ram.freeMb(ctx)
        Crumbs.add("writer: the game's process closed: $after MB free (${after - before} MB more)")
        return (after - before).coerceAtLeast(0)
    }

    /** True once if the writer closed the game's process since (not a crash), and clears it. */
    fun takeClosedGame(ctx: Context): Boolean {
        val f = java.io.File(ctx.filesDir, CLOSED_MARK)
        if (!f.isFile) return false
        val t = runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
        f.delete()
        return System.currentTimeMillis() - t < 7L * 24 * 3_600_000L
    }

    /** The game went out of sight: on the charger, start now (in the writer's process); else at the next charge. */
    fun gameClosed(ctx: Context) {
        gameOpen = false
        if (!enabled(ctx) || com.timerpg.street.NpuTune.active(ctx)) return
        schedule(ctx)
        if (plugged(ctx) && session == null) startService(ctx)
    }

    // ---- starting

    /** Starts [NightService] in the foreground ([writer]: for Writer mode, from its screen); false if Android does not
     *  allow it from here (the background, unless the app's battery use is "Unrestricted"). */
    fun startService(ctx: Context, writer: Boolean = false): Boolean = try {
        val i = Intent(ctx, NightService::class.java).apply { if (writer) action = NightService.ACTION_WRITER }
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        true
    } catch (e: Exception) {
        // Android 12+: ForegroundServiceStartNotAllowedException (an IllegalStateException) from the background
        Crumbs.add("overnight writer: the service may not start from here (${e.javaClass.simpleName})")
        false
    }

    /** A session for [how] ("service", "job" or "writer"); null if one is already running. [onEnd] runs when it stops. */
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
        // (a job scheduled by an older build asked for "battery not low" too: a phone plugged in nearly empty never ran it)
        if (Build.VERSION.SDK_INT >= 26) js.getPendingJob(JOB_ID)?.let { if (!it.isRequireBatteryNotLow) return }
        else if (Build.VERSION.SDK_INT >= 24 && js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NightJob::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_LINEAR)
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

    /** The battery is charging now (not only plugged in: Samsung's protection holds it at 80-85%, a weak charger may lose). */
    fun charging(ctx: Context): Boolean {
        val b = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return false
        val st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
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
    const val GAME_OPENED = "the game was opened (it writes its own lines)"
    /** Broadcasts from the game's process to a session in the writer's. */
    const val ACTION_GAME_OPENED = "com.timerpg.street.night.GAME_OPENED"
    const val ACTION_SWITCHED_OFF = "com.timerpg.street.night.SWITCHED_OFF"
    /** How long the overnight writer leaves the game's process alone after it went out of sight (it may come back). */
    const val GAME_GRACE_MS = 10 * 60_000L
    private const val CLOSED_MARK = "game-closed-by-writer"
    private const val JOB_ID = 7301
    private const val CHANNEL = "night"

    fun notification(ctx: Context, text: String, sub: String?): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Writing dialogue", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Writer mode and the overnight writer: the game's people get their lines written ahead"
                setShowBadge(false)
            })
        val stop = PendingIntent.getService(ctx, 1, Intent(ctx, NightService::class.java).setAction(NightService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // (a tap opens Writer mode's screen, not the game: opening the game stops the writer)
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, WriterActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
            .setContentIntent(open)
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
