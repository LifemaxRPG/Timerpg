package com.timerpg.street.dialogue

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.timerpg.street.llm.Backends
import com.timerpg.street.llm.Dialogue
import com.timerpg.street.llm.LlmBackend
import com.timerpg.street.llm.LlmStats
import com.timerpg.street.llm.NativeLlm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where the dialogue AI is. */
enum class ModelState { STARTING, NO_ENGINE, NO_MODEL, COPYING, WAITING, LOADING, READY, UNLOADED, ERROR }

/** The last speed test: one reply for the first NPC, the cache bypassed. */
data class SpeedResult(
    val who: String,
    val intent: String,
    val reply: String,
    val passed: Boolean,
    val totalMs: Long,
    val stats: LlmStats,
    val error: String? = null,
)

/** Everything the AI chip and its panel show. */
data class AiStatus(
    val state: ModelState = ModelState.STARTING,
    val detail: String = "",
    /** A usable model file is in place, and its size. */
    val hasModel: Boolean = false,
    val modelMb: Long = 0,
    /** While copying: percent (-1 if the size is unknown) and megabytes so far. */
    val copyPercent: Int = -1,
    val copiedMb: Long = 0,
    val stats: LlmStats = LlmStats(),
    /** Lines written by the model so far, lines left as the template (the model's reply failed the check), lines queued. */
    val lines: Int = 0,
    val builtIn: Int = 0,
    val queued: Int = 0,
    /** Why pre-generation is paused (empty: it is not). */
    val paused: String = "",
    /** Set when pre-generation has been switched off for this session (the phone is too slow). */
    val pregenOff: String? = null,
    /** What the model is writing right now. */
    val working: String? = null,
    /** The model runs on the GPU; this phone and build have one; why the GPU was not used. */
    val runsOn: Int = 0,
    val hasGpu: Boolean = false,
    val hasNpu: Boolean = false,
    val gpuNote: String? = null,
    /** Which model is loaded (null: none): the light one for live replies in play, or the main one ([ModelRole]). */
    val role: ModelRole? = null,
    /** The light model is on the phone; the free memory each model's load needs on the chosen device (MB). */
    val hasLive: Boolean = false,
    val liveNeedMb: Long = 0,
    val bankNeedMb: Long = 0,
    /** The AI panel's switch: the main model answers in play too (for comparison). */
    val forceBank: Boolean = false,
    /** In play the light model is needed and not on the phone (the main one does not fit beside the town): the panel
     *  offers its download. */
    val needLive: Boolean = false,
    /** What the last load measured of the model's own memory (KV cache, recurrent state, compute; MB), and whether
     *  its cache is in 8 bits. */
    val workMb: Long = 0,
    val kvQ8: Boolean = false,
    val speedTesting: Boolean = false,
    val speed: SpeedResult? = null,
    /** How the model's work splits between devices (fewer splits: less of it falls back to the CPU). */
    val loadInfo: String? = null,
    /** The last reply the check turned down, and why (it was written again). */
    val rejected: String? = null,
    /** The bank of lines written ahead (this town, every year it has lines for). */
    val bank: ReplyCache.Bank = ReplyCache.Bank(),
    /** While the overnight writer runs: what it is writing and how far it is. */
    val night: NightProgress? = null,
)

/**
 * The overnight writer's progress: [stage] 1 the town's lines, 2 the player's own patterns (follow-ups, frequent
 * themes), 3 more ways of saying each line (variants), 4 other years, 0 nothing left; [done] of [total] in that stage.
 */
data class NightProgress(val stage: Int, val done: Int, val total: Int) {
    val label: String get() = when (stage) {
        1 -> "Writing dialogue: $done of $total"
        2 -> "What you ask about: $done of $total"
        3 -> "More ways to say it: $done of $total"
        4 -> "Other years: $done of $total"
        else -> "Dialogue bank complete"
    }
}

/** What the model wrote while the overnight writer ran (since [DialogueEngine.beginNight]). */
data class NightCount(
    val lines: Int = 0,
    val variants: Int = 0,
    val rejected: Int = 0,
    val genTokens: Long = 0,
    val genMs: Long = 0,
    val prefillTokens: Long = 0,
    val prefillMs: Long = 0,
) {
    val genPerSec: Float get() = if (genMs > 0) genTokens * 1000f / genMs else 0f
    val prefillPerSec: Float get() = if (prefillMs > 0) prefillTokens * 1000f / prefillMs else 0f
}

/** A line in the dialogue panel: the model's ([ai]) or the game's template; [final] once it will not change. */
data class Line(val text: String, val ai: Boolean, val final: Boolean, val note: String, val info: ReplyInfo? = null)

/**
 * How a final line came to be, for the timing breadcrumbs and the AI panel: [source] "ready" (written ahead: the bank, a
 * theme, a follow-up), "live" (the model wrote it now), "template" (the game's own line; [why]); [model] which model on
 * which device; [waitMs] from the question to the model starting on it, [firstTokenMs] from there to its first token
 * (the prompt read), [restMs] from the first token to the end, [tokens] written, [tries].
 */
data class ReplyInfo(val source: String, val model: String? = null, val waitMs: Long = 0, val firstTokenMs: Long = 0, val restMs: Long = 0,
                     val tokens: Int = 0, val tries: Int = 0, val why: String? = null) {
    fun label(): String = buildString {
        append(source)
        model?.let { append(" · ").append(it) }
        if (source != "ready" && (firstTokenMs > 0 || restMs > 0)) {
            if (waitMs > 50) append(" · waited %.1f s".format(java.util.Locale.US, waitMs / 1000.0))
            append(" · first token %.1f s · rest %.1f s (%d tokens%s)".format(java.util.Locale.US, firstTokenMs / 1000.0, restMs / 1000.0, tokens,
                if (tries > 1) ", $tries tries" else ""))
        }
        why?.let { append(" · ").append(it) }
    }
}

/**
 * NPC dialogue: the game's template lines at once, the model's lines when it has written them.
 *
 * One background worker thread (low priority) owns the [LlmBackend]: it loads the model, writes lines ahead of time
 * (pre-generation, nearest the player first, see [PregenQueue]) and answers the player's questions, which jump the queue
 * and stream their tokens to the screen. Every reply passes [Dialogue.validate] or the template is used; the outcome is
 * cached by prompt (in memory and in filesDir/dialogue-cache.json) so lines survive restarts and a rejected one is not
 * tried again and again. Nothing here blocks the calling thread: the public methods only take a short lock and post work.
 *
 * Works without a model or engine ([Backends.create] returning null): every line is then the template.
 */
class DialogueEngine private constructor(private val app: Context) {

    /** A question on screen: [first] at once, then [onLine] (on the main thread) as the reply comes in. */
    class Request internal constructor(val first: Line, internal val onLine: ((Line) -> Unit)?, val ready: Boolean = false) {
        @Volatile internal var live = onLine != null
        /** Stop receiving lines (the panel closed or another topic was picked). Main thread. */
        fun close() { live = false }
    }

    private class Target(val npc: Npc, val topic: Topic)

    /** A line being written. */
    private class Gen(val key: String, val target: Target, val bypass: Boolean = false, val speed: Boolean = false, val variant: Boolean = false,
                      val slot: String? = null) {
        val listeners = CopyOnWriteArrayList<Request>()
        @Volatile var cancelled = false
        @Volatile var timedOut = false
        @Volatile var shown = ""
        @Volatile var done = false
        /** Someone started waiting for it while it was being written ahead: the worker goes to full speed. */
        @Volatile var wanted = false
        /** When it was asked for (the wait before the model starts on it is part of the reply's time). */
        val createdAt = SystemClock.elapsedRealtime()
    }

    /** [slot]: what the line is for ([BankPlan.slotOf]), recorded with it so a changed fact sheet shows as stale. */
    private class Planned(val target: Target, val job: PregenQueue.Job, val slot: String)

    private val main = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    // guarded by lock
    private val commands = ArrayDeque<() -> Unit>()
    private var demand: Gen? = null
    private var running: Gen? = null
    private val pauses = HashSet<String>()
    private var queue = PregenQueue(intArrayOf(1, 1))
    private var backend: LlmBackend? = null

    @Volatile private var closed = false
    @Volatile private var copyCancel = false
    @Volatile private var planned: Map<String, Planned> = emptyMap()
    @Volatile private var pregenOff: String? = null
    @Volatile private var unloadedForMemory = false
    @Volatile private var foreground = true
    private val liftMemoryPause = Runnable { resume("memory") }
    /**
     * What holds the model's load itself (not only the writing ahead): the town being built ("town-build"). A load asked
     * for meanwhile waits ([loadWanted]) and starts when the last hold lifts ([releaseLoad]).
     */
    private val loadHolds = HashSet<String>().apply { holdAtStart?.let { add(it) } }
    @Volatile private var loadWanted = false
    /** Loads put off for want of free memory, in a row (given up after [MEMORY_TRIES]). */
    @Volatile private var memoryWaits = 0
    private val retryLoad = Runnable { command { loadModel() } }
    /** The model was unloaded under memory pressure this many times (after [MEMORY_UNLOADS], it waits for the Load button). */
    @Volatile private var memoryUnloads = 0
    @Volatile private var lastMemoryUnload = 0L
    /** The player is moving: no line is started ahead of time (one being written finishes). */
    @Volatile private var moving = false
    // worker only
    private var failures = 0
    private var timeouts = 0
    private var engineError: String? = null

    private val cache = ReplyCache(File(app.filesDir, "dialogue-cache.json")).apply { warn = { Log.w(TAG, it) } }

    // the overnight writer (com.timerpg.street.night): while [night], lines are written ahead with the game closed
    @Volatile private var night = false
    /** The overnight writer loaded the model itself (it lets it go again when it stops). */
    @Volatile private var nightLoaded = false
    /** Other years' lines (their dialogue.json shipped beside this town's), written after this year's variants. */
    @Volatile private var extraPlans: Map<String, Planned> = emptyMap()
    /** Lines given up on for this night (took too long): not tried again until the next. Under [lock]. */
    private val nightSkip = HashSet<String>()
    private val nightCount = java.util.concurrent.atomic.AtomicReference(NightCount())
    private val _status = MutableStateFlow(AiStatus())
    private val _data = MutableStateFlow<DialogueData?>(null)

    /** The AI's state and speed numbers. */
    val status: StateFlow<AiStatus> = _status
    /** The town's people and what they say (null until loaded, or if dialogue.json is missing or for another town). */
    val data: StateFlow<DialogueData?> = _data

    private val thread = Thread({ loop() }, "dialogue-llm").apply { isDaemon = true }

