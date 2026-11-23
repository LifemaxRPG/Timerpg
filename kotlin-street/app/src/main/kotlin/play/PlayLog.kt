package com.timerpg.street.play

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.File

/**
 * The playtest recorder (PLAYTEST.md): a session's timeline, one line a second, written as gzip JSONL off the render
 * thread to `play/<date>/<session>.jsonl.gz` on the `reports` branch, and the conversations to `talk/<date>/<session>.jsonl`.
 *
 * - The render thread hands each frame to [frame] (fixed arrays in [FrameAgg]: no allocation per frame).
 * - The recorder's own thread ("playlog", low priority) closes each second: the frame percentiles, what the game
 *   screen's [state] provider says (where, when, what the player is doing), the play patterns; every [POWER_EVERY] s the
 *   heat, battery and clocks, every [MEMORY_EVERY] s the memory; flushed to disk as a gzip member every [FLUSH_EVERY] s.
 * - Other systems report loading and streaming with [span] / [spanDone] and cold-start [phase]s, and anything rare with
 *   [event]; the "mark this" button calls [mark] (a system trace with it on Android 15+: [Traces]).
 *
 * A session is the game in front: it goes on across a short trip out (under [SESSION_GAP_MS]) and ends after a longer
 * one, or at the next start if the process died (its file is then closed as it was: every flushed member is whole).
 * Nothing here holds a token or a credential; files go up with [PlayUploads].
 */
object PlayLog {
    const val POWER_EVERY = 5
    const val MEMORY_EVERY = 5
    const val FLUSH_EVERY = 30
    const val PATTERNS_EVERY = 300
    const val SETTINGS_EVERY = 10
    const val ACCOUNTS_EVERY = 60
    const val SESSION_GAP_MS = 5 * 60_000L
    /** A frame this late (ms) while moving, with the town on screen, asks for a system trace (rate-limited). */
    const val SEVERE_MS = 250f

    private var app: Context? = null
    private var dir: File? = null
    private var handler: Handler? = null
    private var probes: Probes? = null
    private var traces: Traces? = null
    private val agg = FrameAgg()
    private val sec = Second()
    private val st = PlayState()
    private val line = JsonLine(2048)
    private var patterns = Patterns()
    @Volatile private var gz: GzipJsonl? = null
    private var talkFile: File? = null
    private var sessionId: String? = null
    private var sessionDate: String? = null
    private var sessionStart = 0L
    private var ticks = 0L
    @Volatile private var foreground = false
    private var lastSettings: String? = null
    private var lastAccounts: String? = null
    @Volatile private var severePending = false

    /** Fills the state once a second (the game screen sets it; called on the recorder's thread: read, don't write). */
    @Volatile var state: ((PlayState) -> Unit)? = null
    /** The settings in effect (quality, tuned values, the version), checked every [SETTINGS_EVERY] s, written when changed. */
    @Volatile var settings: (() -> Map<String, Any?>)? = null
    /** Whether the town is on screen and playing (severe spikes ask for traces only then). */
    @Volatile var playing = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("play", Context.MODE_PRIVATE)
    /** "Record my conversations": on by default in the owner's builds (BuildConfig.OWNER_BUILD), off otherwise. */
    fun recordTalk(ctx: Context): Boolean = prefs(ctx).getBoolean("talk", com.timerpg.street.BuildConfig.OWNER_BUILD)
    fun setRecordTalk(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("talk", on).apply(); event("setting", "recordTalk", if (on) 1 else 0) }
    @Volatile private var talkOn = false

    /** Once per process, from the game's activity (before anything reports a phase). */
    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val d = com.timerpg.street.shot.Reports.dir(a)
        dir = d
        talkOn = recordTalk(a)
        // an earlier process's open files: closed as they are (every flushed member is whole) and queued to send. Here,
        // before this start's upload pass is queued (it ran on the upload thread while this waited on the recorder's
        // low-priority one: a race the upload could win, leaving the last session's file for the start after)
        closeOrphans(d)
        val t = HandlerThread("playlog", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val h = Handler(t.looper)
        handler = h
        h.post {
            probes = Probes(a)
            traces = Traces(a, d) { kind, name, value, detail -> event(kind, name, value, detail) }
            begin()
        }
        phase("process.init")
    }

    /** Renames every "open-" file in [d] to its finished name (an earlier process's, which can write no more). */
    fun closeOrphans(d: File): Int {
        var n = 0
        (d.listFiles { f -> f.name.startsWith("open-") } ?: emptyArray()).forEach { f -> if (runCatching { f.renameTo(File(d, f.name.removePrefix("open-"))) }.getOrDefault(false)) n++ }
        return n
    }

