package com.timerpg.street.shot

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.timerpg.street.BuildConfig
import java.io.File
import java.util.TimeZone

/** The game's recent events for crash reports ([Breadcrumbs]), also kept on disk a moment later for a native crash's report. */
object Crumbs {
    val ring = Breadcrumbs(100)
    private var dir: File? = null
    private val writer by lazy { Handler(HandlerThread("crumbs").apply { start() }.looper) }
    @Volatile private var flushing = false

    /** Before anything is recorded: last run's events move aside (they explain how it ended). */
    fun init(ctx: Context) {
        // once per process (a recreated activity must not move this run's own events aside)
        if (dir != null) return
        val d = Reports.dir(ctx)
        dir = d
        runCatching {
            File(d, PREV).delete(); File(d, NOW).renameTo(File(d, PREV))
            File(d, STATE_PREV).delete(); File(d, STATE_NOW).renameTo(File(d, STATE_PREV))
        }
    }

    /** Records an event (from any thread; rare events only, never per frame). */
    fun add(what: String) {
        ring.add(System.currentTimeMillis(), what)
        val d = dir ?: return
        if (flushing) return
        flushing = true
        // at most one write a second, off the game's threads
        writer.postDelayed({ flushing = false; runCatching { File(d, NOW).writeText(ring.serialize()) } }, 1000)
    }

    /** Keeps the game state on disk too (every few seconds, off the main thread): a native crash leaves no other picture. */
    fun saveState(state: Map<String, Any?>) {
        val d = dir ?: return
        val text = Json.write(state)
        writer.post { runCatching { File(d, STATE_NOW).writeText(text) } }
    }

    fun previous(): List<Pair<Long, String>> = dir?.let { d -> runCatching { Breadcrumbs.parse(File(d, PREV).readText()) }.getOrNull() } ?: emptyList()
    fun previousState(): String? = dir?.let { d -> runCatching { File(d, STATE_PREV).readText() }.getOrNull() }

    private const val NOW = "crumbs.txt"
    private const val PREV = "crumbs-prev.txt"
    private const val STATE_NOW = "state.json"
    private const val STATE_PREV = "state-prev.json"
}

/** What the game is showing now, for the screenshot JSON and crash reports (set by the game screen; read on any thread). */
object GameSnapshot {
    @Volatile var provider: (() -> Map<String, Any?>)? = null
    fun now(): Map<String, Any?>? = runCatching { provider?.invoke() }.getOrElse { mapOf("error" to "state not readable: ${it.javaClass.simpleName}") }
}

/** The app and phone facts every upload carries. */
object DeviceFacts {
    fun app(): Map<String, Any?> = linkedMapOf(
        "versionName" to BuildConfig.VERSION_NAME,
        "versionCode" to BuildConfig.VERSION_CODE,
        "git" to BuildConfig.GIT_SHA,
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
    )

    /** Heat and memory now (the AI panel's numbers). */
    fun device(ctx: Context): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            m["ramFreeMb"] = mi.availMem shr 20
            m["ramTotalMb"] = mi.totalMem shr 20
            m["lowMemory"] = mi.lowMemory
            m["gamePssMb"] = android.os.Debug.getPss() shr 10
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            m["thermalStatus"] = pm.currentThermalStatus
            m["thermalHeadroom10s"] = pm.getThermalHeadroom(10)
        }
        return m
    }

    fun screen(ctx: Context): Map<String, Any?> {
        val dm = ctx.resources.displayMetrics
        return linkedMapOf("widthPx" to dm.widthPixels, "heightPx" to dm.heightPixels, "density" to dm.density, "densityDpi" to dm.densityDpi)
    }
}

/**
 * Crash reports, written on the phone and sent to the repository's `reports` branch (crashes/<date>/<time>-<kind>.json)
 * on the next start: a Kotlin crash (written by the uncaught-exception handler before the process dies), and what
 * Android recorded about earlier exits (a native crash's tombstone, an ANR's trace, a kill for memory). A report is
 * deleted once it is uploaded; at most [KEEP] are kept.
 */
object Reports {
    const val KEEP = 20
    private const val TRACE_CAP = 1_000_000

    fun dir(ctx: Context): File = File(ctx.filesDir, "claude-reports").apply { mkdirs() }

    /** Pending reports, newest first. The file name holds the repository path (slashes as '~'). */
    fun pending(ctx: Context): List<File> = (dir(ctx).listFiles { f -> f.name.startsWith("crashes~") && f.name.endsWith(".json") } ?: emptyArray())
        .sortedByDescending { it.name }

    fun remotePath(f: File) = f.name.replace('~', '/')

    private fun write(ctx: Context, path: String, text: String) {
        val f = File(dir(ctx), path.replace('/', '~'))
        var out = f; var k = 2
        while (out.exists()) out = File(f.parentFile, f.name.removeSuffix(".json") + "-${k++}.json")
        out.writeText(text)
        prune(ctx)
    }

    fun prune(ctx: Context) { pending(ctx).drop(KEEP).forEach { it.delete() } }

