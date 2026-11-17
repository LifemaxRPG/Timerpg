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
enum class ModelState { STARTING, NO_ENGINE, NO_MODEL, COPYING, LOADING, READY, UNLOADED, ERROR }

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
    val speedTesting: Boolean = false,
    val speed: SpeedResult? = null,
    /** How the model's work splits between devices (fewer splits: less of it falls back to the CPU). */
    val loadInfo: String? = null,
)

/** A line in the dialogue panel: the model's ([ai]) or the game's template; [final] once it will not change. */
data class Line(val text: String, val ai: Boolean, val final: Boolean, val note: String)

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
    class Request internal constructor(val first: Line, internal val onLine: ((Line) -> Unit)?) {
        @Volatile internal var live = onLine != null
        /** Stop receiving lines (the panel closed or another topic was picked). Main thread. */
        fun close() { live = false }
    }

    private class Target(val npc: Npc, val topic: Topic)

    /** A line being written. */
    private class Gen(val key: String, val target: Target, val bypass: Boolean = false, val speed: Boolean = false) {
        val listeners = CopyOnWriteArrayList<Request>()
        @Volatile var cancelled = false
        @Volatile var timedOut = false
        @Volatile var shown = ""
        @Volatile var done = false
        /** Someone started waiting for it while it was being written ahead: the worker goes to full speed. */
        @Volatile var wanted = false
    }

    private class Planned(val target: Target, val job: PregenQueue.Job)

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
    // worker only
    private var failures = 0
    private var timeouts = 0
    private var engineError: String? = null

    private val cache = ReplyCache(File(app.filesDir, "dialogue-cache.json"))
    private val _status = MutableStateFlow(AiStatus())
    private val _data = MutableStateFlow<DialogueData?>(null)

    /** The AI's state and speed numbers. */
    val status: StateFlow<AiStatus> = _status
    /** The town's people and what they say (null until loaded, or if dialogue.json is missing or for another town). */
    val data: StateFlow<DialogueData?> = _data

    private val thread = Thread({ loop() }, "dialogue-llm").apply { isDaemon = true }

    // ------------------------------------------------------------------------------------------------ the public side

    /**
     * Asks [npc] about [topic]. Returns at once with the line to show now (the cached line, or the template); if the model
     * can do better, it writes the reply (ahead of any pre-generation), streaming it to [onLine] on the main thread and
     * ending with a [Line.final] line: the model's if it passes the check, else the template.
     */
    fun ask(npc: Npc, topic: Topic, onLine: (Line) -> Unit): Request {
        val prompt = topic.prompt ?: return Request(template(topic, "built-in"), null)
        val key = keyOf(prompt)
        cache.get(key)?.let { return Request(lineOf(topic, it), null) }
        if (closed || _status.value.state != ModelState.READY) return Request(template(topic, "built-in"), null)
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
    fun askFree(npc: Npc, said: String, remembered: List<String> = emptyList(), onLine: (Line) -> Unit): Pair<Topic, Request>? {
        val topic = topicFor(npc, said) ?: return null
        fun clean(s: String) = s.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim()
        val words = clean(said).take(400)
        val prompt = topic.prompt?.let { withMemories(withQuestion(it, words), remembered.map { m -> clean(m).take(600) }) }
        val asked = topic.copy(prompt = prompt)
        return topic to ask(npc, asked, onLine)
    }

    /** Where the player is: building [index] (-1 between buildings) of [street]. Re-sorts the pre-generation queue. */
    fun setPlayer(street: Int, index: Int) {
        val changed = lock.withLock { queue.setPlayer(street, index) }
        if (changed) refresh()
    }

    /** Holds pre-generation (the corner turn, the app in the background, low memory); on-demand lines still come. */
    fun pause(reason: String) {
        lock.withLock {
            if (!pauses.add(reason)) return
            // stop the line being written ahead of time, unless someone is waiting for it
            val cur = running
            if (cur != null && !cur.speed && cur.listeners.none { it.live }) cancelLocked(cur)
        }
        refresh()
    }

    fun resume(reason: String) {
        lock.withLock { if (pauses.remove(reason)) wake.signal() }
        refresh()
    }

    /** The activity is visible ([on]) or not. Coming back reloads a model that was unloaded to free memory. */
    fun onForeground(on: Boolean) {
        foreground = on
        if (!on) {
            pause("background")
            // a line the open panel was waiting for stops too (it goes back in the queue; the panel keeps the template)
            lock.withLock { running?.let { if (!it.speed) cancelLocked(it) } }
            return
        }
        resume("background")
        resume("memory")
        if (unloadedForMemory) { unloadedForMemory = false; command { loadModel() } }
    }

    /** Memory pressure: pause pre-generation, and under real pressure let go of the model (it reloads on return). */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                pause("memory")
                // stop what runs, including a model still loading (it would only be unloaded again)
                lock.withLock { running?.let { cancelLocked(it) } ?: cancelBackendLocked() }
                command { unloadForMemory() }
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

    val fastCpuWanted: Boolean get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getBoolean("repack", false)

    /** The device chosen in the AI panel; -1 until one is: the NPU then when there is one (fastest on the S25 Ultra:
     *  8.8 tok/s and a 2.4 s line, against 4.1 tok/s on the CPU and 4.2 on the GPU, measured on the phone). */
    private val deviceWanted: Int get() = app.getSharedPreferences("ai", Context.MODE_PRIVATE).getInt("device", -1)
    @Volatile private var gpuNote: String? = null

    /** Loads the model again (after an error, or after it was unloaded). */
    fun reload() = command { loadModel() }

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
        lock.withLock {
            running?.let { cancelLocked(it) } ?: cancelBackendLocked()
            wake.signal()
        }
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
            if (pauses.isEmpty() && pregenOff == null && backend?.loaded == true) {
                val j = queue.poll()
                if (j != null) {
                    val p = planned[j.key]
                    if (p == null || cache.get(j.key) != null) continue
                    val g = Gen(j.key, p.target)
                    running = g
                    return g
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
        loadData()
        cache.load()
        if (planned.isNotEmpty()) cache.retain(planned.keys)
        ModelFiles.cleanPartial(app)
        refresh()
        loadModel()
    }

    /** dialogue.json, checked against street.json (same town and year), and every line the model could write. */
    private fun loadData() {
        val d = try {
            DialogueData.parse(app.assets.open("dialogue.json").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "no dialogue: $e")
            return
        }
        // the buildings as the street shows them (street.json is the renderer's source of truth)
        val spots = HashMap<String, PregenQueue.Spot>()
        var lengths = IntArray(0)
        try {
            val sj = JSONObject(app.assets.open("street.json").bufferedReader().use { it.readText() })
            if (sj.optString("town") != d.town || sj.optInt("year") != d.year) {
                Log.w(TAG, "dialogue.json is ${d.town} ${d.year} but street.json is ${sj.optString("town")} ${sj.optInt("year")}: no dialogue")
                return
            }
            val streets = sj.optJSONArray("streets")
            if (streets != null) {
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
        planned = byKey.mapValues { (k, v) -> Planned(v.first, PregenQueue.Job(k, v.second, orders[k]!!)) }
        lock.withLock {
            val old = queue
            queue = PregenQueue(lengths).also { it.setPlayer(old.street, old.position) }
        }
        _data.value = d
    }

    private fun loadModel() {
        if (closed) return
        if (backend?.loaded == true) { setState(ModelState.READY, READY_TEXT); enqueueAll(); return }
        val b = backend ?: createBackend()
            ?: return setState(ModelState.NO_ENGINE, engineError?.let { "$it People use their built-in lines." }
                ?: "This build has no AI engine, so people use their built-in lines.")
        // on the NPU, the Q4_0 copy when the tuner found it faster there (and it is downloaded)
        val q40 = java.io.File(ModelFiles.dir(app), ModelFiles.NPU_Q4_0)
        val wantsQ40 = app.getSharedPreferences("npu", Context.MODE_PRIVATE).getString("model", null) == "q4_0"
        val onNpu = (b as? com.timerpg.street.llm.NativeLlm)?.let { deviceWanted == 2 || (deviceWanted < 0 && it.accelerators and 2 != 0) } ?: false
        val f = if (wantsQ40 && onNpu && q40.length() == 5_379_417_312L) q40 else ModelFiles.file(app)
        val problem = ModelFiles.problem(f)
        _status.update { it.copy(hasModel = problem == null, modelMb = if (f.isFile) f.length() shr 20 else 0) }
        if (problem == ModelFiles.MISSING) return setState(ModelState.NO_MODEL, NO_MODEL_TEXT)
        if (problem != null) return setState(ModelState.ERROR, problem)
        memoryProblem(f.length())?.let { return setState(ModelState.ERROR, it) }
        setState(ModelState.LOADING, "Loading the model (${f.length() shr 20} MB)…")
        var thrown: String? = null
        val native = b as? com.timerpg.street.llm.NativeLlm
        native?.device = deviceWanted.let { if (it >= 0) it else if ((native?.accelerators ?: 0) and 2 != 0) com.timerpg.street.llm.NativeLlm.NPU else com.timerpg.street.llm.NativeLlm.CPU }
        native?.fastCpu = fastCpuWanted
        // loading holds the AI lane: speech, memory and the voices do not load beside it (their memory added to a load's
        // is what took the phone down)
        var ok = com.timerpg.street.llm.AiLane.run("dialogue") {
            try { b.load(f.path, 0, CONTEXT_TOKENS) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
        }
        if (!ok && native != null && native.device != com.timerpg.street.llm.NativeLlm.CPU && !closed) {
            // the GPU or NPU could not take it: the CPU then (and say so)
            val which = if (native.device == com.timerpg.street.llm.NativeLlm.NPU) "NPU" else "GPU"
            native.device = com.timerpg.street.llm.NativeLlm.CPU
            gpuNote = "The $which could not load the model (${native.error ?: "unknown"}); it runs on the CPU."
            ok = com.timerpg.street.llm.AiLane.run("dialogue") {
                try { b.load(f.path, 0, CONTEXT_TOKENS) } catch (t: Throwable) { thrown = t.message ?: t.javaClass.simpleName; false }
            }
        }
        val acc = native?.accelerators ?: 0
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
        setState(ModelState.READY, READY_TEXT)
        enqueueAll()
    }

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
        safely("close") { b.close() }
    }

    /** Enough free memory to load the model without the system killing the game for it? */
    private fun memoryProblem(fileBytes: Long): String? = try {
        val am = app.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        // the weights are mapped from the file (the system can drop and reread them), the KV cache and buffers are not
        val need = fileBytes / 2 + (300L shl 20)
        if (am != null && (mi.lowMemory || mi.availMem < need))
            "Not enough free memory to load the model (${mi.availMem shr 20} MB free, about ${need shr 20} MB needed). Close other apps and tap Retry."
        else null
    } catch (e: Exception) { null }

    private fun unloadForMemory() {
        // the player came back before this ran: keep the model
        if (backend == null || foreground) return
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
        if (!g.bypass) cache.get(g.key)?.let { return g.finish(lineOf(topic, it)) }
        val b = backend
        if (b == null || !b.loaded) {
            g.finish(template(topic, "built-in · the AI is not ready"))
            if (g.speed) _status.update { it.copy(speedTesting = false, speed = SpeedResult(npc.name, topic.intent, "", false, 0, LlmStats(), "The model is not loaded.")) }
            return
        }
        // someone is waiting: full speed; ahead of time: stay out of the game's way
        var raised = g.speed || g.listeners.isNotEmpty()
        priority(if (raised) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND)
        _status.update { it.copy(working = if (g.speed) "speed test (${npc.name})" else "${npc.name} · ${topic.intent}") }
        val t0 = SystemClock.elapsedRealtime()
        var end = End.REJECTED
        var text: String? = null
        var lastRaw = ""
        var error: String? = null
        for (attempt in 1..MAX_TRIES) {
            if (g.cancelled || closed) { end = End.CANCELLED; break }
            if (attempt > 1) { g.shown = ""; g.send(template(topic, "built-in · the AI is trying again…", final = false)) }
            if (g.wanted && !raised) { raised = true; priority(Process.THREAD_PRIORITY_DEFAULT) }
            val sb = StringBuilder()
            val watchdog = Runnable { g.timedOut = true; lock.withLock { cancelLocked(g) } }
            main.postDelayed(watchdog, TIMEOUT_MS)
            var raw: String? = try {
                com.timerpg.street.llm.AiLane.run("dialogue") { b.generate(prompt, MAX_TOKENS, TEMPERATURE) { piece ->
                    // the worker's own thread: someone began waiting for this line, so it gets full speed now
                    if (g.wanted && !raised) { raised = true; priority(Process.THREAD_PRIORITY_DEFAULT) }
                    sb.append(piece)
                    if (g.listeners.isNotEmpty()) {
                        val s = Dialogue.preview(sb.toString())
                        if (s.isNotEmpty() && s != g.shown) { g.shown = s; g.send(Line(s, true, false, "AI · writing…")) }
                    }
                    !g.cancelled && !closed
                } }
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
            if (g.timedOut) { end = End.TIMEOUT; break }
            if (g.cancelled || closed) { end = End.CANCELLED; break }
            if (raw == null) { end = End.ERROR; break }
            lastRaw = raw
            text = Dialogue.validate(raw, topic.must, topic.self, topic.prompt ?: "")
            if (text != null) { end = End.OK; break }
        }
        val ms = SystemClock.elapsedRealtime() - t0
        when (end) {
            End.OK -> {
                failures = 0
                if (!g.speed) { cache.putLine(g.key, text!!); cache.save() }
                g.finish(Line(text!!, true, true, "AI"))
            }
            End.REJECTED -> {
                failures = 0
                if (!g.speed) { cache.putTemplate(g.key); cache.save() }
                g.finish(template(topic, "built-in · the AI's reply did not pass the check"))
            }
            End.TIMEOUT -> {
                if (++timeouts >= 2 && pregenOff == null) pregenOff = "The model is too slow on this phone to write lines ahead; it only answers when you ask."
                g.finish(template(topic, "built-in · the AI took too long"))
            }
            End.CANCELLED -> {
                requeue(g)
                g.finish(template(topic, "built-in"))
            }
            End.ERROR -> {
                g.finish(template(topic, "built-in · the AI failed"))
                if (error != null && PROMPT_ERRORS.any { error!!.contains(it) }) {
                    // this prompt will always fail (too long for the context, cannot be tokenized): the template it is,
                    // without taking the model down for the rest of the town
                    if (!g.speed) { cache.putTemplate(g.key); cache.save() }
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

    private fun template(topic: Topic, note: String, final: Boolean = true) = Line(topic.template, ai = false, final = final, note = note)

    private fun lineOf(topic: Topic, e: ReplyCache.Entry): Line =
        if (e.line != null) Line(e.line, ai = true, final = true, note = "AI")
        else template(topic, "built-in · the AI's reply did not pass the check")

    private fun setState(s: ModelState, detail: String) = _status.update { it.copy(state = s, detail = detail) }

    private fun stats(b: LlmBackend) {
        try { val s = b.stats; _status.update { it.copy(stats = s) } } catch (t: Throwable) { }
    }

    private fun refresh() {
        val (q, p) = lock.withLock { queue.size to pauses.sorted().joinToString(", ") }
        val lines = cache.lines
        val builtIn = cache.templates
        _status.update { it.copy(lines = lines, builtIn = builtIn, queued = q, paused = p, pregenOff = pregenOff) }
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
        const val CONTEXT_TOKENS = 1024
        /** Tries per line before it stays the template. */
        const val MAX_TRIES = 2
        /** A reply that takes longer than this is given up (the template stays). */
        const val TIMEOUT_MS = 45_000L
        /** Native errors that come from the prompt itself, not from the model: they would happen every time. */
        private val PROMPT_ERRORS = listOf("prompt too long", "could not tokenize", "empty prompt")
        private const val SETTINGS = "v1 tokens=$MAX_TOKENS temperature=$TEMPERATURE"
        private const val NO_MODEL_TEXT = "No model yet: people use their built-in lines. Download it from the AI panel, or choose the model file (Qwen3.5-9B-Q4_K_M.gguf, about 5.7 GB) to give them their own words. It is copied into the game once."
        private const val READY_TEXT = "Ready. Lines near you are written ahead of time."

        fun keyOf(prompt: String): String = ReplyCache.keyOf(prompt, SETTINGS)

        private var shared: DialogueEngine? = null

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
