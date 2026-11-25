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
    /** In play, waiting for room for the NPU copy (the model never runs on the CPU in play): "waiting for NPU memory
     *  (2804 MB free, 3751 needed)", for the HUD's chip and the panel; null: not waiting for it. */
    val npuWait: String? = null,
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
 * themes), 5 the main model rewriting what the light one wrote (Writer mode, when only the light one fitted), 3 more
 * ways of saying each line (variants), 4 other years, 0 nothing left; [done] of [total] in that stage.
 */
data class NightProgress(val stage: Int, val done: Int, val total: Int) {
    val label: String get() = when (stage) {
        1 -> "Writing dialogue: $done of $total"
        2 -> "What you ask about: $done of $total"
        3 -> "More ways to say it: $done of $total"
        4 -> "Other years: $done of $total"
        5 -> "Main model rewriting the light model's lines: $done of $total"
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
    /** Lines given up for taking longer than [DialogueEngine.NIGHT_TIMEOUT_MS] (not tried again tonight), and lines the
     *  model failed on (a native error), with the last such failure in words: both used to be counted nowhere, so a
     *  writer that timed out on every line showed 0 lines and 0 turned down. */
    val timedOut: Int = 0,
    val failed: Int = 0,
    val lastFailure: String? = null,
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
                      val slot: String? = null, val upgrade: Boolean = false) {
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
    @Volatile private var demand: Gen? = null
    private var running: Gen? = null

    /** Pre-baking a person's saved states ([NativeLlm.bake]) while the light model idles in play. */
    private class Bake(val system: String, val prompt: String)
    /** The system messages baked (or tried) this session; how many bakes ran. Guarded by [lock]. */
    private val baked = HashSet<String>()
    private var bakes = 0
    /** Where the saved states go: noBackupFilesDir (never in a cloud backup: they are rebuilt from the model). */
    private val stateDir: File get() = File(app.noBackupFilesDir, "llm-states")

    /** The state files' budget: [STATE_BUDGET_BYTES], but never more than what keeps half the storage free. */
    private fun stateBudget(): Long {
        val dir = stateDir
        val held = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val free = runCatching { app.noBackupFilesDir.usableSpace }.getOrDefault(0L)
        return minOf(STATE_BUDGET_BYTES, (free + held) / 2).coerceAtLeast(0L)
    }

    /**
     * Under [lock]: the next person to pre-bake. In play with the light model loaded (pre-generation is the main model's,
     * so the NPU idles between replies), standing still: the system messages of the [BAKE_AHEAD] nearest lines not
     * written yet, each once a session, so talking to them restores their state instead of reading their fact sheet
     * again. Their files stay on flash for later sessions too.
     */
    private fun bakeJobLocked(): Bake? {
        if (night || moving || pregenOff != null || pauses != setOf(LIVE_PAUSE)) return null
        val n = backend as? NativeLlm ?: return null
        if (n.stateDir == null || !n.loaded) return null
        for (j in queue.ordered().take(BAKE_AHEAD)) {
            val prompt = planned[j.key]?.target?.topic?.prompt ?: continue
            val system = prompt.substringBefore("<|im_end|>", "")
            if (system.isEmpty() || !baked.add(system)) continue
            return Bake(system, prompt)
        }
        return null
    }

    /** Bakes one person's states (worker thread). */
    private fun bake(b: Bake) {
        val n = backend as? NativeLlm ?: return
        val t0 = SystemClock.elapsedRealtime()
        val covered = n.bake(b.prompt)
        val k = lock.withLock { ++bakes }
        if (k == 1 || k % 10 == 0 || covered < 0)
            com.timerpg.street.shot.Crumbs.add("dialogue model: baked $k fact sheets (last ${if (covered < 0) "failed" else "$covered tokens"} in " +
                "${SystemClock.elapsedRealtime() - t0} ms); ${n.stateLine()}")
    }
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
    /** The player is moving (or turning the camera): no line is started ahead of time (one being written holds). */
    @Volatile private var moving = false
    /** Lines written ahead of time that were held while the player moved, and for how long in all (ms): the frame profiler's crumbs. */
    @Volatile var held = 0; private set
    @Volatile var heldMs = 0L; private set
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
        // a try of a device copy that ended with the process (killed during the load) counts against trying it again
        runCatching { com.timerpg.street.llm.NpuGate.checkKilled(app) }
        com.timerpg.street.llm.MemProbe.Trims.watch(app)
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
    fun ask(npc: Npc, topic0: Topic, onLine: (Line) -> Unit): Request {
        // what they know of the player from the loop (they saw a theft, heard of it; worked beside them): in the fact sheet
        val topic = withLoopFacts(npc, topic0)
        val prompt = topic.prompt ?: return Request(template(topic, "built-in"), null)
        val key = keyOf(prompt)
        // small talk after a real conversation: what they would say about it next time (written overnight), once
        if (topic.intent == "chat" && topic === topic0) followUp(npc)?.let { return Request(Line(it, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "a follow-up")), null, ready = true) }
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
     * is the fallback, as for any line. [recent]: their last replies in this conversation, which they should not say
     * again. Returns the topic answered and the request.
     */
    fun askFree(npc: Npc, said: String, remembered: List<String> = emptyList(), vec: FloatArray? = null, recent: List<String> = emptyList(),
                onLine: (Line) -> Unit): Pair<Topic, Request>? {
        // (someone who knows something of the player from the loop answers live, from facts no line written ahead had)
        val knows = loopFacts?.invoke(npc.id).orEmpty().isNotEmpty()
        // one of the player's frequent questions, answered ahead of time from this person's own facts: that line, if the
        // words are close enough to the theme (same kind of question, same subject: [Themes.match]); else live
        if (!knows) Themes.match(said, vec, steer.themes)?.let { th ->
            themeAsk(npc, th)?.let { (t, key) ->
                cache.next(key)?.takeIf { !Dialogue.repeats(it, recent) }?.let { line -> return topicFor(npc, said)!! to Request(Line(line, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "a theme you ask about")), null, ready = true) }
            }
        }
        val topic = topicFor(npc, said) ?: return null
        // words that plainly ask about one of their topics (their family, their work, the town...): that topic's line written
        // ahead answers at once, while nothing is generated (unless they said it already); small talk and anything else goes
        // to the model live
        if (!knows && topic.intent != "chat" && topic.prompt != null) {
            val k = keyOf(topic.prompt)
            if (cache.get(k)?.lines?.isNotEmpty() == true) cache.next(k)?.takeIf { !Dialogue.repeats(it, recent) }?.let { line ->
                return topic to Request(Line(line, ai = true, final = true, note = "AI · ready", info = ReplyInfo("ready", why = "their ${topic.intent} line")), null, ready = true)
            }
        }
        return topic to ask(npc, freeTopic(topic, said, remembered, recent), onLine)
    }

    /**
     * The loop's facts about the player for a person (kotlin-street/docs/LOOP.md: the facade's `attitude` facts, cached
     * by LoopSession until their attitude may have changed): added to their fact sheet in every prompt. Set by the app.
     */
    @Volatile var loopFacts: ((String) -> List<String>)? = null