    /** In the uncaught-exception handler: the report, written now (synchronously), before the process goes. */
    fun writeJvmCrash(ctx: Context, thread: Thread, e: Throwable) {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        ctx.getSharedPreferences("reports", Context.MODE_PRIVATE).edit().putLong("jvmT", now).commit()
        val state = linkedMapOf<String, Any?>()
        GameSnapshot.now()?.let { state.putAll(it) }
        runCatching { state["device"] = DeviceFacts.device(ctx) }
        val text = ShotRequests.crashPayload(
            "jvm", now, tz, DeviceFacts.app(),
            linkedMapOf("thread" to thread.name, "exception" to e.javaClass.name, "message" to e.message),
            e.stackTraceToString(), state, Crumbs.ring.snapshot(), logcat(),
        )
        write(ctx, ShotRequests.crashPath(now, tz, "jvm"), text)
    }

    /**
     * A failure the game caught and survived (the town's build, Filament): written as a report like a crash, so it
     * reaches Claude with its full stack, the game state and the breadcrumbs. [kind] goes into the file name.
     */
    fun writeCaught(ctx: Context, kind: String, e: Throwable) = runCatching {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val state = linkedMapOf<String, Any?>()
        GameSnapshot.now()?.let { state.putAll(it) }
        runCatching { state["device"] = DeviceFacts.device(ctx) }
        val text = ShotRequests.crashPayload(
            kind, now, tz, DeviceFacts.app(),
            linkedMapOf("thread" to Thread.currentThread().name, "exception" to e.javaClass.name, "message" to e.message,
                "cause" to rootCause(e).let { "${it.javaClass.name}: ${it.message}" }),
            e.stackTraceToString(), state, Crumbs.ring.snapshot(), logcat(),
        )
        write(ctx, ShotRequests.crashPath(now, tz, kind), text)
    }

    /** The innermost cause of [e]. */
    fun rootCause(e: Throwable): Throwable { var c = e; while (c.cause != null && c.cause !== c) c = c.cause!!; return c }

    /** One line for the player: what failed and, inside it, the real reason. */
    fun explain(e: Throwable): String {
        val r = rootCause(e)
        val inner = "${r.javaClass.simpleName}${r.message?.let { ": $it" } ?: ""}"
        return if (r === e) inner else "${e.message ?: e.javaClass.simpleName} ($inner)"
    }

    /** This app's last log lines (empty if logcat cannot be read). */
    private fun logcat(): List<String>? = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "300", "--pid=${android.os.Process.myPid()}"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.destroy()
        lines.takeLast(300)
    } catch (t: Throwable) { null }

    /**
     * On each start (off the main thread): Android's record of earlier exits since the last one handled (API 30+), a
     * report for each crash, native crash, ANR, kill for memory or for excessive resource use. [filamentDied]: the
     * last start of Filament never drew its first frames (its "starting" mark was still set).
     */
    fun scanExits(ctx: Context, filamentDied: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = ctx.getSharedPreferences("reports", Context.MODE_PRIVATE)
        val last = prefs.getLong("exitT", 0L)
        val jvmT = prefs.getLong("jvmT", 0L)
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 16) }.getOrNull() ?: return
        // (the first run with this feature: only the latest few, not the phone's whole history)
        val fresh = exits.filter { it.timestamp > last }.sortedBy { it.timestamp }.let { if (last == 0L) it.takeLast(3) else it }
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        val tz = TimeZone.getDefault()
        val prevCrumbs = Crumbs.previous()
        val prevState = Crumbs.previousState()
        for (x in fresh) {
            val kind = when (x.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "native"
                ApplicationExitInfo.REASON_ANR -> "anr"
                ApplicationExitInfo.REASON_CRASH -> "crash"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-use"
                else -> null
            } ?: continue
            // a Kotlin crash already has its own report (with its stack): Android's record of it adds nothing
            if (x.reason == ApplicationExitInfo.REASON_CRASH && kotlin.math.abs(x.timestamp - jvmT) < 30_000) continue
            val trace = runCatching {
                x.traceInputStream?.use { s ->
                    val b = s.readBytes()
                    b.copyOf(minOf(b.size, TRACE_CAP)).decodeToString() + if (b.size > TRACE_CAP) "\n[cut at 1 MB of ${b.size} bytes]" else ""
                }
            }.getOrNull()
            // the run just before this one is the one that ended last: its events and state explain it
            val isLatest = x.timestamp == newest
            val fields = linkedMapOf<String, Any?>(
                "reason" to x.reason, "reasonName" to kind, "description" to x.description, "status" to x.status,
                "importance" to x.importance, "pssKb" to x.pss, "rssKb" to x.rss, "pid" to x.pid, "processName" to x.processName,
                "filamentStartDied" to (isLatest && filamentDied),
                "breadcrumbsFrom" to if (isLatest) "the run that ended in this exit" else "not kept (an older exit)",
            )
            val state: Map<String, Any?>? = if (isLatest && prevState != null) runCatching { org.json.JSONObject(prevState); mapOf("lastSaved" to Json.Raw(prevState)) }.getOrNull() else null
            val text = ShotRequests.crashPayload(kind, x.timestamp, tz, DeviceFacts.app(), fields, trace, state,
                if (isLatest) prevCrumbs else emptyList(), null)
            write(ctx, ShotRequests.crashPath(x.timestamp, tz, kind), text)
        }
        prefs.edit().putLong("exitT", maxOf(last, newest)).apply()
    }
}