    init {
        // the memory breakdown: the dialogue model's weights and its own buffers (never let go for the others)
        com.timerpg.street.llm.ModelRoom.register(com.timerpg.street.llm.ModelRoom.Resident("dialogue",
            mb = { if (backend?.loaded == true) (loadedBytes + loadedWork) shr 20 else 0L },
            note = { "${loadedRole?.name?.lowercase() ?: "?"}, ${if (loadedCopied) "copied" else "mapped"}, work ${loadedWork shr 20} MB${if (_status.value.kvQ8) ", 8-bit KV" else ""}" }))
    }

    // ------------------------------------------------------------------------------------------------ the public side

    /**
     * Asks [npc] about [topic]. Returns at once with the line to show now (the cached line, or the template); if the model
     * can do better, it writes the reply (ahead of any pre-generation), streaming it to [onLine] on the main thread and
     * ending with a [Line.final] line: the model's if it passes the check, else the template.
     */
    fun ask(npc: Npc, topic: Topic, onLine: (Line) -> Unit): Request {
        val prompt = topic.prompt ?: return Request(template(topic, "built-in"), null)
        val key = keyOf(prompt)
        // small talk after a real conversation: what they would say about it next time (written overnight), once
        if (topic.intent == "chat") followUp(npc)?.let { return Request(Line(it, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "a follow-up")), null, ready = true) }
        // (a line written several ways: the next of them, so a repeat visit hears another)
        cache.get(key)?.let { e -> return Request(cache.next(key)?.let { Line(it, ai = true, final = true, note = "AI", info = ReplyInfo("ready")) } ?: lineOf(topic, e), null, ready = e.lines.isNotEmpty()) }
        if (closed || _status.value.state != ModelState.READY)
            return Request(template(topic, "built-in · " + (if (closed) "the AI is closed" else "no model ready (${_status.value.state.name.lowercase()})")), null)
        val req = Request(template(topic, "built-in · the AI is writing…", final = false), onLine)
        lock.withLock {
            val cur = running
            if (cur != null && cur.key == key && !cur.cancelled && !cur.speed && !cur.done) {
                // already being written ahead of time: listen in (and the worker speeds up for it)
                cur.listeners += req
                cur.wanted = true
                cur.shown.takeIf { it.isNotEmpty() }?.let { s -> post(req, Line(s, true, false, "AI · writing…")) }
            } else {
                dropDemandLocked()
                val g = Gen(key, Target(npc, topic))
                g.listeners += req
                demand = g
                queue.remove(key)
                // jump the queue: stop the line being written ahead of time (it goes back in the queue)
                if (cur != null && !cur.speed) cancelLocked(cur)
                wake.signal()
            }
        }
        return req
    }

    /**
     * The player said [said] to [npc] (out loud, written down by [com.timerpg.street.voice.PlayerVoice]): the model answers
     * those words, in the person's own fact sheet for the topic they are nearest ([topicFor]). The template of that topic
     * is the fallback, as for any line. Returns the topic answered and the request.
     */
    fun askFree(npc: Npc, said: String, remembered: List<String> = emptyList(), vec: FloatArray? = null, onLine: (Line) -> Unit): Pair<Topic, Request>? {
        // one of the player's frequent questions, answered ahead of time from this person's own facts: that line, if the
        // words are close enough to the theme (same kind of question, same subject: [Themes.match]); else live
        Themes.match(said, vec, steer.themes)?.let { th ->
            themeAsk(npc, th)?.let { (t, key) ->
                cache.next(key)?.let { line -> return topicFor(npc, said)!! to Request(Line(line, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "a theme you ask about")), null, ready = true) }
            }
        }
        val topic = topicFor(npc, said) ?: return null
        // words that plainly ask about one of their topics (their family, their work, the town...): that topic's line written
        // ahead answers at once, while nothing is generated; small talk and anything else goes to the model live
        if (topic.intent != "chat" && topic.prompt != null) {
            val k = keyOf(topic.prompt)
            if (cache.get(k)?.lines?.isNotEmpty() == true) cache.next(k)?.let { line ->
                return topic to Request(Line(line, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "their ${topic.intent} line")), null, ready = true)
            }
        }
        fun clean(s: String) = s.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim()
        val words = clean(said).take(400)
        val prompt = topic.prompt?.let { withMemories(withQuestion(it, words), remembered.map { m -> clean(m).take(600) }) }
        // the topic's fact sheet, but not its required words: a question of the player's own need not name them
        val asked = topic.copy(prompt = prompt, must = emptyList())
        return topic to ask(npc, asked, onLine)
    }

    /** Where the player is: building [index] (-1 between buildings) of [street]. Re-sorts the pre-generation queue. */
    fun setPlayer(street: Int, index: Int) {
        val changed = lock.withLock { queue.setPlayer(street, index) }
        if (changed) refresh()
    }

    /**
     * The walkable town: the player is at building [index] (-1 between buildings) of row [row] (its place in street.json
     * v2's rows), with [live] the rows round it nearest first. Re-sorts the pre-generation queue: this row's people, then
     * the live rows', then the rest of the town.
     */
    fun setPlayerRow(row: Int, index: Int, live: List<Int>) {
        val changed = lock.withLock { queue.setLive(live.filter { it != row }) or queue.setPlayer(row, index) }
        if (changed) refresh()
    }

    /** Which town's people: [dir] is the assets folder ("" the old crossroads' files, "town/gamma/" the walkable town's). */
    @Volatile var dataDir = startDir
        private set

    /**
     * Switches the town whose people talk (the AI panel's town switch, or the first start in the town): its dialogue.json
     * against its street.json, on the worker; the old town's lines written ahead are kept in the cache.
     */
    fun useTown(dir: String) {
        if (dir == dataDir && _data.value != null) return
        dataDir = dir
        command { loadData(); enqueueAll() }
    }

    /** Holds pre-generation (the corner turn, the app in the background, low memory); on-demand lines still come. */
    fun pause(reason: String, stopRunning: Boolean = true) {
        lock.withLock {
            if (!pauses.add(reason)) return
            // stop the line being written ahead of time, unless someone is waiting for it (or it may finish: [stopRunning])
            val cur = running
            if (stopRunning && cur != null && !cur.speed && cur.listeners.none { it.live }) cancelLocked(cur)
        }
        refresh()
    }

    fun resume(reason: String) {
        lock.withLock { if (pauses.remove(reason)) wake.signal() }
        refresh()
    }

    /**
     * Holds the model's load for [reason] (the town's build: its memory and every core go to the first view): a load
     * asked for meanwhile waits, and a load under way is not stopped.
     */
    fun holdLoad(reason: String) { lock.withLock { loadHolds += reason } }

    /** Lifts [holdLoad]'s [reason]; the last one lifted starts a load that waited. */
    fun releaseLoad(reason: String) {
        val go = lock.withLock { loadHolds.remove(reason) && loadHolds.isEmpty() && loadWanted }
        if (go) { com.timerpg.street.shot.Crumbs.add("dialogue model: the load waited for $reason, starting now"); command { loadModel() } }
    }

    /**
     * The player is moving ([on]) or has stood still a moment: while moving no line is started ahead of time (the one
     * being written finishes), so the cores and the memory bus are the frame's (CLAUDE.md: heavy work in bursts while
     * standing, not during heavy animation). Asked-for lines are not held.
     */
    fun setMoving(on: Boolean) {
        if (on == moving) return
        moving = on
        if (!on) lock.withLock { wake.signal() }
    }

    /**
     * The phone's free memory as [com.timerpg.street.llm.MemoryWatch] reads it: [level] 0 fine, 1 low (no writing
     * ahead), 2 critical (the model is let go even with the game in front: a killed game loses more than its lines).
     */
    fun onMemoryLevel(level: Int) {
        when (level) {
            0 -> {
                resume("memory")
                // (back after a memory unload only once things have been calm a while, and not again and again)
                if (unloadedForMemory && foreground && memoryUnloads < MEMORY_UNLOADS && android.os.SystemClock.elapsedRealtime() - lastMemoryUnload > RELOAD_AFTER_MS) {
                    unloadedForMemory = false
                    command { loadModel() }
                }
            }
            1 -> pause("memory")
            else -> {
                pause("memory")
                if (backend == null && !loadingNow) return
                lock.withLock { running?.let { cancelLocked(it) } ?: cancelBackendLocked() }
                command { unloadForMemory(force = true) }
            }
        }
    }
    @Volatile private var loadingNow = false

    /** The activity is visible ([on]) or not. Coming back reloads a model that was unloaded to free memory. */
    fun onForeground(on: Boolean) {
        foreground = on
        if (!on) {
            pause("background")
            // (the rotation of lines given out, and the player's counts, are kept)
            command { cache.save(); saveStats() }
            // a line the open panel was waiting for stops too (it goes back in the queue; the panel keeps the template)
            lock.withLock { running?.let { if (!it.speed) cancelLocked(it) } }
            return
        }
        resume("background")
        resume("memory")
        if (unloadedForMemory && memoryUnloads < MEMORY_UNLOADS) { unloadedForMemory = false; command { loadModel() } }
        // (back in play with the main model loaded, e.g. after writing overnight: the light one if that one is wanted now)
        else command { if (backend?.loaded == true && loadedRole != ModelRole.LIVE) loadModel() }
    }

    /** Memory pressure: pause pre-generation, and under real pressure let go of the model (it reloads on return). */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                pause("memory")
                // stop what runs, including a model still loading (it would only be unloaded again)
                lock.withLock { running?.let { cancelLocked(it) } ?: cancelBackendLocked() }
                // (critical with the game in front: let go of it too, or Android kills the game for it)
                command { unloadForMemory(force = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
            }
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                // a moment's pressure: hold the lines written ahead for a minute, then try again
                pause("memory")
                main.removeCallbacks(liftMemoryPause)
                main.postDelayed(liftMemoryPause, 60_000L)
            }
        }
    }

    /** Copies a model file the player picked (system file picker) into the app, then loads it. */
    fun importModel(uri: Uri) {
        copyCancel = false
        lock.withLock { running?.let { cancelLocked(it) } }
        command { doImport(uri) }
    }

    fun cancelImport() { copyCancel = true }

    /** The model on the phone's CPU, GPU (the Adreno) or NPU (Hexagon): saved, and the model reloads there. */
    fun setDevice(kind: Int) {
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putInt("device", kind).apply()
        gpuNote = null
        lock.withLock { running?.let { cancelLocked(it) } }
        command { closeBackend(); loadModel() }
    }

    /** The CPU's fast (repacked) kernels: off by default, see [com.timerpg.street.llm.NativeLlm.fastCpu]. */
    fun setFastCpu(on: Boolean) {
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putBoolean("repack", on).apply()
        lock.withLock { running?.let { cancelLocked(it) } }
        command { closeBackend(); loadModel() }
    }

    /** Told of every line written ahead of time (worker thread), so its voice can be made ahead too. */
    @Volatile var onWritten: ((Npc, String) -> Unit)? = null

    /** MTP speculative decoding: tokens to guess ahead (0 off; default 2), see [com.timerpg.street.llm.NativeLlm.mtp]. */
    val mtpWanted: Int get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getInt("mtp", 2)

    fun setMtp(n: Int) {
        app.getSharedPreferences("npu", Context.MODE_PRIVATE).edit().putInt("mtp", n).apply()
        lock.withLock { running?.let { cancelLocked(it) } }
        command { closeBackend(); loadModel() }
    }

    val fastCpuWanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("repack", false)

    /** The AI panel's switch: the main model answers in play too, where it fits (for comparing it with the light one). */
    val forceBankWanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("forceBank", false)

    fun setForceBank(on: Boolean) {
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putBoolean("forceBank", on).apply()
        _status.update { it.copy(forceBank = on) }
        lock.withLock { running?.let { cancelLocked(it) } }
        command { loadModel() }
    }

    /** The attention cache in 8 bits (the AI panel's switch; on by default: half the cache's memory). */
    val kvQ8Wanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("kvq8", true)

    fun setKvQ8(on: Boolean) {
        app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putBoolean("kvq8", on).apply()
        lock.withLock { running?.let { cancelLocked(it) } }
        command { closeBackend(); loadModel() }
    }

    /**
     * Where [role]'s measured working memory (its attention cache, state and compute buffers) is kept: by its cache setting
     * and context, and with [dev], by the device it ran on and the model file (its size), so each device's own measure is
     * used once known; without, the last load's on any device.
     */
    private fun workKey(role: ModelRole, dev: Int? = null) = "work_${role.name.lowercase()}_${if (kvQ8Wanted) "q8" else "f16"}_${contextFor(role)}" +
        (dev?.let { "_d${it}_m${modelSize(role)}" } ?: "")

    private fun modelSize(role: ModelRole): Long = if (role == ModelRole.LIVE) ModelFiles.LIVE_BYTES else ModelFiles.file(app).length()

    /**
     * [role]'s own memory beside its weights as measured on [dev] for this model (kept across sessions), else as measured
     * on any device (the CPU's measure of the same cache and context is close to the NPU's), else the flat guess.
     */
    private fun workWanted(role: ModelRole, dev: Int? = deviceFor(role, backend as? com.timerpg.street.llm.NativeLlm)): Long {
        val p = app.getSharedPreferences("ai", Context.MODE_PRIVATE)
        return listOfNotNull(dev?.let { p.getLong(workKey(role, it), 0L) }, p.getLong(workKey(role), 0L)).firstOrNull { it > 0 } ?: ModelRoles.WORK_BYTES
    }

    /** The context each model gets: the light one's live replies fit in [LIVE_CONTEXT_TOKENS] (fact sheet, memories and reply). */
    private fun contextFor(role: ModelRole) = if (role == ModelRole.LIVE) LIVE_CONTEXT_TOKENS else CONTEXT_TOKENS

    /** What [role]'s copy on its device needs (MB to bytes), with its measured working memory. */
    private fun copyNeed(role: ModelRole, bankFile: File): Long =
        ModelRoles.needBytes(if (role == ModelRole.LIVE) ModelFiles.LIVE_BYTES else bankFile.length(), true, workWanted(role))

    private fun describe(c: ModelRoles.Choice): String = when (c) {
        is ModelRoles.Load -> "${c.role.name.lowercase()} ${if (c.copied) "copied to its device" else "on the CPU from its file"}"
        is ModelRoles.Wait -> "wait"
        is ModelRoles.NeedLive -> "the light model is needed"
        ModelRoles.NoModel -> "no model"
    }

    /**
     * The device [role] loads on: the one chosen in the panel (-1: the NPU when there is one). In play the light model goes
     * on the NPU when there is one even with the GPU chosen (the S25 Ultra: 8.8 tok/s there against 4.2 on the GPU,
     * measured; the phone's owner had the GPU set from an earlier test); a CPU choice is kept.
     */
    private fun deviceFor(role: ModelRole, native: com.timerpg.street.llm.NativeLlm?): Int {
        // (the accelerators as the native library reports them, else as it last did on this phone: before the first load
        // it can report none, and the light model's copy was then wanted on the GPU of the old device setting)
        val prefs = app.getSharedPreferences("ai", Context.MODE_PRIVATE)
        val acc = native?.accelerators ?: 0
        if (acc != 0 && prefs.getInt("accelerators", 0) != acc) prefs.edit().putInt("accelerators", acc).apply()
        val npu = ((if (acc != 0) acc else prefs.getInt("accelerators", 0)) and 2 != 0) || _status.value.hasNpu
        val w = deviceWanted
        val d = if (w >= 0) w else if (npu) com.timerpg.street.llm.NativeLlm.NPU else com.timerpg.street.llm.NativeLlm.CPU
        return if (role == ModelRole.LIVE && d == com.timerpg.street.llm.NativeLlm.GPU && npu) com.timerpg.street.llm.NativeLlm.NPU else d
    }

    @Volatile private var loadedWork = 0L

    /** Which model is loaded ([ModelRole]); whether it was copied to its device (not mapped) and its file's size. */
    @Volatile private var loadedRole: ModelRole? = null
    @Volatile private var loadedCopied = false
    @Volatile private var loadedBytes = 0L

    /** The device chosen in the AI panel; -1 until one is: the NPU then when there is one (fastest on the S25 Ultra:
     *  8.8 tok/s and a 2.4 s line, against 4.1 tok/s on the CPU and 4.2 on the GPU, measured on the phone). */
    private val deviceWanted: Int get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getInt("device", -1)
    @Volatile private var gpuNote: String? = null

    /** The model running on the CPU only because its device's copy did not fit ([loadModel]); null: none. */
    @Volatile private var fallbackRole: ModelRole? = null
    @Volatile private var fallbackDevice = 0
    /** Since when free memory has stood above the device copy's need (elapsed ms; 0: not now). */
    private var roomSince = 0L

    /**
     * Every [DEVICE_RETRY_MS] while a model runs on the CPU for want of memory: not while a line is being written or the
     * player talks (or speaks, or the camera turns a corner), it checks the free memory against what the copy on its
     * device needs, and once that has held for [DEVICE_ROOM_MS] it loads the model again there (a breadcrumb either way).
     */
    private val deviceRetry: Runnable = Runnable {
        val role = fallbackRole
        if (closed || role == null || backend?.loaded != true) return@Runnable
        val busy = lock.withLock { running != null || demand != null || pauses.any { it == "talking" || it == "listening" || it == "turn" || it == "town-load" } } || loadingNow
        val dev = listOf("CPU", "GPU", "NPU").getOrElse(fallbackDevice) { "?" }
        if (busy) roomSince = 0L
        else {
            val free = memoryNow().first
            val need = copyNeed(role, ModelFiles.file(app))
            val now = android.os.SystemClock.elapsedRealtime()
            if (free >= need) {
                if (roomSince == 0L) {
                    roomSince = now
                    com.timerpg.street.shot.Crumbs.add("dialogue model: room for the $dev copy now (${free shr 20} MB free, ${need shr 20} MB needed); moving it if that holds")
                } else if (now - roomSince >= DEVICE_ROOM_MS) {
                    com.timerpg.street.shot.Crumbs.add("dialogue model: moving the ${role.name.lowercase()} model from the CPU to the $dev (${free shr 20} MB free, ${need shr 20} MB needed)")
                    fallbackRole = null
                    roomSince = 0L
                    command { closeBackend(); loadModel() }
                    return@Runnable
                }
            } else roomSince = 0L
        }
        main.postDelayed(deviceRetry, DEVICE_RETRY_MS)
    }

    /** Loads the model again (after an error, or after it was unloaded). */
    fun reload() = command { memoryUnloads = 0; memoryWaits = 0; loadModel() }

    /** Deletes the model file (it can be picked again). */
    fun removeModel() {
        lock.withLock { running?.let { cancelLocked(it) } }
        command {
            closeBackend()
            ModelFiles.file(app).delete()
            _status.update { it.copy(hasModel = false, modelMb = 0) }
            setState(ModelState.NO_MODEL, NO_MODEL_TEXT)
        }
    }

    /** Writes one reply for the first NPC, the cache bypassed, and shows the numbers ([AiStatus.speed]). */
    fun speedTest() {
        val first = planned.values.firstOrNull()?.target
        if (first == null) {
            _status.update { it.copy(speed = SpeedResult("", "", "", false, 0, LlmStats(), "There is no dialogue to test with.")) }
            return
        }
        _status.update { it.copy(speedTesting = true, speed = null) }
        lock.withLock {
            dropDemandLocked()
            demand = Gen(keyOf(first.topic.prompt!!), first, bypass = true, speed = true)
            running?.let { cancelLocked(it) }
            wake.signal()
        }
    }

    /** Stops the worker and frees the model (on the worker thread; this returns at once). */
    fun close() {
        closed = true
        copyCancel = true
        main.removeCallbacks(liftMemoryPause)
        main.removeCallbacks(retryLoad)
        main.removeCallbacks(deviceRetry)
        lock.withLock {
            running?.let { cancelLocked(it) } ?: cancelBackendLocked()
            wake.signal()
        }
    }

    // ------------------------------------------------------------------------------------------------ the overnight writer

    /**
     * The overnight writer starts ([com.timerpg.street.night.NightSession]): the phone is charging and the game is not in
     * front ([gameVisible] false). Lines are written ahead with no player to stay out of the way of: the background and
     * moving holds do not apply, and the order is the player's predicted need ([Steer], from what they talk about), not
     * the distance from where they stand. Other holds (memory, [pause] by the writer's own heat care) do apply. The
     * model is loaded if it is not (and let go again by [endNight]).
     */
    fun beginNight(gameVisible: Boolean) {
        if (night || closed) return
        night = true
        if (!gameVisible) foreground = false
        nightCount.set(NightCount())
        nightReady = false
        command {
            loadSteer()
            extraPlans = loadYears()
            val left = lock.withLock { progressLocked(all = true).let { it.total - it.done } }
            // (the bank is the main model's: a light model loaded for play is let go for it by loadModel)
            val bankIn = backend?.loaded == true && loadedRole == ModelRole.BANK
            nightLoaded = !bankIn
            // (a complete bank needs no model: the writer stops without loading it)
            if (left > 0 && !bankIn) { memoryWaits = 0; memoryUnloads = 0; loadModel() }
            nightReady = true
        }
        lock.withLock { wake.signal() }
        refresh()
    }

    /** The overnight writer stopped: back to the game's rules; [release] lets the model go if the writer loaded it. */
    fun endNight(release: Boolean) {
        if (!night) return
        night = false
        lock.withLock {
            running?.let { if (!it.speed && it.listeners.none { r -> r.live }) cancelLocked(it) }
            nightSkip.clear()
            wake.signal()
        }
        command {
            cache.save()
            if (release && nightLoaded && backend != null) {
                closeBackend()
                com.timerpg.street.shot.Crumbs.add("dialogue model: let go by the overnight writer")
                // the game in front loads it again its own way (after the town is built); otherwise when it comes back
                if (foreground) { unloadedForMemory = false; loadModel() }
                else { unloadedForMemory = true; setState(ModelState.UNLOADED, "Let go after writing overnight. It loads again when you come back (or tap Load).") }
            // (the game in front: the light model again if that is the one for play now)
            } else if (backend?.loaded == true) { if (foreground) loadModel() else enqueueAll() }
            nightLoaded = false
        }
        refresh()
    }

    /** The overnight writer is on. */
    val nightOn: Boolean get() = night

    /** Loads the model again for the overnight writer, if it is not loaded (after a memory unload, or a load put off). */
    fun nightReload() = command { if (night && (backend?.loaded != true || loadedRole != ModelRole.BANK) && !loadingNow) { memoryWaits = 0; loadModel() } }

    /** What has been written since [beginNight]. */
    fun nightCount(): NightCount = nightCount.get()

    /** Lines still to write tonight (every stage), or -1 before the night's plan is known. */
    fun nightLeft(): Int = lock.withLock {
        if (!nightReady || planned.isEmpty()) return -1
        val p = progressLocked(all = true)
        p.total - p.done + (if (running != null) 1 else 0)
    }
    @Volatile private var nightReady = false

    /** The other years shipped for this town: dialogue.json files in its folder's years/<year>/ (none yet, see CONVERSATION.md). */
    private fun loadYears(): Map<String, Planned> {
        val base = "${dataDir}years"
        val years = try { app.assets.list(base)?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        val out = LinkedHashMap<String, Planned>()
        for (y in years.sorted()) loadData("$base/$y/", plan = false)?.let { m -> for ((k, v) in m) if (k !in planned) out.putIfAbsent(k, v) }
        if (out.isNotEmpty()) com.timerpg.street.shot.Crumbs.add("overnight: ${out.size} lines of ${years.size} other years to write")
        return out
    }

    /** One line the player's own patterns ask for: a theme's answer or a follow-up, for one person. */
    private class Steered(val key: String, val target: Target, val slot: String)

    /**
     * The player's patterns, from the log (memories.json, player-stats.json): the [Steer], and the lines it asks for:
     * each person's follow-up to their last exchange, then every frequent theme asked of every person (most needed
     * first). Worker thread.
     */
    private fun loadSteer() {
        val d = _data.value ?: return
        val mem = NpcMemory()
        runCatching { File(app.filesDir, "memories.json").takeIf { it.isFile }?.let { mem.load(it.readText()) } }
        val st = steerStats()
        val s = Steer.build(d.npcs, d.town, mem.all(), st, System.currentTimeMillis())
        steer = s
        val people = s.order(d.npcs, { it.id }, { null })
        val out = ArrayList<Steered>()
        for (n in people) { val e = s.recent[n.id] ?: continue; followUpAsk(n, e)?.let { (t, k) -> out += Steered(k, Target(n, t), BankPlan.slotOf(d.town, d.year, n.id, "next")) } }
        for (th in s.frequent) for (n in people) themeAsk(n, th)?.let { (t, k) -> out += Steered(k, Target(n, t), BankPlan.slotOf(d.town, d.year, n.id, "theme:${th.id}")) }
        steered = out.distinctBy { it.key }
        if (s.themes.isNotEmpty()) com.timerpg.street.shot.Crumbs.add("player themes: ${s.themes.size} (${s.frequent.size} frequent), ${s.recent.size} follow-ups")
    }
    @Volatile private var steered: List<Steered> = emptyList()

    /** The player's patterns (themes, who they talk to) as last learned. */
    @Volatile var steer: Steer = Steer.EMPTY
        private set

    /** [npc]'s answer to a theme: their topic nearest it, the player's own words as the question (no required words). */
    private fun themeAsk(npc: Npc, theme: Themes.Theme): Pair<Topic, String>? {
        val topic = topicFor(npc, theme.exemplar) ?: return null
        val p = topic.prompt ?: return null
        val prompt = withQuestion(p, theme.exemplar.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim().take(400))
        return topic.copy(prompt = prompt, must = emptyList()) to keyOf(prompt)
    }

    /** What [npc] says the next time, about their last exchange [e]: their small talk with that memory in its facts. */
    private fun followUpAsk(npc: Npc, e: NpcMemory.Entry): Pair<Topic, String>? {
        val topic = npc.topics.firstOrNull { it.intent == "chat" && it.prompt != null } ?: return null
        val facts = NpcMemory.asFacts(listOf(e)).map { it.replace("<|", "<").replace("|>", ">").take(600) }
        val prompt = withMemories(withQuestion(topic.prompt!!, "Hello again."), facts)
        return topic.copy(prompt = prompt, must = emptyList()) to keyOf(prompt)
    }

    /**
     * Where the bank stands (under [lock]): the first stage with work left, or ([all]) every stage added together.
     */
    private fun progressLocked(all: Boolean = false): NightProgress {
        val ps = planned
        var baseDone = 0
        for (k in ps.keys) if (cache.get(k) != null || k in nightSkip) baseDone++
        val sj = steered
        var sDone = 0
        for (j in sj) if (cache.get(j.key) != null || j.key in nightSkip) sDone++
        var vDone = 0; var vTotal = 0
        for (k in ps.keys) {
            val e = cache.get(k) ?: continue
            if (e.lines.isEmpty() || k in nightSkip) continue
            vTotal += ReplyCache.VARIANTS
            vDone += if (cache.variantsDone(k)) ReplyCache.VARIANTS else e.lines.size
        }
        val ex = extraPlans
        var yDone = 0
        for (k in ex.keys) if (cache.get(k) != null || k in nightSkip) yDone++
        if (all) return NightProgress(0, baseDone + sDone + vDone + yDone, ps.size + sj.size + vTotal + ex.size)
        return when {
            baseDone < ps.size -> NightProgress(1, baseDone, ps.size)
            sDone < sj.size -> NightProgress(2, sDone, sj.size)
            vDone < vTotal -> NightProgress(3, vDone, vTotal)
            yDone < ex.size -> NightProgress(4, yDone, ex.size)
            else -> NightProgress(0, ps.size, ps.size)
        }
    }

    /**
     * The next line to write tonight, most needed first ([Steer]: the people the player talks to and their neighbours,
     * the topics they ask; ties nearest the player): every line of this town not written yet; the player's own patterns
     * (follow-ups, then the frequent themes for every person); more ways of saying each line (all of one prompt's in a
     * row: the model's prompt cache then makes them nearly free of prompt processing); then the other years. Under [lock].
     */
    private fun nightJobLocked(): Gen? {
        val s = steer
        val near = planned.values.sortedWith(compareBy<Planned>({ queue.rank(it.job) }, { it.job.order }))
        val ps = s.order(near, { it.target.npc.id }, { it.target.topic.intent })
        for (p in ps) { val k = p.job.key; if (cache.get(k) == null && k !in nightSkip) return Gen(k, p.target) }
        for (j in steered) if (cache.get(j.key) == null && j.key !in nightSkip) return Gen(j.key, j.target, slot = j.slot)
        for (p in ps) { val k = p.job.key; if (k !in nightSkip && cache.wantsVariant(k)) return Gen(k, p.target, variant = true) }
        for (p in extraPlans.values) { val k = p.job.key; if (cache.get(k) == null && k !in nightSkip) return Gen(k, p.target) }
        return null
    }

    private fun slotOf(key: String): String = (planned[key] ?: extraPlans[key])?.slot ?: ""

    // ------------------------------------------------------------------------------------------------ the player's log

    private val stats = PlayerStats()
    @Volatile private var statsLoaded = false
    private var statsDirty = 0
    private val statsFile = File(app.filesDir, "player-stats.json")

    private fun steerStats(): PlayerStats = synchronized(stats) {
        if (!statsLoaded) { statsLoaded = true; runCatching { if (statsFile.isFile) stats.load(statsFile.readText()) } }
        stats
    }

    /**
     * The player asked [npc] about [intent] (a tapped topic, or their own words answered from it), answered [how]:
     * "ready" (a line written ahead), "live" (the model wrote it then) or "template". Counts only (the words go to
     * [MemoryService.remember]); saved now and then on the worker.
     */
    fun record(npc: Npc, intent: String, how: String) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val save = synchronized(stats) { steerStats().record(npc.id, intent, how, day); ++statsDirty >= 5 }
        if (save) command { saveStats() }
    }

    private fun saveStats() {
        val text = synchronized(stats) { statsDirty = 0; stats.toJson() }
        runCatching { val tmp = File(statsFile.path + ".tmp"); tmp.writeText(text); if (!tmp.renameTo(statsFile)) { statsFile.delete(); tmp.renameTo(statsFile) } }
    }

    /** Answers by day (yyyy-MM-dd to ready, live, template), the last [n] days. */
    fun answerDays(n: Int = 7): List<Pair<String, IntArray>> = synchronized(stats) { steerStats().days.entries.toList().takeLast(n).map { it.key to it.value.copyOf() } }

    /** The follow-up [npc] has ready for the player, said once. */
    private fun followUp(npc: Npc): String? {
        val e = steer.recent[npc.id] ?: return null
        val (_, key) = followUpAsk(npc, e) ?: return null
        val line = cache.get(key)?.line ?: return null
        synchronized(stats) { if (!steerStats().followUpsUsed.add(key)) return null; statsDirty++ }
        return line
    }

    // ------------------------------------------------------------------------------------------------ the worker

    private fun start() = thread.start()

    private fun command(c: () -> Unit) {
        lock.withLock {
            commands.addLast(c)
            wake.signal()
        }
    }

    /** Blocks the worker until there is something to do: a command, a question, or (unless paused) a line to write ahead. */
    private fun next(): Any? = lock.withLock {
        while (!closed) {
            commands.removeFirstOrNull()?.let { return it }
            demand?.let { demand = null; running = it; return it }
            // (overnight, with the game out of sight, its background and moving holds do not apply, and the order is the
            // player's predicted need rather than the distance from where they stand: [nightJobLocked])
            val held = if (night) pauses.any { it != "background" } else pauses.isNotEmpty() || moving || pregenOff != null
            if (!held && backend?.loaded == true) {
                if (night) { nightJobLocked()?.let { running = it; return it } }
                else {
                    val j = queue.poll()
                    if (j != null) {
                        val p = planned[j.key]
                        if (p == null || cache.get(j.key) != null) continue
                        val g = Gen(j.key, p.target)
                        running = g
                        return g
                    }
                }
            }
            wake.await()
        }
        null
    }

    private fun loop() {
        priority(Process.THREAD_PRIORITY_BACKGROUND)
        safely("start") { startup() }
        while (true) {
            val task = try { next() } catch (e: InterruptedException) { null } ?: break
            @Suppress("UNCHECKED_CAST")
            when (task) {
                is Gen -> {
                    try {
                        safely("generate") { generate(task) }
                    } finally {
                        lock.withLock { if (running === task) running = null }
                        // whatever happened, whoever is waiting gets a last line
                        if (!task.done) task.finish(template(task.target.topic, "built-in"))
                        if (task.speed && _status.value.speedTesting) _status.update { it.copy(speedTesting = false) }
                        _status.update { it.copy(working = null) }
                        priority(Process.THREAD_PRIORITY_BACKGROUND)
                    }
                }
                is Function0<*> -> safely("command") { (task as () -> Unit)() }
            }
            refresh()
        }
        closeBackend()
    }

    private fun startup() {
        // (the lines of every town the game ships are kept: switching between them loses nothing written)
        val other = (if (dataDir.isEmpty()) TOWN_DIRS else listOf("")).filter { it != dataDir }.flatMap { d -> loadData(d, plan = false)?.values.orEmpty() }
        loadData()
        cache.load()
        if (planned.isNotEmpty()) {
            extraPlans = loadYears()
            loadSteer()
            val all = planned.values + other + extraPlans.values
            // the lines of every prompt the game has, the player's steered ones, and the stale lines of its people (kept:
            // their facts may come back)
            val slots = all.map { it.slot }.toHashSet() + steered.map { it.slot }
            cache.retain(all.map { it.job.key }.toHashSet() + steered.map { it.key }, slots)
            for (p in all) cache.setSlot(p.job.key, p.slot)
        }
        ModelFiles.cleanPartial(app)
        refresh()
        loadModel()
    }

    /**
     * dialogue.json, checked against street.json (same town and year), and every line the model could write, in the
     * folder [dir]. With [plan] (the default) it becomes the engine's town; otherwise only its plan is returned.
     */
    private fun loadData(dir: String = dataDir, plan: Boolean = true): Map<String, Planned>? {
        val d = try {
            DialogueData.parse(app.assets.open("${dir}dialogue.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "no dialogue in '$dir': $e")
            if (plan) { planned = emptyMap(); _data.value = null }
            return null
        }
        // the buildings as the street shows them (street.json is the renderer's source of truth)
        val spots = HashMap<String, PregenQueue.Spot>()
        var lengths = IntArray(0)
        try {
            val sj = JSONObject(app.assets.open("${dir}street.json").bufferedReader().use { it.readText() })
            if (sj.optString("town") != d.town || sj.optInt("year") != d.year) {
                Log.w(TAG, "dialogue.json is ${d.town} ${d.year} but street.json is ${sj.optString("town")} ${sj.optInt("year")}: no dialogue")
                if (plan) { planned = emptyMap(); _data.value = null }
                return null
            }
            val streets = sj.optJSONArray("streets")
            // the walkable town (street.json v2 with its graph, dialogue.json v2): the rows are the streets, the people
            // found along them by their row and index (the queue then writes ahead by rows round the player)
            val rows = if (dir.isNotEmpty() && d.version >= 2) sj.optJSONObject("graph")?.optJSONArray("rows") else null
            if (rows != null) {
                lengths = IntArray(rows.length())
                val rowIndex = HashMap<String, Int>()
                for (r in 0 until rows.length()) {
                    val ro = rows.getJSONObject(r)
                    rowIndex[ro.opt("id").toString()] = r
                    lengths[r] = ro.optJSONArray("buildings")?.length() ?: 0
                }
                for (n in d.npcs) { val r = rowIndex[n.row] ?: continue; spots.putIfAbsent(n.building, PregenQueue.Spot(r, n.rowIndex.coerceAtLeast(0))) }
            } else if (streets != null) {
                lengths = IntArray(streets.length())
                for (s in 0 until streets.length()) {
                    val b = streets.optJSONObject(s)?.optJSONArray("buildings") ?: continue
                    lengths[s] = b.length()
                    for (i in 0 until b.length()) spots.putIfAbsent(b.optString(i), PregenQueue.Spot(s, i))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "street.json unreadable, using dialogue.json's places: $e")
        }
        if (lengths.isEmpty()) {
            lengths = IntArray((d.npcs.maxOfOrNull { it.street } ?: 0).coerceIn(0, 16) + 1)
            for (n in d.npcs) if (n.street in lengths.indices) lengths[n.street] = maxOf(lengths[n.street], n.index + 1)
        }
        // one job per distinct prompt (the same person at their shop and at home says the same), at all its buildings
        val byKey = LinkedHashMap<String, Pair<Target, MutableList<PregenQueue.Spot>>>()
        var order = 0
        val orders = HashMap<String, Int>()
        for (n in d.npcs) for (t in n.topics) {
            val p = t.prompt ?: continue
            val key = keyOf(p)
            val spot = spots[n.building] ?: PregenQueue.Spot(n.street, n.index)
            val e = byKey.getOrPut(key) { orders[key] = order++; Target(n, t) to ArrayList() }
            if (spot !in e.second) e.second += spot
        }
        val plans = byKey.mapValues { (k, v) -> Planned(v.first, PregenQueue.Job(k, v.second, orders[k]!!), BankPlan.slotOf(d.town, d.year, v.first.npc.id, v.first.topic.intent)) }
        if (!plan) return plans
        planned = plans
        lock.withLock {
            val old = queue
            queue = PregenQueue(lengths).also { it.setPlayer(old.street.coerceAtMost(lengths.size - 1).coerceAtLeast(0), old.position) }
        }
        _data.value = d
        return plans
    }

    private fun loadModel() {
        if (closed) return
        val holds = lock.withLock { loadHolds.sorted().joinToString() }
        // held (the town is being built): the load waits for the hold to lift ([releaseLoad])
        if (backend?.loaded != true && holds.isNotEmpty()) {
            lock.withLock { loadWanted = true }
            return setState(ModelState.WAITING, "Waiting for the town to be built before loading the model ($holds).")
        }
        lock.withLock { loadWanted = false }
        main.removeCallbacks(retryLoad)
        var b = backend ?: createBackend() ?: return setState(ModelState.NO_ENGINE, noEngineText())
        val nl = b as? com.timerpg.street.llm.NativeLlm
        // the device chosen in the panel; -1: the NPU when there is one
        val dev = nl?.let { n -> deviceWanted.let { if (it >= 0) it else if (n.accelerators and 2 != 0) com.timerpg.street.llm.NativeLlm.NPU else com.timerpg.street.llm.NativeLlm.CPU } }
            ?: com.timerpg.street.llm.NativeLlm.CPU
        val onNpu = nl != null && dev == com.timerpg.street.llm.NativeLlm.NPU
        // on the NPU or the GPU (or the CPU's repacked kernels) the whole model is copied into memory the system cannot
        // reclaim; from the file's mapping, about half of it has to fit
        val accelerated = nl != null && (dev != com.timerpg.street.llm.NativeLlm.CPU || fastCpuWanted)
        // the main model's file: on the NPU, the Q4_0 copy when the tuner found it faster there (and it is downloaded);
        // with MTP on and its build downloaded, that one (the same weights plus the prediction head)
        val q40 = java.io.File(ModelFiles.dir(app), ModelFiles.NPU_Q4_0)
        val wantsQ40 = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getString("model", null) == "q4_0"
        val mtpFile = java.io.File(ModelFiles.dir(app), ModelFiles.MTP)
        val useMtp = mtpWanted > 0 && mtpFile.length() == ModelFiles.MTP_BYTES && !(wantsQ40 && onNpu && q40.length() == 5_379_417_312L)
        val bankFile = if (wantsQ40 && onNpu && q40.length() == 5_379_417_312L) q40 else if (useMtp) mtpFile else ModelFiles.file(app)
        val liveFile = ModelFiles.live(app)
        val bankOk = ModelFiles.problem(bankFile) == null
        val liveOk = liveFile.length() == ModelFiles.LIVE_BYTES && ModelFiles.problem(liveFile) == null
        fun choose(): ModelRoles.Choice {
            val (avail, low) = memoryNow()
            // a model already loaded gives its copy back if it is let go: counted as free when deciding what goes in its place
            val held = if (b.loaded && loadedCopied) loadedBytes else 0L
            return ModelRoles.pick(ModelRoles.Input(
                foreground = foreground, sceneBuilt = holds.isEmpty(), freeBytes = avail + held, lowMemory = low && held == 0L,
                bankBytes = if (bankOk) bankFile.length() else 0L, liveBytes = if (liveOk) liveFile.length() else 0L,
                accelerated = accelerated, forceBank = forceBankWanted, writing = night,
                bankWorkBytes = workWanted(ModelRole.BANK), liveWorkBytes = workWanted(ModelRole.LIVE),
            ))
        }
        _status.update { it.copy(hasLive = liveOk, forceBank = forceBankWanted, needLive = false,
            liveNeedMb = ModelRoles.needBytes(ModelFiles.LIVE_BYTES, accelerated) shr 20, bankNeedMb = ModelRoles.needBytes(bankFile.length().takeIf { l -> l > 0 } ?: 5_680_522_464L, accelerated) shr 20) }
        var choice = choose()
        if (b.loaded) {
            val want = (choice as? ModelRoles.Load)?.role
            if (want == null || want == loadedRole) {
                // (a model that waits for the town is kept: the load after the build looks again)
                if (holds.isNotEmpty()) lock.withLock { loadWanted = true }
                setState(ModelState.READY, readyText()); enqueueAll(); return
            }
            // the other model is wanted now: this one goes first (both do not fit)
            com.timerpg.street.shot.Crumbs.add("dialogue model: ${loadedRole?.name?.lowercase()} model let go for the ${want.name.lowercase()} one (${com.timerpg.street.llm.Ram.freeMb(app)} MB free)")
            closeBackend()
            b = createBackend() ?: return setState(ModelState.NO_ENGINE, noEngineText())
            choice = choose()
        }
        // the copy on the NPU just short of room: the speech and memory models that can go (they load again when there is
        // room, and the phone's recognizer hears the player meanwhile) are let go, and the choice made again
        val wantRole = if (liveOk && !forceBankWanted && foreground && !night) ModelRole.LIVE else ModelRole.BANK
        if (accelerated && foreground && !night && (choice is ModelRoles.Wait || choice is ModelRoles.NeedLive || (choice as? ModelRoles.Load)?.copied == false)) {
            val (avail, _) = memoryNow()
            val short = (copyNeed(wantRole, bankFile) - avail) shr 20
            val droppable = com.timerpg.street.llm.ModelRoom.droppableMb()
            if (short in 1..(droppable + 100)) {
                val (freed, _) = com.timerpg.street.llm.ModelRoom.dropFor("the ${if (wantRole == ModelRole.LIVE) "light" else "main"} model's copy ($short MB short)")
                if (freed > 0) {
                    try { Thread.sleep(800) } catch (e: InterruptedException) { }
                    choice = choose()
                    com.timerpg.street.shot.Crumbs.add("dialogue model: after letting go of $freed MB: ${memoryNow().first shr 20} MB free, " +
                        "${copyNeed(wantRole, bankFile) shr 20} MB needed for the copy: ${describe(choice)}")
                }
            }
        }
        (choice as? ModelRoles.NeedLive)?.let { n ->
            com.timerpg.street.shot.Crumbs.add("dialogue model: none in play: ${n.why}")
            _status.update { it.copy(needLive = true, hasModel = bankOk) }
            return setState(ModelState.NO_MODEL, n.why + " People use their lines written ahead and their built-in ones until it is downloaded (AI panel: Download the light model).")
        }
        val role = (choice as? ModelRoles.Load)?.role ?: (if (bankOk || !liveOk) ModelRole.BANK else ModelRole.LIVE)
        val f = if (role == ModelRole.LIVE) liveFile else bankFile
        val problem = if (choice == ModelRoles.NoModel) ModelFiles.problem(bankFile) ?: ModelFiles.MISSING else ModelFiles.problem(f)
        _status.update { it.copy(hasModel = problem == null, modelMb = if (f.isFile) f.length() shr 20 else 0) }
        if (problem == ModelFiles.MISSING) return setState(ModelState.NO_MODEL, NO_MODEL_TEXT)
        if (problem != null) return setState(ModelState.ERROR, problem)
        // the NPU/GPU (or repacked CPU) copy does not fit, but the CPU reading the mapped file does: run there now rather
        // than wait (the phone's owner saw "4067 MB free, 6617 needed" on the NPU while the mapped CPU load needed ~3.6 GB)
        val cpuFallback = accelerated && (choice as? ModelRoles.Load)?.copied == false
        if (choice is ModelRoles.Load) gpuNote = null
        val wantDev = deviceFor(role, native = b as? com.timerpg.street.llm.NativeLlm)
        if (cpuFallback) {
            val devName = listOf("CPU", "GPU", "NPU").getOrElse(wantDev) { "?" }
            gpuNote = "Not enough free memory for the $devName copy of the ${if (role == ModelRole.LIVE) "light" else "main"} model right now " +
                "(${memoryNow().first shr 20} MB free, ${copyNeed(role, bankFile) shr 20} MB needed), so it runs on the CPU from the file " +
                "(it moves to the $devName once there is room: checked every ${DEVICE_RETRY_MS / 1000} s)."
            com.timerpg.street.shot.Crumbs.add("dialogue model: the ${role.name.lowercase()} model on the CPU from its file: its $devName copy needs " +
                "${copyNeed(role, bankFile) shr 20} MB, ${memoryNow().first shr 20} MB free (${com.timerpg.street.llm.ModelRoom.residents().joinToString { "${it.first} ${it.second} MB" }.ifEmpty { "no other model" }} resident; device setting ${deviceWanted})")
        }
        (choice as? ModelRoles.Wait)?.let { w ->
            val why = w.why
            // not now: again in a while (the town's last uploads, another app's memory being let go), then give up
            if (++memoryWaits <= MEMORY_TRIES) {
                com.timerpg.street.shot.Crumbs.add("dialogue model: load put off ($memoryWaits of $MEMORY_TRIES): $why")
                main.removeCallbacks(retryLoad); main.postDelayed(retryLoad, MEMORY_RETRY_MS)
                return setState(ModelState.WAITING, "$why Waiting for memory to free up (try ${memoryWaits} of $MEMORY_TRIES).")
            }
            memoryWaits = 0
            return setState(ModelState.ERROR, "$why Close other apps and tap Retry.")
        }
        memoryWaits = 0
        setState(ModelState.LOADING, "Loading the model (${f.length() shr 20} MB)…")
        com.timerpg.street.shot.Crumbs.add("dialogue model: loading the ${role.name.lowercase()} model with ${com.timerpg.street.llm.Ram.freeMb(app)} MB free")
        loadingNow = true
        var thrown: String? = null
        val native = b as? com.timerpg.street.llm.NativeLlm
        native?.device = if (cpuFallback) com.timerpg.street.llm.NativeLlm.CPU else wantDev
        if (!cpuFallback && wantDev != deviceWanted && deviceWanted == com.timerpg.street.llm.NativeLlm.GPU) {
            gpuNote = "The light model answers on the NPU in play (about twice the GPU's speed on the S25 Ultra); the GPU setting applies to the main model."
            com.timerpg.street.shot.Crumbs.add("dialogue model: the light model on the NPU, not the GPU of the device setting")
        }
        // the attention cache in 8 bits (half its memory); a load that cannot have it keeps 16
        native?.kvQ8 = kvQ8Wanted
        // (repacked CPU kernels copy the weights too: not when falling back for memory)
        native?.fastCpu = fastCpuWanted && !cpuFallback
        val mtpSet = app.getSharedPreferences("npu", Context.MODE_PRIVATE).contains("mtp")
        // (MTP is the main model's own build: the light one runs without it)
        native?.mtp = if (role == ModelRole.BANK && useMtp && (mtpSet || native?.device == com.timerpg.street.llm.NativeLlm.NPU)) mtpWanted else 0
        // loading holds the AI lane: speech, memory and the voices do not load beside it (their memory added to a load's
        // is what took the phone down)
        var ok = com.timerpg.street.llm.AiLane.run("dialogue") {
            try { b.load(f.path, 0, contextFor(role)) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
        }
        if (!ok && native != null && native.device != com.timerpg.street.llm.NativeLlm.CPU && !closed) {
            // the GPU or NPU could not take it: the CPU then (and say so)
            val which = if (native.device == com.timerpg.street.llm.NativeLlm.NPU) "NPU" else "GPU"
            native.device = com.timerpg.street.llm.NativeLlm.CPU
            gpuNote = "The $which could not load the model (${native.error ?: "unknown"}); it runs on the CPU."
            ok = com.timerpg.street.llm.AiLane.run("dialogue") {
                try { b.load(f.path, 0, contextFor(role)) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
            }
        }
        loadingNow = false
        val acc = native?.accelerators ?: 0
        com.timerpg.street.llm.AiLane.dialogueOffCpu = (native?.runsOn ?: 0) != 0
        _status.update { it.copy(runsOn = native?.runsOn ?: 0, hasGpu = acc and 1 != 0, hasNpu = acc and 2 != 0, gpuNote = gpuNote, loadInfo = native?.loadInfo) }
        if (closed) return
        if (!ok || !b.loaded) {
            val why = (try { b.error } catch (t: Throwable) { null }) ?: thrown ?: "unknown error"
            closeBackend()
            return setState(ModelState.ERROR, "Could not load the model: $why")
        }
        failures = 0
        timeouts = 0
        pregenOff = null
        stats(b)
        loadedRole = role
        loadedCopied = native != null && (native.runsOn != com.timerpg.street.llm.NativeLlm.CPU || native.fastCpu)
        loadedBytes = f.length()
        // what this model's cache and buffers really took: the next choice for it counts that, not a guess
        val m = native?.loadMem
        if (native != null && native.workBytes > 0) {
            app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putLong(workKey(role), native.workBytes).apply()
            loadedWork = native.workBytes
        } else loadedWork = 0
        if (native != null && native.workBytes > 0)
            app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putLong(workKey(role, native.runsOn), native.workBytes).apply()
        // on the CPU only for want of memory: look again every so often and move it to its device once there is room
        fallbackRole = if (cpuFallback && native?.runsOn == com.timerpg.street.llm.NativeLlm.CPU) role else null
        fallbackDevice = wantDev
        roomSince = 0L
        main.removeCallbacks(deviceRetry)
        if (fallbackRole != null) {
            main.postDelayed(deviceRetry, DEVICE_RETRY_MS)
            com.timerpg.street.shot.Crumbs.add("dialogue model: on the CPU for want of memory; looking for room on the ${listOf("CPU", "GPU", "NPU").getOrElse(wantDev) { "?" }} every ${DEVICE_RETRY_MS / 1000} s")
        }
        if (m != null) com.timerpg.street.shot.Crumbs.add("dialogue model: ${role.name.lowercase()} on ${listOf("CPU", "GPU", "NPU").getOrElse(native.runsOn) { "?" }} " +
            "in ${b.stats.loadMs} ms with ${com.timerpg.street.llm.Ram.freeMb(app)} MB free after: weights %.0f MB mapped + %.0f copied, KV %.0f (%s), state %.0f, compute %.0f; context ${contextFor(role)}"
                .format(java.util.Locale.US, m[0], m[1], m[2], if (native.kvQ8Used) "8-bit" else "16-bit", m[3], m[4]))
        _status.update { it.copy(role = role, workMb = loadedWork shr 20, kvQ8 = native?.kvQ8Used == true) }
        // the light model answers in play but does not write the bank ahead: the main one does that (overnight, or in
        // play when it is the one that fits); asked-for lines are not held by this
        if (role == ModelRole.LIVE) pause(LIVE_PAUSE) else resume(LIVE_PAUSE)
        setState(ModelState.READY, readyText())
        enqueueAll()
    }

    /** "light model on the NPU" and so on: which model wrote a line, on which device. */
    private fun modelLabel(b: LlmBackend): String {
        val dev = listOf("CPU", "GPU", "NPU").getOrElse((b as? NativeLlm)?.runsOn ?: 0) { "?" }
        return "${if (loadedRole == ModelRole.LIVE) "light" else "main"} model on the $dev"
    }

    private fun readyText() = if (loadedRole == ModelRole.LIVE) LIVE_READY_TEXT else READY_TEXT

    private fun noEngineText() = engineError?.let { "$it People use their built-in lines." }
        ?: "This build has no AI engine, so people use their built-in lines."

    private fun createBackend(): LlmBackend? {
        val b = try {
            Backends.create()
        } catch (t: Throwable) {
            // e.g. UnsatisfiedLinkError: the native library is not in this APK
            engineError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "no AI engine: $t")
            null
        } ?: return null
        lock.withLock { backend = b }
        return b
    }

    private fun closeBackend() {
        val b = lock.withLock {
            val b = backend
            backend = null
            queue.clear()
            b
        } ?: return
        _status.update { it.copy(role = null) }
        safely("close") { b.close() }
    }

    /**
     * The free memory Android counts (availMem) and its low-memory flag, for [ModelRoles.pick] (which says what a load
     * needs beside the game: [ModelRoles.needBytes]). Unreadable: plenty, as before.
     */
    private fun memoryNow(): Pair<Long, Boolean> = try {
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        if (am == null) Long.MAX_VALUE / 4 to false else { am.getMemoryInfo(mi); mi.availMem to mi.lowMemory }
    } catch (e: Exception) { Long.MAX_VALUE / 4 to false }

    /** Lets the model go to free memory: in the background, or ([force]) with the game in front under critical pressure. */
    private fun unloadForMemory(force: Boolean = false) {
        // the player came back before this ran: keep the model
        if (backend == null || (foreground && !force)) return
        if (force) {
            memoryUnloads++
            lastMemoryUnload = android.os.SystemClock.elapsedRealtime()
            com.timerpg.street.shot.Crumbs.add("dialogue model: unloaded, memory critical (${com.timerpg.street.llm.Ram.freeMb(app)} MB free; time $memoryUnloads)")
        }
        closeBackend()
        unloadedForMemory = true
        setState(ModelState.UNLOADED, "The model was unloaded to free memory. It loads again when you come back (or tap Load).")
    }

    private fun doImport(uri: Uri) {
        val before = _status.value
        val wasReady = backend?.loaded == true
        setState(ModelState.COPYING, "Copying the model into the game…")
        _status.update { it.copy(copyPercent = 0, copiedMb = 0) }
        val r = ModelFiles.copy(app, uri, cancelled = { copyCancel || closed }) { done, total ->
            _status.update { it.copy(copyPercent = if (total > 0) (done * 100 / total).toInt() else -1, copiedMb = done shr 20) }
        }
        _status.update { it.copy(copyPercent = -1) }
        when (r) {
            is ModelFiles.Copy.Failed -> when {
                wasReady && backend?.loaded == true -> setState(ModelState.READY, "${r.reason} The current model is still in use.")
                r.cancelled -> setState(before.state, "Copy cancelled.")
                else -> setState(ModelState.ERROR, r.reason)
            }
            is ModelFiles.Copy.Done -> {
                closeBackend()
                // the working model is kept aside until the new one has loaded: a file that is a GGUF but not a model
                // the game can use must not cost the player the one they had
                val cur = ModelFiles.file(app)
                val old = File(cur.path + ".old")
                old.delete()
                val hadOld = cur.isFile && cur.renameTo(old)
                if (!ModelFiles.install(r.part, cur)) {
                    if (hadOld) old.renameTo(cur)
                    loadModel()
                    return setState(if (backend?.loaded == true) ModelState.READY else ModelState.ERROR, "Could not save the model file.")
                }
                loadModel()
                if (backend?.loaded == true) {
                    old.delete()
                    // a new model gets its own try at the lines the old one failed
                    cache.dropTemplates()
                    cache.save()
                    enqueueAll()
                } else if (hadOld) {
                    val why = _status.value.detail
                    cur.delete()
                    old.renameTo(cur)
                    loadModel()
                    setState(_status.value.state, "That file could not be used ($why). The previous model is back.")
                }
            }
        }
    }

    private fun enqueueAll() {
        lock.withLock {
            for ((key, p) in planned) if (cache.get(key) == null && running?.key != key && demand?.key != key) queue.add(p.job)
            wake.signal()
        }
    }

    private enum class End { OK, REJECTED, TIMEOUT, CANCELLED, ERROR }

    /** Writes one line (worker thread). */
    private fun generate(g: Gen) {
        val npc = g.target.npc
        val topic = g.target.topic
        val prompt = topic.prompt ?: return g.finish(template(topic, "built-in"))
        if (!g.bypass && !g.variant) cache.get(g.key)?.let { return g.finish(lineOf(topic, it)) }
        val b = backend
        if (b == null || !b.loaded) {
            g.finish(template(topic, "built-in · the AI is not ready"))
            if (g.speed) _status.update { it.copy(speedTesting = false, speed = SpeedResult(npc.name, topic.intent, "", false, 0, LlmStats(), "The model is not loaded.")) }
            return
        }
        // someone is waiting: full speed; ahead of time: stay out of the game's way
        // (overnight with the game closed there is nothing to stay out of the way of)
        var raised = g.speed || g.listeners.isNotEmpty() || (night && !foreground)
        priority(if (raised) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND)
        _status.update { it.copy(working = if (g.speed) "speed test (${npc.name})" else "${npc.name} · ${topic.intent}") }
        val t0 = SystemClock.elapsedRealtime()
        var end = End.REJECTED
        var text: String? = null
        var lastRaw = ""
        var error: String? = null
        // when the model first wrote (the prompt read), and the tries made
        var firstAt = 0L
        var tries = 0
        for (attempt in 1..MAX_TRIES) {
            tries = attempt
            if (g.cancelled || closed) { end = End.CANCELLED; break }
            if (attempt > 1) { g.shown = ""; g.send(template(topic, "built-in · the AI is trying again…", final = false)) }
            if (g.wanted && !raised) { raised = true; priority(Process.THREAD_PRIORITY_DEFAULT) }
            val sb = StringBuilder()
            val watchdog = Runnable { g.timedOut = true; lock.withLock { cancelLocked(g) } }
            main.postDelayed(watchdog, if (night && g.listeners.isEmpty() && !g.speed) NIGHT_TIMEOUT_MS else TIMEOUT_MS)
            // the reply is shown a whole sentence at a time, each checked as a line is (bar the facts it must name, which may
            // come later); a sentence that fails stops the model at once (the line is cut there or tried again), and two
            // good sentences are a whole line (the check keeps no more): the model stops there too
            var checked = ""
            val onPiece = fun(piece: String): Boolean {
                if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                // the worker's own thread: someone began waiting for this line, so it gets full speed now
                if (g.wanted && !raised) { raised = true; priority(Process.THREAD_PRIORITY_DEFAULT) }
                sb.append(piece)
                val done = completeSentences(Dialogue.preview(sb.toString())) ?: return !g.cancelled && !closed
                if (done.first != checked) {
                    checked = done.first
                    val ok = Dialogue.validate(done.first, emptyList(), topic.self, topic.prompt ?: "") ?: return false
                    if (g.listeners.isNotEmpty() && ok != g.shown) { g.shown = ok; g.send(Line(ok, true, false, "AI · writing…")) }
                    if (done.second >= 2) return false
                }
                return !g.cancelled && !closed
            }
            var raw: String? = try {
                com.timerpg.street.llm.AiLane.run("dialogue") { b.generate(prompt, MAX_TOKENS, if (g.variant) VARIANT_TEMPERATURE else TEMPERATURE, onPiece) }
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
                null
            } finally {
                main.removeCallbacks(watchdog)
            }
            // NativeLlm reports failures (decode error, no model, prompt too long) as an empty or partial reply plus
            // lastGenerateError, not by throwing: that is an error to retry, not a reply to judge and cache as rejected.
            val nativeError = (b as? NativeLlm)?.lastGenerateError
            if (raw != null && nativeError != null && nativeError != "cancelled") { error = nativeError; raw = null }
            stats(b)
            if (night && !g.speed) countNight(b)
            if (g.timedOut) { end = End.TIMEOUT; break }
            if (g.cancelled || closed) { end = End.CANCELLED; break }
            if (raw == null) { end = End.ERROR; break }
            lastRaw = raw
            text = Dialogue.validate(raw, topic.must, topic.self, topic.prompt ?: "")
            // another way of saying it must be another way: the same words again is a failed try
            if (text != null && g.variant && !cache.isNew(g.key, text!!)) text = null
            if (text != null) { end = End.OK; break }
            // its first sentence alone may be good (the slip was in the second): keep that rather than start over
            FIRST.find(Dialogue.preview(raw))?.groupValues?.get(1)?.let { first ->
                text = Dialogue.validate(first, topic.must, topic.self, topic.prompt ?: "")
                if (text != null && g.variant && !cache.isNew(g.key, text!!)) text = null
            }
            if (text != null) { end = End.OK; break }
            val why = Dialogue.whyRejected(raw, topic.must, topic.self, topic.prompt ?: "")
            _status.update { it.copy(rejected = "${npc.first}: $why: \u201c${Dialogue.preview(raw).take(90)}\u201d") }
        }
        val tEnd = SystemClock.elapsedRealtime()
        val ms = tEnd - t0
        val tokens = try { b.stats.genTokens } catch (t: Throwable) { 0 }
        fun info(source: String, why: String? = null) = ReplyInfo(source, modelLabel(b), waitMs = (t0 - g.createdAt).coerceAtLeast(0),
            firstTokenMs = if (firstAt > 0) firstAt - t0 else 0, restMs = if (firstAt > 0) tEnd - firstAt else 0, tokens = tokens, tries = tries, why = why)
        when (end) {
            End.OK -> {
                failures = 0
                val slot = g.slot ?: slotOf(g.key)
                if (g.variant) {
                    if (cache.addVariant(g.key, text!!)) addNight(variants = 1) else { cache.variantFailed(g.key); addNight(rejected = 1) }
                    saveCache()
                // (the light model's on-the-spot lines are not banked: the main model writes those overnight)
                } else if (!g.speed && loadedRole != ModelRole.LIVE) { cache.putLine(g.key, text!!, slot); saveCache(); if (night) addNight(lines = 1) }
                g.finish(Line(text!!, true, true, "AI", info("live")))
                // a line written ahead: its voice can be made ahead too
                if (!g.speed && g.listeners.isEmpty()) onWritten?.invoke(npc, text!!)
            }
            End.REJECTED -> {
                failures = 0
                if (night && !g.speed) addNight(rejected = 1)
                if (g.variant) { cache.variantFailed(g.key); saveCache() }
                else if (!g.speed && loadedRole != ModelRole.LIVE) { cache.putTemplate(g.key, g.slot ?: slotOf(g.key)); saveCache() }
                g.finish(template(topic, "built-in · the AI's reply did not pass the check", info = info("template", "the reply did not pass the check")))
            }
            End.TIMEOUT -> {
                // overnight: given up for this night only (no one waited for it)
                if (night && g.listeners.isEmpty() && !g.speed) {
                    if (g.variant) cache.variantFailed(g.key) else lock.withLock { nightSkip += g.key }
                } else if (++timeouts >= 2 && pregenOff == null) pregenOff = "The model is too slow on this phone to write lines ahead; it only answers when you ask."
                g.finish(template(topic, "built-in · the AI took too long", info = info("template", "took too long")))
            }
            End.CANCELLED -> {
                requeue(g)
                g.finish(template(topic, "built-in"))
            }
            End.ERROR -> {
                g.finish(template(topic, "built-in · the AI failed", info = info("template", "the model failed: ${error ?: "unknown"}")))
                if (error != null && PROMPT_ERRORS.any { error!!.contains(it) }) {
                    // this prompt will always fail (too long for the context, cannot be tokenized): the template it is,
                    // without taking the model down for the rest of the town
                    if (g.variant) cache.variantFailed(g.key)
                    else if (!g.speed) { cache.putTemplate(g.key, g.slot ?: slotOf(g.key)); saveCache() }
                } else if (++failures >= 3) {
                    closeBackend()
                    setState(ModelState.ERROR, "The model stopped working (${error ?: "unknown error"}). Tap Retry to load it again.")
                } else requeue(g)
            }
        }
        if (g.speed) {
            val st = try { b.stats } catch (t: Throwable) { LlmStats() }
            val why = when (end) { End.TIMEOUT -> "Took longer than ${TIMEOUT_MS / 1000} s."; End.CANCELLED -> "Cancelled."; End.ERROR -> error; else -> null }
            _status.update { it.copy(speedTesting = false, speed = SpeedResult(npc.name, topic.intent, text ?: Dialogue.preview(lastRaw), end == End.OK, ms, st, why)) }
        }
    }

    /** A line that was stopped part-way goes back in the queue (unless someone else has written it meanwhile). */
    private fun requeue(g: Gen) {
        if (g.speed || closed) return
        val p = planned[g.key] ?: return
        lock.withLock { if (cache.get(g.key) == null && demand?.key != g.key) queue.add(p.job) }
    }

    /** A question not started yet that another one replaces: its listener gets the template, its line goes back in the queue. */
    private fun dropDemandLocked() {
        val old = demand ?: return
        demand = null
        old.finish(template(old.target.topic, "built-in"))
        if (old.speed) _status.update { it.copy(speedTesting = false, speed = SpeedResult(old.target.npc.name, old.target.topic.intent, "", false, 0, LlmStats(), "Speed test replaced by a question; try again.")) }
        else if (cache.get(old.key) == null) planned[old.key]?.let { queue.add(it.job) }
    }

    /** Stops whatever the backend is doing when no line is (a model being loaded). Under [lock]. */
    private fun cancelBackendLocked() {
        try { backend?.cancel() } catch (t: Throwable) { Log.w(TAG, "cancel: $t") }
    }

    /** Asks [g] to stop. Holding [lock] keeps this from racing with the backend being closed. */
    private fun cancelLocked(g: Gen) {
        g.cancelled = true
        if (running === g) try { backend?.cancel() } catch (t: Throwable) { Log.w(TAG, "cancel: $t") }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun Gen.send(line: Line) { for (r in listeners) post(r, line) }

    private fun Gen.finish(line: Line) {
        if (done) return
        done = true
        send(line)
    }

    private fun post(r: Request, line: Line) {
        main.post { if (r.live) r.onLine?.invoke(line) }
    }

    private fun template(topic: Topic, note: String, final: Boolean = true, info: ReplyInfo? = null) = Line(topic.template, ai = false, final = final, note = note,
        info = if (!final) null else info ?: ReplyInfo("template", why = note.removePrefix("built-in").removePrefix(" · ").ifEmpty { null }))

    private fun lineOf(topic: Topic, e: ReplyCache.Entry): Line =
        e.line?.let { Line(it, ai = true, final = true, note = "AI", info = ReplyInfo("ready")) }
            ?: template(topic, "built-in · the AI's reply did not pass the check")

    private fun setState(s: ModelState, detail: String) = _status.update { it.copy(state = s, detail = detail) }

    private var lastSave = 0L

    /** Saves the bank after a line (overnight, at most once a minute: the file grows with the variants; [endNight] saves). */
    private fun saveCache() {
        val now = SystemClock.elapsedRealtime()
        if (night && now - lastSave < 60_000L) return
        lastSave = now
        cache.save()
    }

    /** The last generation's tokens, into tonight's count. */
    private fun countNight(b: LlmBackend) {
        val st = try { b.stats } catch (t: Throwable) { return }
        nightCount.updateAndGet { it.copy(genTokens = it.genTokens + st.genTokens, genMs = it.genMs + st.genMs,
            prefillTokens = it.prefillTokens + (st.promptTokens - st.reusedTokens).coerceAtLeast(0), prefillMs = it.prefillMs + st.prefillMs) }
    }

    private fun addNight(lines: Int = 0, variants: Int = 0, rejected: Int = 0) {
        nightCount.updateAndGet { it.copy(lines = it.lines + lines, variants = it.variants + variants, rejected = it.rejected + rejected) }
    }

    private fun stats(b: LlmBackend) {
        try { val s = b.stats; _status.update { it.copy(stats = s) } } catch (t: Throwable) { }
    }

    private fun refresh() {
        val (q, p) = lock.withLock { queue.size to pauses.sorted().joinToString(", ") }
        val lines = cache.lines
        val builtIn = cache.templates
        val prog = if (night) lock.withLock { progressLocked() } else null
        _status.update { it.copy(lines = lines, builtIn = builtIn, queued = q, paused = p, pregenOff = pregenOff, bank = bank(), night = prog) }
    }

    /** The bank for every line the game can ask for now: this town's, the player's steered ones, other years'. */
    fun bank(): ReplyCache.Bank {
        val plan = HashMap<String, String>()
        for ((k, v) in planned) plan[k] = v.slot
        for ((k, v) in extraPlans) plan.putIfAbsent(k, v.slot)
        for (j in steered) plan.putIfAbsent(j.key, j.slot)
        return cache.bank(plan)
    }

    private fun priority(p: Int) {
        try { Process.setThreadPriority(p) } catch (t: Throwable) { }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
            if (what == "start" || what == "command") setState(ModelState.ERROR, "Something went wrong ($what): ${t.message ?: t.javaClass.simpleName}")
        }
    }

    companion object {
        /** The topic of [npc]'s that the player's own words [said] are about (chat when nothing more specific fits). */
        fun topicFor(npc: Npc, said: String): Topic? {
            val s = " " + said.lowercase().replace(Regex("[^a-z' ]"), " ") + " "
            fun has(vararg w: String) = w.any { s.contains(" $it") }
            val intent = when {
                has("secret", "hiding", "hide ", "hidden") -> "secret"
                has("rumor", "rumour", "gossip", "heard anything", "heard any", "news", "going on", "what's new", "whats new") -> "rumors"
                has("family", "wife", "husband", "kids", "children", "child", "son ", "sons", "daughter", "married", "spouse", "parents") -> "family"
                has("work", "job", "shop", "store", "business", "living", "trade", "sell") -> "work"
                has("town", "mayor", "place like", "around here") -> "town"
                else -> "chat"
            }
            val voiced = npc.topics.filter { it.prompt != null }
            return voiced.firstOrNull { it.intent == intent } ?: voiced.firstOrNull { it.intent == "chat" } ?: voiced.firstOrNull() ?: npc.topics.firstOrNull()
        }

        /** [prompt] with [facts] (what the person remembers) added to its fact sheet, before the example answer. */
        fun withMemories(prompt: String, facts: List<String>): String {
            if (facts.isEmpty()) return prompt
            // someone who remembers talking with the player does not take them for a stranger any more
            val p0 = prompt
            val prompt = p0.replace("The player is a stranger.", "The player is an acquaintance; you have talked before.")
            val at = prompt.indexOf("\nExample of the kind of answer:").takeIf { it >= 0 } ?: prompt.indexOf("<|im_end|>").takeIf { it >= 0 } ?: return prompt
            return prompt.substring(0, at) + "\n" + facts.joinToString("\n") + prompt.substring(at)
        }

        /** [prompt] (ChatML) with the player's turn replaced by [words]. */
        fun withQuestion(prompt: String, words: String): String {
            val start = prompt.indexOf("<|im_start|>user\n").takeIf { it >= 0 } ?: return prompt
            val from = start + "<|im_start|>user\n".length
            val end = prompt.indexOf("<|im_end|>", from).takeIf { it >= 0 } ?: return prompt
            return prompt.substring(0, from) + words + prompt.substring(end)
        }

        private const val TAG = "Dialogue"
        const val MAX_TOKENS = 56
        const val TEMPERATURE = .3f
        /** Another way of saying a line already written: a little more freedom (each reply also has its own seed). */
        const val VARIANT_TEMPERATURE = .7f
        /** Overnight no one waits: a line is given up only after this long. */
        const val NIGHT_TIMEOUT_MS = 120_000L
        const val CONTEXT_TOKENS = 1024
        /** The light model's context in play (see [contextFor]). */
        const val LIVE_CONTEXT_TOKENS = 1024
        /** Tries per line before it stays the template. */
        const val MAX_TRIES = 2
        private val SENTENCE_END = Regex("[.!?]+[\"”’')]*\\s+(?=\\S)")

        /**
         * The whole sentences at the start of [t] (those the model has gone on past: a full stop, then a space and more)
         * and how many; null when none is complete yet.
         */
        fun completeSentences(t: String): Pair<String, Int>? {
            var n = 0; var end = 0
            for (m in SENTENCE_END.findAll(t)) { end = m.range.first + m.value.trimEnd().length; n++ }
            return if (n == 0) null else t.substring(0, end).trim() to n
        }

        private val FIRST = Regex("""^(.{8,}?[.!?])(\s|$)""")
        /** A reply that takes longer than this is given up (the template stays). */
        const val TIMEOUT_MS = 45_000L
        /** Native errors that come from the prompt itself, not from the model: they would happen every time. */
        private val PROMPT_ERRORS = listOf("prompt too long", "could not tokenize", "empty prompt")
        private const val SETTINGS = "v1 tokens=$MAX_TOKENS temperature=$TEMPERATURE"
        private const val NO_MODEL_TEXT = "No model yet: people use their built-in lines. Download it from the AI panel, or choose the model file (Qwen3.5-9B-Q4_K_M.gguf, about 5.7 GB) to give them their own words. It is copied into the game once."
        private const val READY_TEXT = "Ready. Lines near you are written ahead of time."
        private const val LIVE_READY_TEXT = "Ready: the light model answers in play. Lines ahead of time are written by the main model (overnight, or when it fits)."
        /** The pause reason while the light model is loaded (pre-generation is the main model's). */
        const val LIVE_PAUSE = "light model loaded"

        fun keyOf(prompt: String): String = ReplyCache.keyOf(prompt, SETTINGS)

        /** The walkable towns' folders (each with its own dialogue.json and street.json v2). */
        val TOWN_DIRS = listOf("town/gamma/")

        /** Retries of a load put off for want of memory, how far apart, and the memory unloads before it waits for Load. */
        const val MEMORY_TRIES = 8
        const val MEMORY_RETRY_MS = 15_000L
        /** How often a model on the CPU for want of memory looks for room on its device, and how long the room must hold. */
        const val DEVICE_RETRY_MS = 30_000L
        const val DEVICE_ROOM_MS = 60_000L
        const val MEMORY_UNLOADS = 2
        /** After a memory unload, how long memory must have been fine before the model comes back by itself. */
        const val RELOAD_AFTER_MS = 120_000L

        /** A hold on the model's load the engine starts with ([holdLoad]; set before [shared] first makes it). */
        @Volatile var holdAtStart: String? = null

        /** The folder the engine starts on (set before [shared] first makes it: the town the game opens in). */
        @Volatile var startDir = ""

        private var shared: DialogueEngine? = null

        /** The process already has its engine (the game's, or the overnight writer's). */
        val sharedExists: Boolean @Synchronized get() = shared?.closed == false

        /** The process's engine (it outlives the activity being recreated). */
        @Synchronized
        fun shared(ctx: Context): DialogueEngine =
            shared?.takeIf { !it.closed } ?: DialogueEngine(ctx.applicationContext).also { shared = it; it.start() }

        /** Closes the engine (the game is finishing). */
        @Synchronized
        fun release() {
            shared?.close()
            shared = null
        }
    }
}