    private fun withLoopFacts(npc: Npc, topic: Topic): Topic {
        val p = topic.prompt ?: return topic
        val f = loopFacts?.invoke(npc.id).orEmpty().take(6).map { it.replace("<|", "<").replace("|>", ">").replace("\n", " ").take(300) }
        if (f.isEmpty()) return topic
        // (a person who knows the player's name from a shift or a sale does not take them for a stranger)
        val known = if (f.any { it.startsWith("You know this person by name") }) p.replace("The player is a stranger.", "The player is someone you have met.") else p
        val at = known.indexOf("\nExample of the kind of answer:").takeIf { it >= 0 } ?: known.indexOf("<|im_end|>").takeIf { it >= 0 } ?: return topic
        return topic.copy(prompt = known.substring(0, at) + "\nWhat you know about the player (\"this person\"):\n" + f.joinToString("\n") + known.substring(at))
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

    /** [loadBeforeTown]: the early load asked of the engine's thread, waited for briefly. */
    private fun loadEarly() {
        if (closed || night || !foreground || backend?.loaded == true || android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return
        val done = java.util.concurrent.CountDownLatch(1)
        val t0 = android.os.SystemClock.elapsedRealtime()
        command { try { loadModel(early = true) } finally { done.countDown() } }
        // room for both the copy and the town's build: the build goes beside the load (the valve still watches it)
        val free = memoryNow().first shr 20
        val need = copyNeed(ModelRole.LIVE, ModelFiles.file(app)) shr 20
        if (free >= need + TOWN_BUILD_MB) {
            com.timerpg.street.shot.Crumbs.add("dialogue model: early load beside the town's build ($free MB free, $need needed + ~$TOWN_BUILD_MB for the town)")
            return
        }
        val inTime = runCatching { done.await(EARLY_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false)
        com.timerpg.street.shot.Crumbs.add("dialogue model: the town's build waited ${android.os.SystemClock.elapsedRealtime() - t0} ms for the early load " +
            "(${if (inTime) "done" else "still going, the town goes ahead"}; ${if (backend?.loaded == true) "${loadedRole?.name?.lowercase()} model loaded" else "no model"}, " +
            "${memoryNow().first shr 20} MB free now)")
    }

    /** Lifts [holdLoad]'s [reason]; the last one lifted starts a load that waited. */
    fun releaseLoad(reason: String) {
        val go = lock.withLock { loadHolds.remove(reason) && loadHolds.isEmpty() && loadWanted }
        if (go) { com.timerpg.street.shot.Crumbs.add("dialogue model: the load waited for $reason, starting now"); command { loadModel() } }
    }

    /**
     * The player is moving ([on]) or has stood still a moment: while moving no line is started ahead of time and the one
     * being written holds between two words until they stand (its threads asleep), so the cores and the memory bus are the frame's (CLAUDE.md: heavy work in bursts while
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
            // (writing the bank with the game closed: memory short of the game's comfort is no reason to hold the writing;
            // the owner's night of 2026-11-19 sat 23 minutes "writing" with the model loaded and nothing written, held so)
            1 -> if (night && !foreground) resume("memory") else pause("memory")
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
        // (and a model on the CPU, loaded behind the game: in play it never runs there)
        else command { if (backend?.loaded == true && (loadedRole != ModelRole.LIVE || loadedOnCpu)) loadModel() }
    }

    /** Memory pressure: pause pre-generation, and under real pressure let go of the model (it reloads on return). */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        // writing the bank with the game closed: "the app is in the background" is the point, not a reason to hold; only
        // real pressure lets the model go (the writer loads it again). Before this, the game going out of sight left a
        // "memory" hold that nothing lifted (the level watch only speaks on a change): the night of 2026-11-19 had the
        // model loaded again and ready for 23 minutes of writing time and wrote nothing.
        if (night && !foreground) {
            @Suppress("DEPRECATION")
            if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                lock.withLock { running?.let { cancelLocked(it) } ?: cancelBackendLocked() }
                command { unloadForMemory(force = true) }
            }
            return
        }
        // a low-memory signal while a device copy is in its hold window: the margin grows (NpuGate.marginTrouble)
        if (foreground && (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
            marginTroubled("Android's low-memory signal (onTrimMemory $level) within the load's hold window")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                pause("memory")
                // stop what runs, including a model still loading (it would only be unloaded again)
                lock.withLock { running?.let { cancelLocked(it) } ?: cancelBackendLocked() }
                // (critical with the game in front: let go of it too, or Android kills the game for it)
                command { unloadForMemory(force = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
            }
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !(night && !foreground) -> {
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

    /** validate()-aware sampling ("npu" pref "guided", default off until the owner has judged the blind side-by-side):
     *  see [com.timerpg.street.llm.NativeLlm.guided]. Read for every reply. */
    val guidedWanted: Boolean get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getBoolean("guided", false)
    /** Fixed-shape guess-and-check passes from the next load ("npu" pref "fixed_steps", default off: to A/B on the S25),
     *  see [com.timerpg.street.llm.NativeLlm.fixedSteps]. */
    val fixedStepsWanted: Boolean get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getBoolean("fixed_steps", false)
    /** Overnight, the main model writes this many lines per pass ("npu" pref "writer_batch", 0/1 off, at most 8; default
     *  off until measured on the S25: ENGINE_TAILORING.md, idea 6). */
    val writerBatchWanted: Int get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getInt("writer_batch", 0).coerceIn(0, 8)
    /** MTP speculative decoding: tokens to guess ahead (0 off; default 2), see [com.timerpg.street.llm.NativeLlm.mtp]. */
    val mtpWanted: Int get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getInt("mtp", 2)

    /** N-gram guesses: tokens to guess ahead by lookup in the prompt, the reply and the person's bank lines (0 off),
     *  see [com.timerpg.street.llm.NativeLlm.ngram]. */
    val ngramWanted: Int get() = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getInt("ngram", NGRAM_DEFAULT)

    fun setNgram(n: Int) {
        app.getSharedPreferences("npu", Context.MODE_PRIVATE).edit().putInt("ngram", n).apply()
        lock.withLock { running?.let { cancelLocked(it) } }
        command { closeBackend(); loadModel() }
    }

    /** The n-gram guesses' pool for a line of [npc]'s: their other lines in the bank (the line [key] itself left out). */
    private fun poolFor(npc: Npc, key: String): String = npc.topics.asSequence()
        .mapNotNull { t -> t.prompt?.let { keyOf(it) }?.takeIf { it != key }?.let { cache.get(it)?.lines } }
        .flatten().take(POOL_LINES).joinToString("\n")

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
    private fun workKey(role: ModelRole, dev: Int? = null, batch: Int? = null, what: String = "work") =
        "${what}_${role.name.lowercase()}_${if (kvQ8Wanted) "q8" else "f16"}_${contextFor(role)}" +
        (dev?.let { "_d${it}_m${modelSize(role)}" } ?: "") + (batch?.let { "_b$it" } ?: "")

    private fun modelSize(role: ModelRole): Long = if (role == ModelRole.LIVE) ModelFiles.LIVE_BYTES else ModelFiles.file(app).length()

    /**
     * The prompt's batch on [dev]: the panel's setting when one was made ("batch"), else 128 on the NPU (its compute
     * buffers were 318 MB at 512, MEMORY_AUDIT.md M2: in-play prompts are short, a fact sheet and a reply in 1024 tokens)
     * and 512 elsewhere.
     */
    fun batchFor(dev: Int): Int = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getInt("batch",
        if (dev == com.timerpg.street.llm.NativeLlm.NPU) NPU_BATCH else 512)

    /** How the last [workWanted] got its figure (for the gate's breadcrumb). */
    @Volatile private var workNote = ""

    /**
     * [role]'s own memory beside its weights (KV cache, recurrent state, compute buffers) at [dev]'s batch: as measured on
     * [dev] for this model at that batch (kept across sessions), else on any device at that batch (the CPU's measure of
     * the same cache and context is close to the NPU's), else a measure at another batch scaled to this one
     * ([ModelRoles.scaleWork]: the compute buffers follow the batch; measures from before 2026-11-24 kept no batch and no
     * split, and were all at 512, so their compute share is the S25's measured one), else the flat guess.
     */
    private fun workWanted(role: ModelRole, dev: Int? = deviceFor(role, backend as? com.timerpg.street.llm.NativeLlm)): Long {
        val p = app.getSharedPreferences("ai", Context.MODE_PRIVATE)
        val b = batchFor(dev ?: com.timerpg.street.llm.NativeLlm.CPU)
        listOfNotNull(dev?.let { workKey(role, it, b) }, workKey(role, null, b)).firstOrNull { p.getLong(it, 0L) > 0 }?.let {
            workNote = "measured at batch $b"; return p.getLong(it, 0L)
        }
        for (from in listOf(512, 256, 128).filter { it != b }) for (k in listOfNotNull(dev?.let { workKey(role, it, from) }, workKey(role, null, from))) {
            val w = p.getLong(k, 0L); if (w <= 0) continue
            val c = p.getLong(k.replaceFirst("work_", "compute_"), (w * ModelRoles.COMPUTE_SHARE_512).toLong())
            workNote = "estimated for batch $b from ${w shr 20} MB measured at $from"
            return ModelRoles.scaleWork(w, c, from, b)
        }
        // (before 2026-11-24: no batch in the key, always 512)
        listOfNotNull(dev?.let { workKey(role, it) }, workKey(role)).firstOrNull { p.getLong(it, 0L) > 0 }?.let { k ->
            val w = p.getLong(k, 0L)
            workNote = "estimated for batch $b from ${w shr 20} MB measured at 512"
            return ModelRoles.scaleWork(w, (w * ModelRoles.COMPUTE_SHARE_512).toLong(), 512, b)
        }
        workNote = "a first guess"
        return ModelRoles.WORK_BYTES
    }

    /** Where the weights a device copy of [role] really took are kept (MB measured, by device, file size and head setting). */
    private fun copyKey(role: ModelRole, dev: Int, fileBytes: Long) = "copy_${role.name.lowercase()}_d${dev}_m${fileBytes}_h${if (headMappedWanted) 1 else 0}" +
        // (a file with the English-first head copies its K rows to the device: another measured size)
        (if (role == ModelRole.LIVE) com.timerpg.street.llm.NativeLlm.headVocab(ModelFiles.live(app)).let { if (it > 0) "_k$it" else "" } else "")

    /** What [role]'s device copy takes of its file, as its last load on [dev] measured (bytes), else -1 (the whole file). */
    private fun copyWanted(role: ModelRole, fileBytes: Long, dev: Int? = deviceFor(role, backend as? com.timerpg.street.llm.NativeLlm)): Long {
        if (dev == null || dev == com.timerpg.street.llm.NativeLlm.CPU) return -1
        return app.getSharedPreferences("ai", Context.MODE_PRIVATE).getLong(copyKey(role, dev, fileBytes), -1L)
    }

    /** A tied output head kept on the CPU, mapped, for a device copy (the AI panel may turn it off: "headMapped"). */
    val headMappedWanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("headMapped", true)

    /** The context each model gets: the light one's live replies fit in [LIVE_CONTEXT_TOKENS] (fact sheet, memories and reply). */
    private fun contextFor(role: ModelRole) = if (role == ModelRole.LIVE) LIVE_CONTEXT_TOKENS else CONTEXT_TOKENS

    /** What [role]'s copy on its device needs (MB to bytes), with its measured working memory, copy and the adaptive margin. */
    private fun copyNeed(role: ModelRole, bankFile: File): Long {
        val size = if (role == ModelRole.LIVE) ModelFiles.LIVE_BYTES else bankFile.length()
        return ModelRoles.needBytes(size, true, workWanted(role), com.timerpg.street.llm.NpuGate.margin(app) shl 20, copyWanted(role, size))
    }

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
        // (writing the bank: the NPU whenever there is one, whatever device the panel was left on from a test)
        // (in play: the NPU too, the owner's rule being that dialogue in play never runs on the CPU)
        val w = if ((night || foreground) && npu) com.timerpg.street.llm.NativeLlm.NPU else deviceWanted
        val d = if (w >= 0) w else if (npu) com.timerpg.street.llm.NativeLlm.NPU else com.timerpg.street.llm.NativeLlm.CPU
        return if (role == ModelRole.LIVE && d == com.timerpg.street.llm.NativeLlm.GPU && npu) com.timerpg.street.llm.NativeLlm.NPU else d
    }

    @Volatile private var loadedWork = 0L
    /** What the device gate counted beyond the free memory at the last choice, and why (for the breadcrumbs and panel). */
    @Volatile private var gateNote: String? = null
    /** The gate's whole arithmetic at the last choice (free, reclaim counted, margin, need and its parts, the result). */
    @Volatile private var gateLine = ""
    /** When the last device copy in play loaded (elapsed ms; 0: none, or its hold window ended or was troubled): after
     *  [com.timerpg.street.llm.NpuGate.HOLD_MS] untroubled the margin learns from it ([marginCheck]). */
    @Volatile private var heldSince = 0L
    private val marginCheck = Runnable {
        val at = heldSince
        if (at == 0L || backend?.loaded != true || android.os.SystemClock.elapsedRealtime() - at < com.timerpg.street.llm.NpuGate.HOLD_MS) return@Runnable
        heldSince = 0L
        val lowest = minOf(com.timerpg.street.llm.MemoryWatch.lowestMb, heldValveMin)
        if (lowest != Long.MAX_VALUE) com.timerpg.street.llm.NpuGate.marginHeld(app, lowest, com.timerpg.street.llm.MemoryWatch.thresholdMb)
    }
    @Volatile private var heldValveMin = Long.MAX_VALUE

    /** Trouble beside a device copy within its hold window (a low-memory signal, critical memory): the margin grows, once. */
    private fun marginTroubled(why: String) {
        if (heldSince == 0L) return
        heldSince = 0L
        main.removeCallbacks(marginCheck)
        com.timerpg.street.llm.NpuGate.marginTrouble(app, why)
    }
    /** When the last tried copy loaded (elapsed ms; 0: none): memory critical soon after counts against it. */
    @Volatile private var triedAt = 0L
    @Volatile private var triedFree = 0L
    @Volatile private var triedNeed = 0L

    /** The NPU failing for another reason than memory: the GPU before the CPU (the AI panel's switch; on by default). */
    val gpuFallbackWanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("gpuFallback", true)
    fun setGpuFallback(on: Boolean) { app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit().putBoolean("gpuFallback", on).apply() }
    /** The prompt's batch on the NPU from the next load (128 by default; [batchFor]), see [com.timerpg.street.llm.NativeLlm.batch]. */
    val batchWanted: Int get() = batchFor(com.timerpg.street.llm.NativeLlm.NPU)
    /** What the device gate counted, for the AI panel. */
    val gateText: String? get() = gateNote

    /** Which model is loaded ([ModelRole]); whether it was copied to its device (not mapped) and its file's size. */
    @Volatile private var loadedRole: ModelRole? = null
    @Volatile private var loadedCopied = false
    /** The loaded model runs on the CPU (never kept in play: [loadModel] lets it go there). */
    @Volatile private var loadedOnCpu = false
    /** Looks in a row for room for the NPU copy in play ([ModelRoles.Wait.device]): no limit, for the breadcrumbs. */
    @Volatile private var npuWaits = 0
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
            val need = copyNeed(role, ModelFiles.file(app))
            // (with what Android is expected to find for it, NpuGate: a try that failed at this level is not tried again)
            val free = memoryNow().first.let { f ->
                val swap = runCatching { com.timerpg.street.llm.MemProbe.parseMeminfo(java.io.File("/proc/meminfo").readText())["SwapFree"] }.getOrNull() ?: 0L
                f + (com.timerpg.street.llm.NpuGate.verdict(app, f shr 20, need shr 20, swap).reclaimMb shl 20)
            }
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

    /**
     * The NPU fit test ([com.timerpg.street.llm.NpuFitTest]) takes the memory: writing ahead paused, the model let go and
     * its load held. Blocks until the model is closed (at most 60 s); false if it was not in time.
     */
    fun fitTestHold(): Boolean {
        pause(FIT_TEST)
        holdLoad(FIT_TEST)
        lock.withLock { running?.let { cancelLocked(it) } }
        val done = java.util.concurrent.CountDownLatch(1)
        command { closeBackend(); main.removeCallbacks(deviceRetry); fallbackRole = null; setState(ModelState.WAITING, "The NPU fit test is using the memory."); done.countDown() }
        return done.await(60, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** After the fit test: the hold lifted and the model loaded again as usual. */
    fun fitTestRelease() {
        lock.withLock { loadHolds.remove(FIT_TEST) }
        resume(FIT_TEST)
        command { memoryWaits = 0; loadModel() }
    }

    /** The main model's file as a load on the NPU would take it (the Q4_0 copy when the tuner chose it), for the fit test. */
    fun bankFileForNpu(): File {
        val q40 = File(ModelFiles.dir(app), ModelFiles.NPU_Q4_0)
        val wantsQ40 = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getString("model", null) == "q4_0"
        return if (wantsQ40 && q40.length() == 5_379_417_312L) q40 else ModelFiles.file(app)
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
        upgraded = 0
        // (a memory hold left from play does not carry into the night; a critical level unloads as before)
        if (!gameVisible && com.timerpg.street.llm.MemoryWatch.level < 2) resume("memory")
        synchronized(recent) { recent.clear() }
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

    /** The overnight writer stopped: back to the game's rules; [release] lets the model go if the writer loaded it.
     *  [done] runs (on the worker) once the bank is saved and the model let go. */
    fun endNight(release: Boolean, done: (() -> Unit)? = null) {
        if (!night) { done?.let { command { it() } }; return }
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
            done?.invoke()
        }
        refresh()
    }

    /** The overnight writer is on. */
    val nightOn: Boolean get() = night

    /** What holds the writing now besides the writer's own rests (e.g. "memory"), or null: shown and reported by the writer. */
    fun nightHeld(): String? = lock.withLock {
        pauses.filter { it != "background" && !it.startsWith("night-") }.sorted().joinToString().ifEmpty { null }
    }

    /** Loads the model again for the overnight writer, if it is not loaded (after a memory unload, or a load put off). */
    fun nightReload() = command { if (night && !foreground && com.timerpg.street.llm.MemoryWatch.level < 2) resume("memory"); if (night && (backend?.loaded != true || loadedRole != ModelRole.BANK) && !loadingNow) { memoryWaits = 0; loadModel() } }

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
        // (the light model's lines, rewritten while the main model writes: done as they are rewritten)
        var uLeft = 0
        if (loadedRole == ModelRole.BANK) for (k in ps.keys) if (cache.get(k)?.light == true && k !in nightSkip) uLeft++
        val uTotal = uLeft + upgraded
        if (all) return NightProgress(0, baseDone + sDone + vDone + yDone + upgraded, ps.size + sj.size + vTotal + ex.size + uTotal)
        return when {
            baseDone < ps.size -> NightProgress(1, baseDone, ps.size)
            sDone < sj.size -> NightProgress(2, sDone, sj.size)
            uLeft > 0 -> NightProgress(5, upgraded, uTotal)
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
        // the light model's lines (Writer mode when only it fitted), rewritten once the main model writes
        if (loadedRole == ModelRole.BANK) for (p in ps) { val k = p.job.key; if (k !in nightSkip && cache.get(k)?.light == true) return Gen(k, p.target, upgrade = true) }
        for (p in ps) { val k = p.job.key; if (k !in nightSkip && cache.wantsVariant(k)) return Gen(k, p.target, variant = true) }
        for (p in extraPlans.values) { val k = p.job.key; if (cache.get(k) == null && k !in nightSkip) return Gen(k, p.target) }
        return null
    }

    private fun slotOf(key: String): String = (planned[key] ?: extraPlans[key])?.slot ?: ""

    /** Lines the main model rewrote this session (stage 5). */
    @Volatile private var upgraded = 0

    /**
     * The lines still to write tonight by year, person and topic ([BankPlan.queue]): every line not written yet, each
     * variant still wanted, the light model's lines to rewrite while the main model writes.
     */
    fun nightQueue(): BankPlan.Queue = lock.withLock {
        val slots = ArrayList<String>()
        for ((k, p) in planned) {
            if (k in nightSkip) continue
            val e = cache.get(k)
            when {
                e == null -> slots += p.slot
                e.light && loadedRole == ModelRole.BANK -> slots += p.slot
                cache.wantsVariant(k) -> repeat(ReplyCache.VARIANTS - e.lines.size) { slots += p.slot }
            }
        }
        for (j in steered) if (cache.get(j.key) == null && j.key !in nightSkip) slots += j.slot
        for ((k, p) in extraPlans) if (cache.get(k) == null && k !in nightSkip) slots += p.slot
        val names = _data.value?.npcs?.associate { it.id to it.name }.orEmpty()
        BankPlan.queue(slots, { names[it] })
    }

    /** The last lines written this session, newest last (for Writer mode's screen and its report). */
    fun nightRecent(): List<com.timerpg.street.night.NightReport.Written> = synchronized(recent) { recent.toList() }
    private val recent = ArrayDeque<com.timerpg.street.night.NightReport.Written>()

    private fun noteWritten(g: Gen, text: String, how: String) {
        val slot = g.slot ?: slotOf(g.key)
        // (lines steered by the player's own words stay on the phone unless they chose to send their words)
        val steeredLine = slot.substringAfterLast('/').let { it == "next" || it.startsWith("theme:") }
        val w = com.timerpg.street.night.NightReport.Written(g.target.npc.name, g.target.topic.intent, if (steeredLine && !shareWords()) null else text, how)
        synchronized(recent) { recent.addLast(w); while (recent.size > 8) recent.removeFirst() }
    }

    private fun shareWords() = app.getSharedPreferences("night", Context.MODE_PRIVATE).getBoolean("shareWords", com.timerpg.street.BuildConfig.OWNER_BUILD)

    /** "main model on the NPU": the model loaded now and its device; empty when none is. */
    fun modelNow(): String {
        val b = backend ?: return ""
        if (!b.loaded) return ""
        return modelLabel(b)
    }

    /** The free memory each model's copy on its device needs now (MB): the main one, the light one (0: not on the phone). */
    fun copyNeedsMb(): Pair<Long, Long> {
        val main = ModelFiles.file(app).length().takeIf { it > 0 }?.let { ModelRoles.needBytes(it, true, workWanted(ModelRole.BANK)) shr 20 } ?: 0L
        val light = if (ModelFiles.live(app).length() == ModelFiles.LIVE_BYTES) ModelRoles.needBytes(ModelFiles.LIVE_BYTES, true, workWanted(ModelRole.LIVE)) shr 20 else 0L
        return main to light
    }

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
            // (the light model idle in play: pre-bake the nearest people's saved states)
            if (backend?.loaded == true) bakeJobLocked()?.let { return it }
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
                is Bake -> safely("bake") { bake(task) }
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
        // (Writer mode or the overnight writer in its own process saves its last lines as it stops: read them too)
        com.timerpg.street.night.WriterLock.awaitFree(app, 20_000L)
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

    /**
     * [early]: the session is starting and the town is not built yet ([loadBeforeTown]): the light model's NPU copy loads
     * first if it fits (MEMORY_AUDIT.md, M1), past the town's hold (never past the fit test's); else it waits for the
     * town as before.
     */
    private fun loadModel(early: Boolean = false) {
        if (closed) return
        val holds = lock.withLock { loadHolds.sorted().joinToString() }
        val earlyOk = early && foreground && !night && backend?.loaded != true && lock.withLock { FIT_TEST !in loadHolds }
        // held (the town is being built): the load waits for the hold to lift ([releaseLoad])
        if (backend?.loaded != true && holds.isNotEmpty() && !earlyOk) {
            lock.withLock { loadWanted = true }
            return setState(ModelState.WAITING, "Waiting for the town to be built before loading the model ($holds).")
        }
        lock.withLock { loadWanted = false }
        main.removeCallbacks(retryLoad)
        var b = backend ?: createBackend() ?: return setState(ModelState.NO_ENGINE, noEngineText())
        val nl = b as? com.timerpg.street.llm.NativeLlm
        // the device chosen in the panel; -1: the NPU when there is one
        // (writing the bank: the NPU when there is one, whatever the panel says, see [deviceFor])
        // (in play too: the NPU whenever there is one, whatever the panel was left on: in play the model never runs on the CPU)
        // (the accelerators after llama.cpp's backends are loaded: [NativeLlm.accelerators] loads them first. Build 0.1.848
        // read them before anything had, got none, waited for "no NPU" in play without ever loading them: never the NPU)
        val accNow = nl?.accelerators ?: 0
        if (accNow != 0) app.getSharedPreferences("ai", Context.MODE_PRIVATE).let { p -> if (p.getInt("accelerators", 0) != accNow) p.edit().putInt("accelerators", accNow).apply() }
        _status.update { it.copy(hasGpu = accNow and 1 != 0, hasNpu = accNow and 2 != 0) }
        val hasNpuNow = accNow and 2 != 0
        val dev = nl?.let { n -> (if ((night || foreground) && hasNpuNow) com.timerpg.street.llm.NativeLlm.NPU else deviceWanted)
            .let { if (it >= 0) it else if (hasNpuNow) com.timerpg.street.llm.NativeLlm.NPU else com.timerpg.street.llm.NativeLlm.CPU } }
            ?: com.timerpg.street.llm.NativeLlm.CPU
        val onNpu = nl != null && dev == com.timerpg.street.llm.NativeLlm.NPU
        // without it: why, specifically (no engine, the NPU library not in this build, no HTP session), for the HUD
        val npuWhy = if (onNpu) "" else if (nl == null) "the AI engine here is not the native one (${b.javaClass.simpleName})"
            else if (hasNpuNow) "the NPU is there but device ${listOf("CPU", "GPU", "NPU").getOrElse(dev) { "?" }} was chosen"
            else nl.npuMissingWhy(app)
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
            // what Android is expected to find beyond its free count for a copy on the device (com.timerpg.street.llm.NpuGate)
            val reclaim = if (!accelerated) null else {
                val role = if (liveOk && !forceBankWanted && !night) ModelRole.LIVE else ModelRole.BANK
                val swap = runCatching { com.timerpg.street.llm.MemProbe.parseMeminfo(java.io.File("/proc/meminfo").readText())["SwapFree"] }.getOrNull() ?: 0L
                com.timerpg.street.llm.NpuGate.verdict(app, (avail + held) shr 20, copyNeed(role, bankFile) shr 20, swap)
            }
            gateNote = reclaim?.let { "${it.reclaimMb} MB counted beyond the free memory: ${it.why}" }
            val margin = com.timerpg.street.llm.NpuGate.margin(app)
            val liveWork = workWanted(ModelRole.LIVE); val liveWorkNote = workNote
            val liveCopy = copyWanted(ModelRole.LIVE, ModelFiles.LIVE_BYTES)
            val input = ModelRoles.Input(
                foreground = foreground, sceneBuilt = holds.isEmpty(), freeBytes = avail + held, lowMemory = low && held == 0L,
                bankBytes = if (bankOk) bankFile.length() else 0L, liveBytes = if (liveOk) liveFile.length() else 0L,
                accelerated = accelerated, forceBank = forceBankWanted, writing = night,
                bankWorkBytes = workWanted(ModelRole.BANK), liveWorkBytes = liveWork,
                reclaimBytes = (reclaim?.reclaimMb ?: 0L) shl 20,
                npu = onNpu, npuWhy = npuWhy,
                bankCopyBytes = copyWanted(ModelRole.BANK, bankFile.length()), liveCopyBytes = liveCopy,
                marginBytes = margin shl 20, early = earlyOk,
            )
            val c = ModelRoles.pick(input)
            // the gate's figures, every one (MEMORY_AUDIT.md, plan step 4): the next phone report proves the arithmetic
            val role = (c as? ModelRoles.Load)?.role ?: if (liveOk && !forceBankWanted && !night) ModelRole.LIVE else ModelRole.BANK
            gateLine = "free ${(avail + held) shr 20} MB" + (if (held > 0) " (${held shr 20} of it the loaded model's)" else "") +
                ", reclaim counted ${reclaim?.reclaimMb ?: 0} MB, margin $margin MB, need ${ModelRoles.copyNeed(input, role) shr 20} MB for the " +
                "${if (role == ModelRole.LIVE) "light" else "main"} copy (weights ${(if (role == ModelRole.LIVE) liveCopy.takeIf { it > 0 }?.let { "${it shr 20} measured" } else null) ?: "${(if (role == ModelRole.LIVE) ModelFiles.LIVE_BYTES else bankFile.length()) shr 20} file"}" +
                (if (role == ModelRole.LIVE) ", own ${liveWork shr 20} $liveWorkNote" else "") + ", batch ${batchFor(com.timerpg.street.llm.NativeLlm.NPU)})" +
                (if (earlyOk) ", before the town" else "") + ": ${describe(c)}" + ((c as? ModelRoles.Load)?.takeIf { it.tried }?.let { " (a try)" } ?: "")
            return c
        }
        _status.update { it.copy(hasLive = liveOk, forceBank = forceBankWanted, needLive = false,
            liveNeedMb = (if (accelerated) copyNeed(ModelRole.LIVE, bankFile) else ModelRoles.needBytes(ModelFiles.LIVE_BYTES, false)) shr 20,
            bankNeedMb = ModelRoles.needBytes(bankFile.length().takeIf { l -> l > 0 } ?: 5_680_522_464L, accelerated) shr 20) }
        var choice = choose()
        // before the town, only a load that fits now (or as a try): else the town goes first, as before
        if (earlyOk && choice !is ModelRoles.Load) {
            com.timerpg.street.shot.Crumbs.add("dialogue model: not before the town: $gateLine")
            lock.withLock { loadWanted = true }
            return setState(ModelState.WAITING, "Waiting for the town to be built before loading the model ($holds).")
        }
        // in play a model on the CPU (loaded behind the game, or by an older rule) goes: in play it never runs there
        val playNow = foreground && !night && (holds.isEmpty() || earlyOk)
        if (b.loaded) {
            val want = (choice as? ModelRoles.Load)?.role
            if (!(playNow && loadedOnCpu) && (want == null || want == loadedRole)) {
                // (a model that waits for the town is kept: the load after the build looks again)
                if (holds.isNotEmpty()) lock.withLock { loadWanted = true }
                setState(ModelState.READY, readyText()); enqueueAll(); return
            }
            // the other model is wanted now: this one goes first (both do not fit)
            com.timerpg.street.shot.Crumbs.add("dialogue model: ${loadedRole?.name?.lowercase()} model let go " +
                (if (playNow && loadedOnCpu) "(it was on the CPU, never in play)" else "for the ${want?.name?.lowercase()} one") + " (${com.timerpg.street.llm.Ram.freeMb(app)} MB free)")
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
        (choice as? ModelRoles.Wait)?.takeIf { it.device }?.let { w ->
            // in play, no room for the NPU copy (or no NPU): nothing on the CPU. People speak their lines written ahead and
            // their built-in ones; the choice is made again every DEVICE_RETRY_MS with no limit (the other models that
            // can go are let go first, above, and a try counts what Android finds when asked: NpuGate)
            memoryWaits = 0
            npuWaits++
            val free = memoryNow().first shr 20
            // the chip says which it is: no NPU found (and the part missing), or the NPU there and memory short (N of M MB)
            val short = when {
                w.noNpu -> "no NPU found: ${npuWhy.substringBefore("; backends:").take(80)}"
                w.needBytes > 0 -> "NPU short of memory: $free of ${w.needBytes shr 20} MB free" +
                    (gateNote?.substringBefore(" MB counted")?.toLongOrNull()?.takeIf { it > 0 }?.let { " (+$it Android may find)" } ?: "")
                else -> "waiting for the NPU"
            }
            if (npuWaits == 1 || npuWaits % 10 == 0)
                com.timerpg.street.shot.Crumbs.add("dialogue model: in play, nothing on the CPU: ${w.why} (look ${npuWaits}, every ${DEVICE_RETRY_MS / 1000} s; " +
                    "${gateNote ?: "no gate figure"}; ${com.timerpg.street.llm.ModelRoom.residents().joinToString { "${it.first} ${it.second} MB" }.ifEmpty { "no other model resident" }})")
            gpuNote = null
            _status.update { it.copy(runsOn = 0, gpuNote = null) }
            main.removeCallbacks(retryLoad)
            // looked at again with no limit, the no-NPU case too, less often (0.1.848 never looked again after a "no NPU"
            // read before the backends were loaded; a later look also re-reports the specific reason in the crumbs)
            main.postDelayed(retryLoad, if (w.noNpu) DEVICE_RETRY_MS * 4 else DEVICE_RETRY_MS)
            return setNpuWait(short, w.why + " People speak their lines written ahead and their built-in ones meanwhile; looking again every " +
                "${(if (w.noNpu) DEVICE_RETRY_MS * 4 else DEVICE_RETRY_MS) / 1000} s.")
        }
        npuWaits = 0
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
            gpuNote = "In play and writing the model runs on the NPU (about twice the GPU's and the CPU's speed on the S25 Ultra); the device setting applies only behind the game."
            com.timerpg.street.shot.Crumbs.add("dialogue model: the ${role.name.lowercase()} model on the NPU, not the GPU of the device setting")
        }
        // the attention cache in 8 bits (half its memory); a load that cannot have it keeps 16
        native?.kvQ8 = kvQ8Wanted
        // (repacked CPU kernels copy the weights too: not when falling back for memory)
        native?.fastCpu = fastCpuWanted && !cpuFallback
        val mtpSet = app.getSharedPreferences("npu", Context.MODE_PRIVATE).contains("mtp")
        // (MTP is the main model's own build: the light one runs without it)
        native?.mtp = if (role == ModelRole.BANK && useMtp && (mtpSet || native?.device == com.timerpg.street.llm.NativeLlm.NPU)) mtpWanted else 0
        // n-gram guesses (no model of their own: either role), stacked before MTP's
        native?.ngram = ngramWanted
        // loading holds the AI lane: speech, memory and the voices do not load beside it (their memory added to a load's
        // is what took the phone down)
        // the prompt's batch: 128 on the NPU (its compute buffers: MEMORY_AUDIT.md, M2), 512 elsewhere (batchFor)
        native?.batch = batchFor(native?.device ?: com.timerpg.street.llm.NativeLlm.CPU)
        // a tied output head stays mapped on the CPU rather than copied to the device a second time (M3)
        native?.headMapped = headMappedWanted
        // the saved states of the hybrid model on flash, not in RAM (NOVEL_OPTIMIZATIONS.md, idea 2): shared by this
        // process and the writer's, kept across sessions; their budget leaves the phone's storage at least half free
        native?.stateDir = stateDir
        native?.stateBudgetBytes = stateBudget()
        // writing the bank, only the shared rules' state: thousands of prompts a night would otherwise write a state of
        // ~100 MB each for each person and fact sheet (wear on the flash), most of them never read again; in play every
        // point, so a conversation's next questions restore its whole fact sheet
        native?.statePoints = if (night) 1 else 7
        val deviceCopy = native != null && !cpuFallback && native.device != com.timerpg.street.llm.NativeLlm.CPU
        // right before a device copy: the Java heap and the native allocator give back what they can (C2, M6: memory freed
        // just before the load shows up as free in full; an hour earlier Android would have refilled it with cache)
        if (deviceCopy) trimBeforeLoad()
        // a try: the copy fits only with what Android is expected to find (NpuGate). Marked first (a kill during it is
        // known on the next start), watched (stopped if free memory falls to Android's threshold plus a valve), recorded.
        // Every device copy with the game in front is watched by the valve now (a try or not), so it also gives the lowest
        // free memory the margin learns from
        val tried = (choice as? ModelRoles.Load)?.tried == true && native != null && !cpuFallback
        val freeBefore = memoryNow().first shr 20
        val needMb = copyNeed(role, bankFile) shr 20
        var valve: com.timerpg.street.llm.LoadValve? = null
        com.timerpg.street.shot.Crumbs.add("npu gate: ${if (tried) "trying" else "loading"} with $freeBefore MB free after the trim; $gateLine")
        if (tried) {
            com.timerpg.street.llm.NpuGate.markRunning(app, freeBefore, needMb)
            com.timerpg.street.shot.Crumbs.add("dialogue model: trying the ${role.name.lowercase()} model's ${listOf("CPU", "GPU", "NPU").getOrElse(wantDev) { "?" }} copy " +
                "with $freeBefore MB free, $needMb MB needed: ${gateNote ?: ""}")
        }
        if (tried || (deviceCopy && foreground)) valve = com.timerpg.street.llm.LoadValve.start(app) { b.cancel() }
        var ok = com.timerpg.street.llm.AiLane.run("dialogue") {
            try { b.load(f.path, 0, contextFor(role)) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
        }
        valve?.stop()
        if (tried && native != null) {
            val after = memoryNow().first shr 20
            val copied = native.loadMem.let { (it[1] + it[2] + it[3] + it[4]).toLong() }
            val why = when { valve?.hit != null -> "stopped by the safety valve: ${valve?.hit}"; !ok -> native.error ?: "load failed"; else -> "" }
            com.timerpg.street.llm.NpuGate.record(app, com.timerpg.street.llm.NpuGate.Try(System.currentTimeMillis(), freeBefore, needMb, ok,
                if (ok) com.timerpg.street.llm.NpuGate.found(freeBefore, after, copied) else -1, why))
            if (ok) { triedAt = android.os.SystemClock.elapsedRealtime(); triedFree = freeBefore; triedNeed = needMb; main.postDelayed({ com.timerpg.street.llm.NpuGate.clearRunning(app) }, 20_000) }
            else com.timerpg.street.llm.NpuGate.clearRunning(app)
        } else if (deviceCopy && !ok && valve?.hit != null) com.timerpg.street.llm.NpuGate.marginTrouble(app, "the safety valve stopped a load: ${valve?.hit}")
        // the result, with the gate's figures, in one line (the next phone report proves the arithmetic)
        if (native != null) com.timerpg.street.shot.Crumbs.add("npu gate: result ${if (ok) "loaded" else "failed (${native.error ?: thrown ?: "?"})"}: " +
            "${memoryNow().first shr 20} MB free after (${freeBefore} before, lowest ${valve?.minFreeMb?.takeIf { it != Long.MAX_VALUE } ?: "?"}); " +
            "weights %.0f MB copied + %.0f mapped, own %.0f (compute %.0f, batch ${native.batch}); need was $needMb MB".format(java.util.Locale.US,
                native.loadMem[1], native.loadMem[0], native.loadMem[2] + native.loadMem[3] + native.loadMem[4], native.loadMem[4]))
        // the margin's hold window: an untroubled one lets it learn ([marginCheck])
        if (ok && deviceCopy && foreground && !night) {
            com.timerpg.street.llm.MemoryWatch.resetLowest()
            heldValveMin = valve?.minFreeMb ?: Long.MAX_VALUE
            heldSince = android.os.SystemClock.elapsedRealtime()
            main.removeCallbacks(marginCheck); main.postDelayed(marginCheck, com.timerpg.street.llm.NpuGate.HOLD_MS + 1000)
        }
        // the NPU could not take it: the GPU (unless it was memory: its copy is as big), then the CPU (and say so)
        val memoryFail = valve?.hit != null || (native?.error ?: "").let { e -> e.contains("alloc", true) || e.contains("memory", true) }
        // in play: the NPU or nothing (never the CPU; the GPU is no faster than the CPU on the S25 either): wait and look again
        if (!ok && native != null && playNow && !closed) {
            val err = native.error ?: thrown ?: "unknown"
            loadingNow = false
            closeBackend()
            com.timerpg.street.shot.Crumbs.add("dialogue model: the ${role.name.lowercase()} model's NPU copy did not load in play ($err): nothing on the CPU; " +
                if (memoryFail) "looking again in ${DEVICE_RETRY_MS / 1000} s" else "Retry in the AI panel")
            _status.update { it.copy(runsOn = 0, gpuNote = null) }
            if (memoryFail) {
                main.removeCallbacks(retryLoad); main.postDelayed(retryLoad, DEVICE_RETRY_MS)
                return setNpuWait("waiting for NPU memory (${memoryNow().first shr 20} MB free, $needMb needed)",
                    "The NPU copy ran short of memory while loading ($err). In play the model never runs on the CPU: people speak their " +
                        "lines written ahead and their built-in ones; looking again every ${DEVICE_RETRY_MS / 1000} s.")
            }
            return setState(ModelState.ERROR, "The NPU could not load the model ($err). In play it never runs on the CPU: people speak " +
                "their lines written ahead and their built-in ones. Tap Retry.")
        }
        if (!ok && native != null && native.device == com.timerpg.street.llm.NativeLlm.NPU && !closed && !memoryFail &&
            native.accelerators and 1 != 0 && gpuFallbackWanted) {
            val npuError = native.error ?: "unknown"
            native.device = com.timerpg.street.llm.NativeLlm.GPU
            com.timerpg.street.shot.Crumbs.add("dialogue model: the NPU could not load it ($npuError): trying the GPU")
            ok = com.timerpg.street.llm.AiLane.run("dialogue") {
                try { b.load(f.path, 0, contextFor(role)) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
            }
            if (ok) gpuNote = "The NPU could not load the model ($npuError); it runs on the GPU."
            else gpuNote = "The NPU could not load the model ($npuError), nor the GPU (${native.error ?: "unknown"})."
        }
        if (!ok && native != null && native.device != com.timerpg.street.llm.NativeLlm.CPU && !closed) {
            // the GPU or NPU could not take it: the CPU then (and say so)
            val which = if (native.device == com.timerpg.street.llm.NativeLlm.NPU) "NPU" else "GPU"
            native.device = com.timerpg.street.llm.NativeLlm.CPU
            gpuNote = (gpuNote?.let { "$it " } ?: "") + "The $which could not load the model (${native.error ?: "unknown"}); it runs on the CPU."
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
        loadedOnCpu = native != null && native.runsOn == com.timerpg.street.llm.NativeLlm.CPU
        // (a device copy's weights as copied: what letting it go gives back; the tied head kept mapped is not in it)
        loadedBytes = if (loadedCopied && native != null && native.loadMem[1] > 0) (native.loadMem[1] * 1048576.0).toLong() else f.length()
        // what this model's cache and buffers really took: the next choice for it counts that, not a guess
        val m = native?.loadMem
        // (kept by the batch it was measured at, with its compute part apart, so another batch's need is scaled from it:
        // ModelRoles.scaleWork; and the weights the device copy really took, so the need counts that and not the file)
        if (native != null && native.workBytes > 0) {
            val compute = (native.loadMem[4] * 1048576.0).toLong()
            app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
                .putLong(workKey(role, null, native.batch), native.workBytes).putLong(workKey(role, null, native.batch, "compute"), compute)
                .putLong(workKey(role, native.runsOn, native.batch), native.workBytes).putLong(workKey(role, native.runsOn, native.batch, "compute"), compute)
                .apply()
            loadedWork = native.workBytes
        } else loadedWork = 0
        if (native != null && native.runsOn != com.timerpg.street.llm.NativeLlm.CPU && native.loadMem[1] > 0)
            app.getSharedPreferences("ai", Context.MODE_PRIVATE).edit()
                .putLong(copyKey(role, native.runsOn, f.length()), (native.loadMem[1] * 1048576.0).toLong()).apply()
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
                .format(java.util.Locale.US, m[0], m[1], m[2], if (native.kvQ8Used) "8-bit" else "16-bit", m[3], m[4]) +
            "; saved ${native.stateLine()}")
        runCatching { com.timerpg.street.play.PlayLog.spanDone("model.load", b.stats.loadMs.toDouble(), "${role.name.lowercase()} on ${listOf("CPU", "GPU", "NPU").getOrElse(native?.runsOn ?: 0) { "?" }}") }
        _status.update { it.copy(role = role, workMb = loadedWork shr 20, kvQ8 = native?.kvQ8Used == true) }
        // the light model answers in play but does not write the bank ahead: the main one does that (overnight, or in
        // play when it is the one that fits); asked-for lines are not held by this
        // (writing the bank with the light model is Writer mode's choice when the main one's copy does not fit: its lines
        // are marked, and the main model rewrites them when it writes)
        if (role == ModelRole.LIVE && !night) pause(LIVE_PAUSE) else resume(LIVE_PAUSE)
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

    /**
     * Right before a device copy loads: the Java heap collected and finalized once and trimmed, and the native allocator's
     * free pages purged (MEMORY_AUDIT.md, C2/M6), with what that gave back in the breadcrumbs. On this thread (the
     * engine's), so tens of ms of collection never stall a frame.
     */
    private fun trimBeforeLoad() {
        fun rssMb() = runCatching {
            java.io.File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.shr(10)
        }.getOrNull() ?: -1L
        val rt = Runtime.getRuntime()
        val t0 = android.os.SystemClock.elapsedRealtime()
        val free0 = memoryNow().first shr 20; val rss0 = rssMb()
        val java0 = (rt.totalMemory() - rt.freeMemory()) shr 20; val heap0 = rt.totalMemory() shr 20
        val native0 = android.os.Debug.getNativeHeapAllocatedSize() shr 20; val nativeSize0 = android.os.Debug.getNativeHeapSize() shr 20
        System.gc(); System.runFinalization(); System.gc()
        val purged = com.timerpg.street.llm.NativeLlm.purgeNativeHeap()
        val java1 = (rt.totalMemory() - rt.freeMemory()) shr 20; val heap1 = rt.totalMemory() shr 20
        val nativeSize1 = android.os.Debug.getNativeHeapSize() shr 20
        val rss1 = rssMb(); val free1 = memoryNow().first shr 20
        com.timerpg.street.shot.Crumbs.add("dialogue model: trimmed before the load in ${android.os.SystemClock.elapsedRealtime() - t0} ms: " +
            "Java heap $java0 -> $java1 MB used ($heap0 -> $heap1 held), native heap $nativeSize0 -> $nativeSize1 MB ($native0 allocated; " +
            "purge ${when (purged) { 1 -> "done"; 0 -> "not supported"; else -> "no library" }}), RSS $rss0 -> $rss1 MB, free $free0 -> $free1 MB")
    }

    /** Lets the model go to free memory: in the background, or ([force]) with the game in front under critical pressure. */
    private fun unloadForMemory(force: Boolean = false) {
        // the player came back before this ran: keep the model
        if (backend == null || (foreground && !force)) return
        if (force) {
            val since = android.os.SystemClock.elapsedRealtime() - triedAt
            if (triedAt > 0 && since < com.timerpg.street.llm.NpuGate.HOLD_MS) {
                // (the failed try grows the margin itself: NpuGate.record)
                heldSince = 0L; main.removeCallbacks(marginCheck)
                com.timerpg.street.llm.NpuGate.record(app, com.timerpg.street.llm.NpuGate.Try(System.currentTimeMillis(), triedFree, triedNeed, ok = false, why = "memory went critical ${since / 1000} s after the load"))
                com.timerpg.street.llm.NpuGate.clearRunning(app)
            }
            triedAt = 0L
            marginTroubled("memory went critical within the load's hold window")
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
        if (!g.bypass && !g.variant && !g.upgrade) cache.get(g.key)?.let { return g.finish(lineOf(topic, it)) }
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
                // a line written ahead of time holds between two words while the player walks or rides (the model's threads
                // sleep, so the CPU and the heat go to the frames; 0.1.385 stuttered with it writing on six cores while
                // walking) and goes on from the same word once they stand; not one someone waits for, nor overnight
                if (moving && !raised && !night && demand == null && !g.cancelled && !closed) {
                    main.removeCallbacks(watchdog)
                    val h0 = SystemClock.elapsedRealtime()
                    held++
                    while (moving && !g.wanted && demand == null && !g.cancelled && !closed) {
                        try { Thread.sleep(HOLD_POLL_MS) } catch (e: InterruptedException) { break }
                    }
                    heldMs += SystemClock.elapsedRealtime() - h0
                    if (g.wanted && !raised) { raised = true; priority(Process.THREAD_PRIORITY_DEFAULT) }
                    main.postDelayed(watchdog, if (night && g.listeners.isEmpty() && !g.speed) NIGHT_TIMEOUT_MS else TIMEOUT_MS)
                }
                sb.append(piece)
                val done = completeSentences(Dialogue.preview(sb.toString())) ?: return !g.cancelled && !closed
                if (done.first != checked) {
                    checked = done.first
                    val ok = Dialogue.validate(done.first, emptyList(), topic.self, topic.prompt ?: "", topic.recent) ?: return false
                    if (g.listeners.isNotEmpty() && ok != g.shown) { g.shown = ok; g.send(Line(ok, true, false, "AI · writing…")) }
                    if (done.second >= 2) return false
                }
                return !g.cancelled && !closed
            }
            (b as? NativeLlm)?.draftPool = poolFor(npc, g.key)
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
            text = Dialogue.validate(raw, topic.must, topic.self, topic.prompt ?: "", topic.recent)
            // another way of saying it must be another way: the same words again is a failed try
            if (text != null && g.variant && !cache.isNew(g.key, text!!)) text = null
            if (text != null) { end = End.OK; break }
            // its first sentence alone may be good (the slip was in the second): keep that rather than start over
            FIRST.find(Dialogue.preview(raw))?.groupValues?.get(1)?.let { first ->
                text = Dialogue.validate(first, topic.must, topic.self, topic.prompt ?: "", topic.recent)
                if (text != null && g.variant && !cache.isNew(g.key, text!!)) text = null
            }
            if (text != null) { end = End.OK; break }
            val why = Dialogue.whyRejected(raw, topic.must, topic.self, topic.prompt ?: "", topic.recent)
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
                    if (cache.addVariant(g.key, text!!)) { addNight(variants = 1); if (night) noteWritten(g, text!!, "variant") }
                    else { cache.variantFailed(g.key); addNight(rejected = 1) }
                    saveCache()
                } else if (g.upgrade) {
                    cache.replaceLine(g.key, text!!, slot); saveCache(); upgraded++; addNight(lines = 1); noteWritten(g, text!!, "rewrite")
                // (the light model's on-the-spot lines in play are not banked: the main model writes those; writing the bank
                // with it (Writer mode, only it fitted), they are, marked as its)
                } else if (!g.speed && (loadedRole != ModelRole.LIVE || night)) {
                    cache.putLine(g.key, text!!, slot, light = loadedRole == ModelRole.LIVE); saveCache()
                    if (night) { addNight(lines = 1); noteWritten(g, text!!, "line") }
                }
                g.finish(Line(text!!, true, true, "AI", info("live")))
                // a line written ahead: its voice can be made ahead too
                if (!g.speed && g.listeners.isEmpty()) onWritten?.invoke(npc, text!!)
            }
            End.REJECTED -> {
                failures = 0
                if (night && !g.speed) addNight(rejected = 1)
                if (g.variant) { cache.variantFailed(g.key); saveCache() }
                // (a rewrite that fails keeps the light model's line; the light model's failure leaves it to the main one)
                else if (g.upgrade || (night && loadedRole == ModelRole.LIVE)) lock.withLock { nightSkip += g.key }
                else if (!g.speed && loadedRole != ModelRole.LIVE) { cache.putTemplate(g.key, g.slot ?: slotOf(g.key)); saveCache() }
                g.finish(template(topic, "built-in · the AI's reply did not pass the check", info = info("template", "the reply did not pass the check")))
            }
            End.TIMEOUT -> {
                // overnight: given up for this night only (no one waited for it)
                if (night && g.listeners.isEmpty() && !g.speed) {
                    addNight(timedOut = 1, failure = "a line took longer than ${NIGHT_TIMEOUT_MS / 1000} s ($tokens tokens in ${ms / 1000} s) and was given up")
                    if (g.variant) cache.variantFailed(g.key) else lock.withLock { nightSkip += g.key }
                } else if (++timeouts >= 2 && pregenOff == null) pregenOff = "The model is too slow on this phone to write lines ahead; it only answers when you ask."
                g.finish(template(topic, "built-in · the AI took too long", info = info("template", "took too long")))
            }
            End.CANCELLED -> {
                requeue(g)
                g.finish(template(topic, "built-in"))
            }
            End.ERROR -> {
                if (night && !g.speed) addNight(failed = 1, failure = "the model failed on a line: ${(error ?: "unknown").take(160)}")
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

    private fun setState(s: ModelState, detail: String) = _status.update { it.copy(state = s, detail = detail, npuWait = null) }

    /** In play, waiting for the NPU copy's room ([ModelRoles.Wait.device]): [short] for the HUD's chip, [detail] for the panel. */
    private fun setNpuWait(short: String, detail: String) = _status.update { it.copy(state = ModelState.WAITING, detail = detail, npuWait = short) }

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

    private fun addNight(lines: Int = 0, variants: Int = 0, rejected: Int = 0, timedOut: Int = 0, failed: Int = 0, failure: String? = null) {
        nightCount.updateAndGet { it.copy(lines = it.lines + lines, variants = it.variants + variants, rejected = it.rejected + rejected,
            timedOut = it.timedOut + timedOut, failed = it.failed + failed, lastFailure = failure ?: it.lastFailure) }
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
                // their own secret ("are you hiding anything?"); a secret about others or the town is gossip: what they have
                // heard ("do you know any secrets about this town?" asks what they know, not what they keep)
                has("hiding", "hide ", "hidden", "your secret", "your own secret", "keeping anything", "keeping something",
                    "keep anything", "keep something", "have any secrets", "have a secret", "got any secrets", "got a secret") -> "secret"
                has("secret", "rumor", "rumour", "gossip", "heard anything", "heard any", "news", "going on", "what's new", "whats new") -> "rumors"
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

        private const val EXAMPLE = "\nExample of the kind of answer:"

        /**
         * [prompt] without its example answer: the example answers the menu's question, and in a free question the model
         * copied it ("It is a lovely day for it, isn't it?" three times in the owner's first conversation log).
         */
        fun withoutExample(prompt: String): String {
            val at = prompt.indexOf(EXAMPLE).takeIf { it >= 0 } ?: return prompt
            val end = prompt.indexOf("<|im_end|>", at).takeIf { it >= 0 } ?: return prompt
            return prompt.substring(0, at) + prompt.substring(end)
        }

        /** [prompt] with their last replies in this conversation ([recent], at most 2: prefill time matters) and the rule not to say them again. */
        fun withRecent(prompt: String, recent: List<String>): String {
            val r = recent.takeLast(2).filter { it.isNotBlank() }
            if (r.isEmpty()) return prompt
            val line = "You already said: " + r.joinToString(" ") { "\"$it\"" } + " Do not repeat those words."
            val at = prompt.indexOf(EXAMPLE).takeIf { it >= 0 } ?: prompt.indexOf("<|im_end|>").takeIf { it >= 0 } ?: return prompt
            return prompt.substring(0, at) + "\n" + line + prompt.substring(at)
        }

        /**
         * The topic as a free question asks it: the player's [said] words in place of the menu's question, the example
         * answer left out, what they remember ([remembered]) and their last replies ([recent], those not already in a
         * memory) in the facts, and [recent] for the check. Words it must name only where the topic is the secret being
         * told (the player asked about it); otherwise a question of the player's own need not name them.
         */
        fun freeTopic(topic: Topic, said: String, remembered: List<String>, recent: List<String>): Topic {
            fun clean(s: String) = s.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim()
            val words = clean(said).take(400)
            val mem = remembered.map { m -> clean(m).take(600) }
            val last = recent.takeLast(3).map { clean(it).take(300) }
            // (the check knows the last 3; the prompt quotes the last 2, and not one a memory already quotes: prefill matters)
            val newOnes = last.takeLast(2).filter { r -> mem.none { it.contains(r) } }
            val prompt = topic.prompt?.let { withRecent(withMemories(withoutExample(withQuestion(it, words)), mem), newOnes) }
            val keep = if (topic.intent == "rumors" || topic.intent == "secret") topic.must else emptyList()
            return topic.copy(prompt = prompt, must = keep, recent = last)
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
        /** How often a line held while the player moves looks whether it may go on (ms). */
        const val HOLD_POLL_MS = 50L
        /** Native errors that come from the prompt itself, not from the model: they would happen every time. */
        private val PROMPT_ERRORS = listOf("prompt too long", "could not tokenize", "empty prompt")
        private const val SETTINGS = "v1 tokens=$MAX_TOKENS temperature=$TEMPERATURE"
        private const val NO_MODEL_TEXT = "No model yet: people use their built-in lines. Download it from the AI panel, or choose the model file (Qwen3.5-9B-Q4_K_M.gguf, about 5.7 GB) to give them their own words. It is copied into the game once."
        private const val READY_TEXT = "Ready. Lines near you are written ahead of time."
        private const val LIVE_READY_TEXT = "Ready: the light model answers in play. Lines ahead of time are written by the main model (overnight, or when it fits)."
        /** The pause and load hold while the NPU fit test runs. */
        const val FIT_TEST = "fit test"
        /** The pause reason while the light model is loaded (pre-generation is the main model's). */
        const val LIVE_PAUSE = "light model loaded"
        /** N-gram guesses ahead by default ([ngramWanted]). */
        const val NGRAM_DEFAULT = 2
        /** The most bank lines in a person's n-gram pool ([poolFor]). */
        const val POOL_LINES = 24
        /** How many of the nearest unwritten lines' people the idle light model pre-bakes ([bakeJobLocked]). */
        const val BAKE_AHEAD = 12
        /** The most the saved model states may take on flash (~55 MB each for the light model: ~55 fact sheets). */
        const val STATE_BUDGET_BYTES = 3L shl 30
        /** The prompt's batch on the NPU unless the panel set one ([batchFor]). */
        const val NPU_BATCH = 128
        /** How long the town's build waits for the light model's early load ([loadBeforeTown]; ~6 s measured on the S25). */
        const val EARLY_WAIT_MS = 20_000L
        /** What the town's build takes of the phone's free memory, about (game PSS 165 -> 2470-2550 MB, measured on
         *  0.1.848): free beyond the light copy's need by this much lets the build go beside the load rather than wait. */
        const val TOWN_BUILD_MB = 2400L

        /**
         * A session starts (the town's loading thread, before it reads the town): the light model's NPU copy loads first
         * when it fits (MEMORY_AUDIT.md, M1: at app start 3665 MB free plus what Android finds covers the need; with the
         * town built ~3050), and the town's build waits for it at most [EARLY_WAIT_MS], or not at all when the phone has
         * room for both. Safe as every load in play: the gate decides, the valve watches, memory critical later lets the
         * model go before the game. Nothing when no engine is running, a model is loaded, or the load would not fit (the
         * town goes first then, as before). Off the main thread.
         */
        fun loadBeforeTown() {
            val e = synchronized(this) { shared?.takeIf { !it.closed } } ?: return
            e.loadEarly()
        }

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