    /** A session's file names: its first part `<kind>~<date>~<id>`, later parts (after a trip out of sight) `-p<n>`. */
    fun partName(kind: String, date: String, id: String, part: Int, ext: String): String =
        "$kind~$date~$id" + (if (part > 1) "-p$part" else "") + ext

    // ------------------------------------------------------------------ the session

    /**
     * The session's current part. A part is closed (and sent) as soon as the game goes out of sight: the session used
     * to be closed only [SESSION_GAP_MS] later, by which time Android has frozen the cached process (Android 14+) or
     * Writer mode has closed it, so the timeline went up only at the next start, if ever. Coming back within the gap
     * goes on in the same session in a new part; the last part's "partEnd" line has the session's patterns so far.
     */
    private var part = 1

    private fun begin() {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val id = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date(now)) + "-" + com.timerpg.street.BuildConfig.VERSION_CODE
        sessionId = id; sessionDate = date
        sessionStart = SystemClock.elapsedRealtime()
        ticks = 0
        patterns = Patterns()
        lastSettings = null; lastAccounts = null
        part = 1
        val g = GzipJsonl(File(d, "open-" + partName("play", date, id, 1, ".jsonl.gz")))
        gz = g
        talkFile = File(d, "open-" + partName("talk", date, id, 1, ".jsonl"))
        val head = linkedMapOf<String, Any?>("k" to "session", "t" to 0, "id" to id, "start" to TalkTurn.iso(now),
            "app" to com.timerpg.street.shot.DeviceFacts.app(), "screen" to runCatching { com.timerpg.street.shot.DeviceFacts.screen(app!!) }.getOrNull(),
            "traces" to (traces?.supported == true), "recordTalk" to talkOn, "format" to 1)
        g.add(com.timerpg.street.shot.Json.write(head) + "\n")
        g.flush()
        com.timerpg.street.shot.Crumbs.add("playtest session $id")
    }

    /** The session is over (out of sight for [SESSION_GAP_MS]): its part, if one is still open, is closed and sent. */
    private fun end() {
        if (gz != null) {
            line(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "patterns", "t" to now(), "final" to true, "v" to com.timerpg.street.shot.Json.Raw(patterns.json()))))
            line(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "end", "t" to now(), "seconds" to ticks)))
            finishPart()
            app?.let { PlayUploads.send(it) }
        }
        sessionId = null
    }

    /** The game went out of sight: this part ends now ("partEnd" with the seconds and the patterns so far) and is sent. */
    private fun closePart() {
        if (gz == null) return
        line(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "partEnd", "t" to now(), "part" to part, "seconds" to ticks,
            "patterns" to com.timerpg.street.shot.Json.Raw(patterns.json()))))
        finishPart()
        app?.let { PlayUploads.send(it) }
    }

    private fun finishPart() {
        val g = gz ?: return
        val d = dir ?: return
        runCatching { g.flush() }
        gz = null
        runCatching { g.file.renameTo(File(d, g.file.name.removePrefix("open-"))) }
        talkFile?.let { f -> if (f.isFile) runCatching { f.renameTo(File(d, f.name.removePrefix("open-"))) } }
        talkFile = null
    }

    /** Back in sight within the gap: the same session goes on in a new part. */
    private fun openPart() {
        val d = dir ?: return
        val id = sessionId ?: return
        val date = sessionDate ?: return
        part++
        val g = GzipJsonl(File(d, "open-" + partName("play", date, id, part, ".jsonl.gz")))
        gz = g
        talkFile = File(d, "open-" + partName("talk", date, id, part, ".jsonl"))
        g.add(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "part", "t" to now(), "id" to id, "part" to part,
            "start" to TalkTurn.iso(System.currentTimeMillis()), "format" to 1)) + "\n")
        g.flush()
        lastSettings = null; lastAccounts = null
    }

    private val endLater = Runnable { if (!foreground) end() }
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            val h = handler ?: return
            h.postAtTime(this, SystemClock.uptimeMillis() / 1000 * 1000 + 1000)
            runCatching { second() }
        }
    }

    /** The game came in front ([on]) or went out of sight. */
    fun foreground(on: Boolean) {
        val h = handler ?: return
        h.post {
            if (on == foreground) return@post
            foreground = on
            h.removeCallbacks(endLater)
            if (on) {
                if (sessionId == null) begin() else if (gz == null) openPart()
                event("foreground", "in", 1)
                h.removeCallbacks(tick); h.post(tick)
            } else {
                event("foreground", "out", 0)
                // (closed and sent now, while the process may still run: see [part])
                closePart()
                h.postDelayed(endLater, SESSION_GAP_MS)
            }
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime() - sessionStart

    private fun line(text: CharSequence) { gz?.add(if (text.endsWith("\n")) text else "$text\n") }

    /** Once a second on the recorder's thread. */
    private fun second() {
        val g = gz ?: return
        ticks++
        val t = now()
        agg.close(sec)
        st.clear()
        runCatching { state?.invoke(st) }
        patterns.second(st)
        g.add(Timeline.second(line, t, sec, st))
        val p = probes
        if (p != null && ticks % POWER_EVERY == 1L) g.add(p.power(line, t))
        if (p != null && ticks % MEMORY_EVERY == 3L) g.add(p.memory(line, t))
        if (ticks % SETTINGS_EVERY == 2L) settings?.let { s ->
            val text = runCatching { com.timerpg.street.shot.Json.write(s()) }.getOrNull()
            if (text != null && text != lastSettings) { lastSettings = text; line("{\"k\":\"settings\",\"t\":$t,\"v\":$text}") }
        }
        if (ticks % ACCOUNTS_EVERY == 4L) accounts(t)
        if (ticks % PATTERNS_EVERY == 0L) line(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "patterns", "t" to t, "v" to com.timerpg.street.shot.Json.Raw(patterns.json()))))
        if (severePending) { severePending = false; traces?.request("spike") }
        if (ticks % FLUSH_EVERY == 0L) runCatching { g.flush() }
    }

    /** The renderer's own accounts (GpuAccount, TownMemory), when they changed. */
    private fun accounts(t: Long) {
        val text = runCatching {
            "{\"gpu\":" + com.timerpg.street.shot.Json.quote(com.lifemax.rpg.feature.habits.presentation.scene3d.GpuAccount.line()) +
                ",\"town\":" + com.timerpg.street.shot.Json.quote(com.lifemax.rpg.feature.habits.presentation.scene3d.TownMemory.summary()) + "}"
        }.getOrNull() ?: return
        if (text == lastAccounts) return
        lastAccounts = text
        line("{\"k\":\"accounts\",\"t\":$t,\"v\":$text}")
    }

    // ------------------------------------------------------------------ the render thread

    /**
     * A frame just drawn (the render thread; allocates nothing): see [FrameAgg.frame]. A frame later than [SEVERE_MS]
     * while moving in the town asks for a system trace on the recorder's next second (rate-limited there).
     */
    fun frame(startedAt: Long, cpuMs: Float, gpuMs: Float, gpuFrameId: Long, inMotion: Boolean,
              draws: Int, triangles: Int, visible: Int, parts: LongArray?, ups: IntArray?, bytes: Long) {
        if (!foreground) return
        val iv = agg.frame(System.nanoTime(), startedAt, cpuMs, gpuMs, gpuFrameId, inMotion, draws, triangles, visible, parts, ups, bytes)
        if (iv > SEVERE_MS && inMotion && playing) severePending = true
    }

    /** A touch went down (the activity's dispatch; sampled for touch-to-frame latency). */
    fun touch() { if (foreground) agg.touch(System.nanoTime()) }

    // ------------------------------------------------------------------ events, spans, phases (any thread; rare)

    /** A rare event: {"k":kind,"t":..,"n":name,"v":value,"d":detail}. */
    fun event(kind: String, name: String, value: Long = 0, detail: String? = null) {
        val h = handler ?: return
        h.post {
            val j = JsonLine(160).begin().str("k", kind).long("t", now()).str("n", name)
            if (value != 0L) j.long("v", value)
            j.strIfSet("d", detail)
            line(j.end())
        }
    }

    /** Something counted in the play patterns (talks, doors, rides, trains, marks). */
    fun count(what: String) { handler?.post { patterns.count(what) } }

    /** Time since the process started (ms), for cold-start phases. */
    fun sinceProcessStart(): Long = SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()

    /** A cold-start phase reached: {"k":"phase","n":name,"ms":since the process started}. */
    fun phase(name: String, detail: String? = null) {
        val ms = sinceProcessStart()
        val h = handler ?: return
        h.post {
            val j = JsonLine(160).begin().str("k", "phase").long("t", now()).str("n", name).long("ms", ms)
            j.strIfSet("d", detail)
            line(j.end())
        }
    }

    /** A span begun: [Span.end] writes {"k":"span","n":name,"ms":..,"d":..}. For loading and streaming (request to ready). */
    fun span(name: String): Span = Span(name, SystemClock.elapsedRealtimeNanos())
    inline fun <T> span(name: String, block: () -> T): T { val s = span(name); try { return block() } finally { s.end() } }

    /** A span measured elsewhere ([ms] long, ended now). */
    fun spanDone(name: String, ms: Double, detail: String? = null) {
        val h = handler ?: return
        h.post {
            val j = JsonLine(200).begin().str("k", "span").long("t", now()).str("n", name).num("ms", ms, 1)
            j.strIfSet("d", detail)
            line(j.end())
        }
    }

    class Span internal constructor(val name: String, private val at: Long) {
        private var done = false
        fun end(detail: String? = null) {
            if (done) return
            done = true
            spanDone(name, (SystemClock.elapsedRealtimeNanos() - at) / 1e6, detail)
        }
    }

    /** onTrimMemory's [level]. */
    fun trim(level: Int) = event("trim", "level", level.toLong())

    /** The "mark this" button: an event with the state, a system trace (Android 15+), and [note] if typed. */
    fun mark(note: String? = null) {
        count("mark")
        val h = handler ?: return
        h.post {
            val snap = runCatching { com.timerpg.street.shot.GameSnapshot.now() }.getOrNull()
            line(com.timerpg.street.shot.Json.write(linkedMapOf("k" to "mark", "t" to now(), "note" to note, "game" to snap)))
            val asked = traces?.request("mark") ?: false
            if (!asked) event("trace", "mark", 0, if (traces?.supported == true) "not now (one already running or asked within 30 s)" else "needs Android 15")
            runCatching { gz?.flush() }
        }
    }

    /** The note typed for the last mark (the screenshot's sheet). */
    fun markNote(note: String?) { if (!note.isNullOrBlank()) event("markNote", "note", 0, note.take(500)) }

    // ------------------------------------------------------------------ the AI

    /** An NPC exchange for the `talk/` log (only with "Record my conversations" on); its timing goes in the timeline too. */
    fun talk(turn: TalkTurn) {
        if (turn.written) return
        turn.written = true
        val ctx = app ?: return
        val h = handler ?: return
        talkOn = recordTalk(ctx)
        h.post {
            // where and when, from the timeline's last second (this thread's own)
            if (turn.town == null) turn.town = st.town
            if (turn.year == 0) turn.year = st.year
            if (turn.hour.isNaN()) turn.hour = st.hour
            if (turn.room == null) turn.room = st.room
            patterns.count("talk:" + turn.input)
            val j = JsonLine(240).begin().str("k", "ai").long("t", now()).str("src", turn.source ?: "none")
            if (turn.firstTokenMs >= 0) j.long("firstMs", turn.firstTokenMs)
            if (turn.lastTokenMs >= 0) j.long("lastMs", turn.lastTokenMs)
            if (turn.totalMs >= 0) j.long("totalMs", turn.totalMs)
            j.intIfKnown("tok", turn.tokens).strIfSet("unit", turn.unit)
            if (turn.walkedAway) j.bool("away", true)
            line(j.end())
            if (!talkOn) return@post
            val f = talkFile ?: return@post
            val id = sessionId ?: return@post
            runCatching { f.appendText(turn.json(id) + "\n") }
        }
    }

    /** The speech recognizer: how long after the player stopped the words came, and its confidence (NaN: not given). */
    fun asr(by: String, latencyMs: Long, audioMs: Long, confidence: Float) {
        val h = handler ?: return
        h.post {
            val j = JsonLine(200).begin().str("k", "asr").long("t", now()).str("by", by).long("ms", latencyMs)
            if (audioMs > 0) j.long("audioMs", audioMs)
            if (!confidence.isNaN()) j.num("conf", confidence, 3)
            line(j.end())
        }
    }

    /** A voice line made: the first sound after [firstMs], [audioS] of speech made in [madeMs]. */
    fun tts(npcId: String, engine: String, firstMs: Long, audioS: Double, madeMs: Long, loadedFirst: Boolean) {
        val h = handler ?: return
        h.post {
            val j = JsonLine(200).begin().str("k", "tts").long("t", now()).str("npc", npcId).str("engine", engine).long("firstMs", firstMs)
                .num("audioS", audioS, 1).long("madeMs", madeMs)
            if (loadedFirst) j.bool("loaded", true)
            line(j.end())
            if (!talkOn) return@post
            val f = talkFile ?: return@post
            runCatching { f.appendText("{\"k\":\"tts\",\"t\":\"${TalkTurn.iso(System.currentTimeMillis())}\",\"npc\":${com.timerpg.street.shot.Json.quote(npcId)},\"engine\":\"$engine\",\"firstSoundMs\":$firstMs,\"madeMs\":$madeMs,\"audioS\":${Math.round(audioS * 10) / 10.0}}\n") }
        }
    }
}
