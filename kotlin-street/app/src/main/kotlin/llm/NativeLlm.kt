package com.timerpg.street.llm

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * [LlmBackend] on llama.cpp, natively (libtimerpg_llm.so, built from app/src/main/cpp; arm64 phones only).
 *
 * Nothing here throws on a bad model, a missing library or low memory: [load] returns false with [error] set, and
 * [generate] returns "" with [lastGenerateError] set. Both block, so call them off the main thread (on the main thread
 * they refuse instead of freezing the game), or use [loadAsync]/[generateAsync]. One generation runs at a time; others
 * wait their turn. Give it a [context] to have the model freed when Android runs low on memory ([onTrimMemory]).
 */
class NativeLlm(context: Context? = null) : LlmBackend {

    @Volatile override var loaded: Boolean = false
        private set

    /** Where the model runs from the next [load]: [CPU], [GPU] (the Adreno, OpenCL) or [NPU] (Hexagon). */
    @Volatile var device = CPU
    /** The CPU's repacked kernels from the next load: faster prompts, but the whole model copied into memory the system
     *  cannot reclaim (off: the weights stay mapped from the file, so speech and the voices fit beside them). */
    @Volatile var fastCpu = false
    /** Tokens the model's MTP head guesses ahead from the next load (0: off; needs a model file with the head). */
    @Volatile var mtp = 0
    /**
     * Tokens guessed ahead by n-gram lookup from the next load (0: off; NOVEL_OPTIMIZATIONS.md, idea 8): what followed
     * the reply's last few tokens where they appeared before (the fact sheet, the conversation in the prompt, the reply
     * so far, [draftPool]), checked in one pass with the next token. Lossless: the same tokens greedily, the same
     * distribution sampling. Tried before the MTP head. A hybrid model keeps this many steps of its recurrent state to
     * step back over wrong guesses (counted in [workBytes] through llama.cpp's own buffer sizes).
     */
    @Volatile var ngram = 0
    /** Lines the n-gram guesses may also copy from in the next [generate]: the person's lines in the bank, one a line. */
    @Volatile var draftPool: String = ""
    private var poolSent: String? = null
    /**
     * validate()-aware sampling in the next [generate]s (docs/ENGINE_TAILORING.md, idea 2; native guide.cpp): tokens that
     * would certainly fail the reply check (an action, AI talk, a stage direction, a name or place the prompt's facts do not
     * have, four words of one of [guideRecent] again) are never sampled. [guideSelf]: the speaker's full name;
     * [guideRecent]: their last replies. Off by default ("npu" pref "guided") until the owner has judged it.
     */
    @Volatile var guided = false
    @Volatile var guideSelf: String = ""
    @Volatile var guideRecent: List<String> = emptyList()
    /**
     * From the next load: every guess-and-check pass the same length (a short guess padded with filler, checked like any
     * guess, so lossless), so llama.cpp and the NPU backend reuse one graph instead of rebuilding it whenever the length
     * changes (ENGINE_TAILORING.md, idea 4; "npu" pref "fixed_steps").
     */
    @Volatile var fixedSteps = false
    /**
     * Sequences the context holds from the next load, for [generateBatch] (the overnight writer: several prompts in one
     * pass; ENGINE_TAILORING.md, idea 6). 0 or 1: one, as in play. Each costs one more recurrent state (and its rollback
     * planes) and room in the attention cache; [workBytes] counts them after the load.
     */
    @Volatile var batchSeqs = 0
    /** What the loaded context holds for [generateBatch] (1: one sequence; then [generateBatch] runs them one by one). */
    val batchSeqsLoaded: Int get() = if (handle == 0L) 0 else runCatching { nativeBatchSeqs(handle) }.getOrDefault(0)
    /** The attention cache (KV) in 8 bits from the next load (about half its memory; falls back to 16 where it cannot). */
    @Volatile var kvQ8 = false
    /** The batch the prompt is processed in from the next load (512, 256 or 128 tokens): smaller shrinks the compute
     *  buffers beside the weights, at some cost in prompt speed. */
    @Volatile var batch = 512
    /**
     * On the GPU or NPU from the next load: the output head stays on the CPU, mapped from the file, when the model ties it
     * to its token embeddings (Qwen3.5-4B, the light model). llama.cpp keeps the embeddings mapped on the CPU (the input
     * layer always is) and puts a second, copied, version of the same tensor on the device as the output head: the light
     * model's 497 MB Q6_K table was both mapped and copied (MEMORY_AUDIT.md, M3: 2501 MB copied + 497 mapped from a
     * 2463 MB file). Kept mapped, the copy is ~2004 MB; the head's one matrix-vector product per generated token runs on the
     * CPU (it reads the same 521 MB either way). No effect on a model with its own output weights.
     */
    @Volatile var headMapped = true
    /**
     * Where a recurrent or hybrid model (Qwen3.5) keeps its saved states from the next load: files on flash (written
     * once, read back mapped, kept across sessions and shared with the writer's process), instead of up to 8 copies of
     * tens of MB each in RAM the system cannot take back (docs/NOVEL_OPTIMIZATIONS.md, idea 2). Null: in RAM as before.
     */
    @Volatile var stateDir: File? = null
    /** The most the state files of every model may take on flash; the least recently used go first. */
    @Volatile var stateBudgetBytes: Long = 3L shl 30
    /** With [stateDir]: the shortest state (the shared rules) also stays in RAM. Off by default: on a computer a
     *  restore from the mapped file in the page cache cost about what one from RAM did (docs/CONVERSATION.md). */
    @Volatile var hotState = false
    /** With [stateDir]: which states a reply writes from the next load (1 the shared rules, 2 the person, 4 the whole
     *  fact sheet; [bake] writes all three). */
    @Volatile var statePoints = 7
    /** The loaded model's cache is in 8 bits. */
    val kvQ8Used: Boolean get() = handle != 0L && runCatching { nativeKvQ8(handle) }.getOrDefault(false)
    /**
     * What the last load allocated, as llama.cpp logged it (MB): weights mapped from the file, weights copied (to the NPU
     * or GPU, or repacked), the KV cache, the recurrent state, compute and output buffers. Read right after [load].
     */
    var loadMem: DoubleArray = DoubleArray(5)
        private set
    /** The model's own working memory beside its weights (KV cache, recurrent state, compute buffers), in bytes, and the
     *  saved states it keeps in RAM ([com.timerpg.street.dialogue.ModelRoles.residentStateBytes]: none with [stateDir]
     *  unless [hotState], else up to 8). */
    val workBytes: Long get() = ((loadMem[2] + loadMem[3] + loadMem[4]) * 1048576.0).toLong() +
        com.timerpg.street.dialogue.ModelRoles.residentStateBytes(if (stateDir == null) 8 else if (hotState) 1 else 0,
            (loadMem[2] * 1048576.0).toLong(), (loadMem[3] * 1048576.0).toLong())
    /** Where the loaded model runs. */
    val runsOn: Int get() = if (handle == 0L) CPU else runCatching { nativeDevice(handle) }.getOrDefault(CPU)
    /**
     * This phone and build's accelerators: bit 0 a GPU, bit 1 an NPU. The native side knows them only once llama.cpp's
     * backends are loaded (libggml-opencl.so, libggml-hexagon.so: [initBackend], otherwise first done inside [load]);
     * before that it reports none. So this loads them first (and the library if need be), except on the main
     * thread (tens to hundreds of ms the first time), where it reports what is known so far. Build 0.1.848 asked before
     * anything had loaded them, read "no NPU", and in play nothing then loads to load them: the NPU was never found.
     */
    val accelerators: Int get() {
        if (!backendReady) {
            if (isMainThread()) return runCatching { nativeAccelerators() }.getOrDefault(0)
            if (libraryError != null) return 0
            initBackend()
        }
        return runCatching { nativeAccelerators() }.getOrDefault(0)
    }
    @Volatile override var error: String? = null
        private set
    @Volatile override var stats: LlmStats = LlmStats()
        private set
    /** Why the last [generate] returned early or empty ("cancelled", "prompt too long: ..."), or null. */
    @Volatile var lastGenerateError: String? = null
        private set
    /** Which CPU kernels llama.cpp runs on this phone, for the speed panel (e.g. "...armv8.6_1.so (...); cpu: neon dotprod fp16 i8mm"). */
    val backendInfo: String get() = libraryError ?: initBackend()

    /** Loads llama.cpp's backends once per process (the CPU kernels, then the GPU's and the NPU's libraries if this build
     *  has them and the phone's drivers open them) and says what loaded; [accelerators] is right only after it. */
    private fun initBackend(): String {
        if (!backendReady) powerEnv()
        return (runCatching { nativeBackendInfo() }.getOrNull() ?: "unknown").also { backendReady = true }
    }

    /**
     * The NPU's power vote (the "npu" preferences, docs/CONVERSATION.md, "The NPU's power vote"), read once, before
     * anything loads the backends. "power": "auto" (the default, also when unset) votes full power (burst) only while a
     * reply, bake or load computes and releases it (DCVS on, the DSP may sleep) "power_hold_ms" (default 400) after the
     * last one ends, and at once when the model unloads. Any other name is ggml-hexagon's GGML_HEXAGON_POWER_MODE for the
     * whole process, as before: "burst" (clocks pinned at maximum, the DSP kept awake), "balanced",
     * "sustained_high_performance", ...
     */
    private fun powerEnv() {
        val prefs = appContext?.getSharedPreferences("npu", Context.MODE_PRIVATE)
        val mode = prefs?.getString("power", null)?.trim()?.takeIf { it.isNotEmpty() } ?: "auto"
        val hold = runCatching { prefs?.getInt("power_hold_ms", 400) }.getOrNull() ?: 400
        if (mode != "auto") runCatching { if (android.system.Os.getenv("GGML_HEXAGON_POWER_MODE") == null) android.system.Os.setenv("GGML_HEXAGON_POWER_MODE", mode, false) }
        runCatching { nativeSetNpuPower(mode.toByteArray(Charsets.UTF_8), hold) }
        npuKnobs(prefs)
    }

    /**
     * The NPU knobs to A/B on the S25 (docs/ENGINE_TAILORING.md, idea 4; "npu" preferences, read once per process before
     * the backends load; NpuTune's own settings, when tuning or tuned, win):
     * - "opencl_at_start" (default false): the GPU's backend (OpenCL) loads with the others; off, only when a load asks
     *   for the GPU (in play the model runs on the NPU: the Adreno driver's start-up and memory are skipped).
     * - "play_sessions" (default 0: ggml-hexagon's 2): HTP sessions in the game's process (the light 4B fits one; one
     *   session means no hand-off between sessions per token). The writer's process (the 9B needs 2) keeps 2.
     * - "oppoll" (default false): GGML_HEXAGON_OPPOLL=1 in the game's process: completions polled instead of waited for
     *   on an interrupt (one ARM core spins while the NPU works; lower latency per op). Never in the writer's process.
     */
    private fun npuKnobs(prefs: android.content.SharedPreferences?) {
        val writer = runCatching { android.app.Application.getProcessName().endsWith(":writer") }.getOrDefault(false)
        val gpuAtStart = prefs?.getBoolean("opencl_at_start", false) ?: false
        runCatching { nativeSetGpuBackend(gpuAtStart) }
        if (writer) return
        val sessions = runCatching { prefs?.getInt("play_sessions", 0) }.getOrNull() ?: 0
        if (sessions in 1..4) runCatching {
            if (android.system.Os.getenv("GGML_HEXAGON_DEVICES") == null) android.system.Os.setenv("GGML_HEXAGON_DEVICES", sessions.toString(), false)
        }
        if (prefs?.getBoolean("oppoll", false) == true) runCatching { android.system.Os.setenv("GGML_HEXAGON_OPPOLL", "1", false) }
    }

    /**
     * Why [accelerators] has no NPU, specifically (for the HUD and the reports): the engine's library missing, the NPU's
     * library not in this build, or in it but no HTP session opened (the backends' own line says what did load).
     * Off the main thread.
     */
    fun npuMissingWhy(ctx: Context?): String {
        libraryError?.let { return it }
        val info = initBackend()
        if (accelerators and 2 != 0) return "the NPU is there"
        val lib = runCatching { (ctx?.classLoader as? dalvik.system.BaseDexClassLoader)?.findLibrary("ggml-hexagon") }.getOrNull()
        return if (ctx != null && lib == null) "this build has no NPU library (libggml-hexagon.so is not in the APK: built without hexagon.sdk); backends: $info"
        else "the NPU library loaded no HTP device (the phone's Hexagon driver or session did not open; GGML_HEXAGON_NDEV 2); backends: $info"
    }
    /** Refuse to load when the phone has clearly too little free memory for the model (see [load]). */
    @Volatile var memoryCheck: Boolean = true
    /**
     * Null if the engine can run on this phone (its native libraries load); otherwise why not. Loads the libraries the
     * first time (a few MB, tens of ms): call it off the main thread. Lets the game say so before anyone copies a model.
     */
    val unavailable: String? get() = libraryError

    /** The native engine; only touched while holding [genLock] (except [nativeCancel], see [cancel]). */
    @Volatile private var handle = 0L
    /** Held for the whole of a load/generate/unload: the model does one thing at a time. */
    private val genLock = ReentrantLock(true)

    /** One load or generate call, so that a cancel can be aimed at it (see [cancel] and [generateAsync]). */
    private class Request { @Volatile var cancelled = false }
    /** Guards [running] and [cancelSeq]; held only briefly, never while the model works. */
    private val state = Any()
    private var running: Request? = null
    private var cancelSeq = 0L

    private val appContext = context?.applicationContext
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) = this@NativeLlm.onTrimMemory(level)
        override fun onLowMemory() = this@NativeLlm.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    init {
        appContext?.registerComponentCallbacks(memoryCallbacks)
    }

    // ------------------------------------------------------------------------------------------------ LlmBackend

    /**
     * Loads [path] (a GGUF file; never in the APK). [threads] <= 0 uses [defaultThreads]. Fails (false, [error]) on the
     * main thread, without the native library (not an arm64 phone), on a missing/unreadable/invalid file, when free
     * memory is clearly too low (the whole model is read for every token, so it must fit in RAM) and when llama.cpp
     * cannot load it. Replaces a loaded model. [cancel] stops it.
     */
    override fun load(path: String, threads: Int, contextTokens: Int): Boolean {
        if (isMainThread()) return loadFailed("load() was called on the main thread; call it from a background thread")
        // from inside onToken the lock is this thread's own: a load would run under the generation still on the stack
        check(!genLock.isHeldByCurrentThread) { "load() called from inside onToken; stop by returning false" }
        libraryError?.let { return loadFailed(it) }
        val file = File(path)
        if (!file.isFile) return loadFailed("Model file not found: $path")
        if (!file.canRead()) return loadFailed("Model file is not readable: $path")
        if (file.length() < 1L shl 20) return loadFailed("Model file is too small to be a model (${file.length()} bytes): $path")
        val ctx = (if (contextTokens > 0) contextTokens else 1024).coerceIn(128, 32768)
        if (memoryCheck) {
            // the weights are mapped from the file: clean pages the system can drop and read again, so half of them is
            // what has to fit beside everything else; the cache and the buffers are the model's own memory
            val needMb = (file.length() / 2 + ctx * KV_BYTES_PER_TOKEN + OVERHEAD_BYTES) shr 20
            val freeMb = availableMemoryMb()
            if (freeMb in 0 until needMb) {
                return loadFailed("Not enough free memory for the dialogue AI: about $needMb MB needed, $freeMb MB free")
            }
        }
        val t = if (threads > 0) threads else defaultThreads()
        val req = Request()
        val entrySeq = synchronized(state) { cancelSeq }
        genLock.withLock {
            if (handle == 0L) handle = runCatching { nativeCreate() }.getOrDefault(0L)
            if (handle == 0L) return loadFailed("Could not create the native engine (out of memory?)")
            if (!begin(req, entrySeq)) {  // cancelled while waiting: whatever was loaded is still there
                Log.i(TAG, "load cancelled before it started")
                error = "cancelled"
                return false
            }
            runCatching { nativeSetDevice(handle, device or (if (fastCpu) 16 else 0) or (if (kvQ8) 32 else 0) or (mtp.coerceIn(0, 15) shl 8) or (batchCode(batch) shl 12) or (if (headMapped) 1 shl 15 else 0) or (ngram.coerceIn(0, 15) shl 16) or (if (fixedSteps) 1 shl 20 else 0) or
                ((batchSeqs.coerceIn(1, 8) - 1) shl 24)) }
            poolSent = null
            if (!backendReady) powerEnv()  // (the native load loads the backends first if nothing did yet)
            runCatching { nativeSetSnapshots(handle, (stateDir?.path ?: "").toByteArray(Charsets.UTF_8), stateBudgetBytes, hotState, statePoints) }
            loaded = false  // the native load frees the old model first
            val ok = try {
                nativeLoad(handle, path.toByteArray(Charsets.UTF_8), t, ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "load threw", e)
                false
            } finally {
                end()
            }
            stats = readStats()
            loadMem = runCatching { nativeLoadMem() }.getOrNull()?.takeIf { it.size == 5 } ?: DoubleArray(5)
            if (!ok) return loadFailed(runCatching { nativeError(handle) }.getOrNull()?.ifEmpty { null } ?: "Could not load the model")
            error = null
            loaded = true
            Log.i(TAG, "loaded ${file.name} in ${stats.loadMs} ms, ${stats.threads} threads, context $ctx; $backendInfo")
            return true
        }
    }

    override fun generate(prompt: String, maxTokens: Int, temperature: Float, onToken: (String) -> Boolean): String =
        generate(prompt, maxTokens, temperature, onToken, Request())

    /**
     * Stops the running [generate] (or [load]) as soon as it can, and any calls already waiting for their turn. Calls
     * made after it run normally. Safe from any thread, never blocks.
     */
    override fun cancel() {
        synchronized(state) {
            cancelSeq++
            if (running != null) nativeCancel(handle)
        }
    }

    /** Frees the model (waits for a running call to stop first). The object can be loaded again afterwards. */
    override fun close() {
        unload(null)
        appContext?.unregisterComponentCallbacks(memoryCallbacks)
    }

    // ------------------------------------------------------------------------------------------------ extras

    /** [load] on a background thread. */
    suspend fun loadAsync(path: String, threads: Int = 0, contextTokens: Int = 1024): Boolean =
        withContext(Dispatchers.IO) { load(path, threads, contextTokens) }

    /**
     * [generate] on a background thread; cancelling the coroutine (e.g. withTimeout, or the player walking away) stops
     * this generation (or takes it out of the queue) without touching others. [onToken] is called on that thread.
     */
    suspend fun generateAsync(prompt: String, maxTokens: Int = 56, temperature: Float = .3f, onToken: (String) -> Boolean = { true }): String {
        val req = Request()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel(req) }
            thread(name = "llm-generate", isDaemon = true) {
                val r = runCatching { generate(prompt, maxTokens, temperature, onToken, req) }
                if (cont.isActive) cont.resumeWith(r)
            }
        }
    }

    /** Frees the model when Android needs the memory: while the game runs and memory is critical, or once it is in the background. */
    fun onTrimMemory(level: Int) {
        @Suppress("DEPRECATION")
        val free = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!free || (!loaded && handle == 0L)) return
        Log.w(TAG, "onTrimMemory($level): freeing the model")
        cancel()
        // Never block the main thread (a load may be running): free it on a thread of its own.
        thread(name = "llm-unload", isDaemon = true) { unload("Freed to save memory; load it again when needed") }
    }

    /**
     * Pre-bakes [prompt]'s saved states (with [stateDir]): processes its system message up to the end of its fact sheet
     * and writes the states not on flash yet (the shared rules, this person, this fact sheet), so that a conversation
     * with them later restores instead of reading it all again. Returns the tokens covered (0: nothing to bake, -1:
     * failed or cancelled). Blocks like [generate]; [cancel] stops it.
     */
    fun bake(prompt: String): Int {
        if (isMainThread() || !loaded) return -1
        val req = Request()
        val entrySeq = synchronized(state) { cancelSeq }
        genLock.withLock {
            if (!loaded || handle == 0L) return -1
            if (!begin(req, entrySeq)) return -1
            return try { nativeBake(handle, prompt.toByteArray(Charsets.UTF_8)) } catch (e: Throwable) { -1 } finally { end() }
        }
    }

    /** The saved states now: files, bytes on flash, in RAM, RAM bytes, a state's size, restores from flash, from RAM,
     *  restore ms, writes, write ms, removed for the budget (all 0 before a load). */
    val stateStats: LongArray get() = (if (handle == 0L) null else runCatching { nativeSnapStats(handle) }.getOrNull()) ?: LongArray(11)

    /** [stateStats] in words, for the breadcrumbs. */
    fun stateLine(): String = stateStats.let { s ->
        "states: ${s[0]} on flash (${s[1] shr 20} MB), ${s[2]} in RAM (${s[3] shr 20} MB), each ${s[4] shr 20} MB; " +
            "restored ${s[5]} from flash + ${s[6]} from RAM in ${s[7]} ms; written ${s[8]} in ${s[9]} ms; ${s[10]} removed for the budget"
    }

    /**
     * Several prompts in one pass ([batchSeqs] from the load): each reply token-identical to what [generate] would write
     * for it alone with the same seed (proved greedy and sampled on a computer: host_test --batch-test). [seeds]: one per
     * prompt (a new random one each when empty). Blocks; not on the main thread. Returns one reply and its stats per
     * prompt ("" for one that failed); null if it could not run at all.
     */
    fun generateBatch(prompts: List<String>, maxTokens: Int, temperature: Float, seeds: IntArray = IntArray(0)): List<Pair<String, LlmStats>>? {
        lastGenerateError = null
        if (isMainThread() || !loaded || prompts.isEmpty()) return null
        genLock.withLock {
            if (!loaded || handle == 0L) return null
            val req = Request()
            if (!begin(req, synchronized(state) { cancelSeq })) return null
            val sd = if (seeds.size == prompts.size) seeds else IntArray(prompts.size) { kotlin.random.Random.nextInt() }
            val out = try {
                nativeGenerateBatch(handle, Array(prompts.size) { prompts[it].toByteArray(Charsets.UTF_8) }, maxTokens.coerceIn(0, 4096), temperature, sd)
            } catch (e: Throwable) {
                Log.e(TAG, "generateBatch threw", e)
                null
            } finally {
                end()
            }
            val err = runCatching { nativeError(handle) }.getOrNull()?.ifEmpty { null }
            if (err != null) lastGenerateError = err
            out ?: return null
            val st = runCatching { nativeBatchStats(handle) }.getOrNull()
            return out.mapIndexed { i, s ->
                val o = i * 17
                val ls = if (st != null && st.size >= o + 17) LlmStats(loadMs = st[o], promptTokens = st[o + 1].toInt(), reusedTokens = st[o + 2].toInt(),
                    prefillMs = st[o + 3], genTokens = st[o + 4].toInt(), genMs = st[o + 5], firstTokenMs = st[o + 6], threads = st[o + 7].toInt())
                else LlmStats()
                (s ?: "") to ls
            }
        }
    }

    /** [stats] as one reply of a [generateBatch] went, so code that reads [stats] after a reply reads that one's. */
    fun replayStats(s: LlmStats) { stats = s }

    // ------------------------------------------------------------------------------------------------ internals

    private fun generate(prompt: String, maxTokens: Int, temperature: Float, onToken: (String) -> Boolean, req: Request): String {
        lastGenerateError = null
        if (isMainThread()) return generateFailed("generate() was called on the main thread; call it from a background thread")
        if (!loaded) return generateFailed("no model loaded")
        val entrySeq = synchronized(state) { cancelSeq }
        genLock.withLock {
            if (!loaded || handle == 0L) return generateFailed("no model loaded")
            if (!begin(req, entrySeq)) return generateFailed("cancelled")
            val sink = TokenSink(onToken)
            draftPool.let { p -> if (p != poolSent) { runCatching { nativeSetPool(handle, p.toByteArray(Charsets.UTF_8)) }; poolSent = p } }
            runCatching { nativeSetGuide(handle, guided, guideSelf.toByteArray(Charsets.UTF_8), guideRecent.joinToString("\n") { it.replace('\n', ' ') }.toByteArray(Charsets.UTF_8)) }
            val text = try {
                nativeGenerate(handle, prompt.toByteArray(Charsets.UTF_8), maxTokens.coerceIn(0, 4096), temperature, sink)
            } catch (e: Throwable) {
                Log.e(TAG, "generate threw", e)
                null
            } finally {
                end()
            }
            stats = readStats()
            val err = runCatching { nativeError(handle) }.getOrNull()?.ifEmpty { null }
            if (err != null) lastGenerateError = err
            sink.thrown?.let { throw it }  // the caller's own callback failed: hand its exception back
            return text ?: generateFailed(err ?: "generation failed")
        }
    }

    /** Under [genLock]: marks [req] as the one running and arms the engine, unless it was cancelled while it waited. */
    private fun begin(req: Request, entrySeq: Long): Boolean = synchronized(state) {
        if (req.cancelled || cancelSeq != entrySeq) return false
        running = req
        nativeArm(handle)
        true
    }

    private fun end() = synchronized(state) { running = null }

    /** Cancels one request: the running one is stopped, a waiting one will not start. */
    private fun cancel(req: Request) = synchronized(state) {
        req.cancelled = true
        if (running === req) nativeCancel(handle)
    }

    private fun unload(reason: String?) {
        // from inside onToken the lock is this thread's own: destroying the engine under the running generation
        check(!genLock.isHeldByCurrentThread) { "close() or load() called from inside onToken; stop by returning false" }
        cancel()
        genLock.withLock {
            if (handle != 0L) runCatching { nativeDestroy(handle) }
            handle = 0L
            loaded = false
            if (reason != null) error = reason
        }
    }

    private fun readStats(): LlmStats {
        val s = runCatching { nativeStats(handle) }.getOrNull() ?: return LlmStats()
        return LlmStats(loadMs = s[0], promptTokens = s[1].toInt(), reusedTokens = s[2].toInt(), prefillMs = s[3],
            genTokens = s[4].toInt(), genMs = s[5], firstTokenMs = s[6], threads = s[7].toInt(),
            drafted = s.getOrElse(8) { 0L }.toInt(), accepted = s.getOrElse(9) { 0L }.toInt(),
            ngramDrafted = s.getOrElse(10) { 0L }.toInt(), ngramAccepted = s.getOrElse(11) { 0L }.toInt(),
            steps = s.getOrElse(12) { 0L }.toInt(), padded = s.getOrElse(13) { 0L }.toInt(), padAccepted = s.getOrElse(14) { 0L }.toInt(),
            guideBanned = s.getOrElse(15) { 0L }.toInt(), guideFallbacks = s.getOrElse(16) { 0L }.toInt())
    }

    private fun loadFailed(why: String): Boolean {
        Log.w(TAG, "load failed: $why")
        // [loaded] is left as it is: a check that fails before the native load leaves the model loaded before in place
        // (still usable, and closed by close()); the native load itself clears it first, as it frees the old model
        error = why
        return false
    }

    private fun generateFailed(why: String): String {
        if (why != "cancelled") Log.w(TAG, "generate: $why")
        lastGenerateError = why
        return ""
    }

    private fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

    /** Called from native code with each piece of the reply (always whole characters). */
    @Keep
    private class TokenSink(private val onToken: (String) -> Boolean) {
        var thrown: Throwable? = null

        @Keep
        fun onText(text: String): Boolean = try {
            onToken(text)
        } catch (t: Throwable) {
            thrown = t
            false
        }
    }

    private external fun nativeBackendInfo(): String?
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoad(handle: Long, path: ByteArray, threads: Int, contextTokens: Int): Boolean
    private external fun nativeError(handle: Long): String?
    private external fun nativeSetDevice(handle: Long, kind: Int)
    private external fun nativeDevice(handle: Long): Int
    private external fun nativeAccelerators(): Int
    private external fun nativeArm(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, sink: TokenSink?): String?
    private external fun nativeStats(handle: Long): LongArray?
    private external fun nativeLoadInfo(): String?
    private external fun nativeLoadMem(): DoubleArray?
    private external fun nativeKvQ8(handle: Long): Boolean
    private external fun nativeSetSnapshots(handle: Long, dir: ByteArray, budgetBytes: Long, hot: Boolean, points: Int)
    private external fun nativeBake(handle: Long, prompt: ByteArray): Int
    private external fun nativeSetPool(handle: Long, lines: ByteArray)
    private external fun nativeSetGuide(handle: Long, on: Boolean, self: ByteArray, recent: ByteArray)
    private external fun nativeGenerateBatch(handle: Long, prompts: Array<ByteArray>, maxTokens: Int, temperature: Float, seeds: IntArray): Array<String?>?
    private external fun nativeBatchStats(handle: Long): LongArray?
    private external fun nativeBatchSeqs(handle: Long): Int
    private external fun nativeSnapStats(handle: Long): LongArray?

    /** How the model's work splits between devices (llama.cpp's "graph splits" line; more: more fell back to the CPU). */
    val loadInfo: String? get() = if (libraryError != null) null else runCatching { nativeLoadInfo() }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val CPU = 0
        const val GPU = 1
        const val NPU = 2
        /** [batch] as the native engine takes it (0: 512, 1: 256, 2: 128). */
        fun batchCode(n: Int) = when { n <= 128 -> 2; n <= 256 -> 1; else -> 0 }
        private const val TAG = "NativeLlm"
        /** Qwen3-0.6B's KV cache in f16: 28 layers x 8 heads x 128 x 2 (K and V) x 2 bytes, rounded up. */
        private const val KV_BYTES_PER_TOKEN = 120L * 1024
        /** Compute buffers, the sampler, the app's own headroom. */
        private const val OVERHEAD_BYTES = 96L shl 20

        /**
         * Null once libtimerpg_llm.so (and the llama.cpp libraries it needs) loaded; otherwise why not. Lazy, so the
         * libraries load on the first [load] (a background thread), not when the class is first touched.
         */
        /** llama.cpp's backends are loaded in this process ([initBackend]; native init_backend runs once per process). */
        @Volatile private var backendReady = false

        private val libraryError: String? by lazy {
            try {
                System.loadLibrary("timerpg_llm")
                null
            } catch (t: Throwable) {
                Log.w(TAG, "native library not loaded", t)
                "The dialogue AI is not available on this device (${t.javaClass.simpleName}: ${t.message}). It needs a 64-bit ARM phone."
            }
        }

        /**
         * Threads for the model: the phone's big cores (from /sys/devices/system/cpu/cpu N/cpu_capacity, else
         * cpufreq/cpuinfo_max_freq), at most 6, and always leaving two cores for the game's own main and render threads.
         * See [threadsFor].
         */
        fun defaultThreads(): Int {
            val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            fun read(name: String) = (0 until 32).mapNotNull { cpu ->
                runCatching { File("/sys/devices/system/cpu/cpu$cpu/$name").readText().trim().toLong() }.getOrNull()?.takeIf { it > 0 }
            }
            return threadsFor(n, read("cpu_capacity"), read("cpufreq/cpuinfo_max_freq"))
        }

        /**
         * [defaultThreads] for [cores] cores, given each core's [capacities] (the kernel's cpu_capacity: its design and
         * clock together, the fastest core 1024) and max [clocks]; either may be empty (unknown).
         * Big cores: with capacities, those with at least half the fastest one's. The S25 Ultra's Snapdragon 8 Elite has
         * no little cores (6 x 3.53 GHz cores of the same design as its 2 x 4.47 GHz ones, so about 800 of 1024): all 8
         * count and it gets 6 threads, where "faster than the slowest cluster" would give it 2; little A5x cores are at
         * a third of the fastest or less. With clocks only: those faster than the slowest cluster (a clock alone cannot
         * tell a little core from a slower big one, e.g. 1.9 GHz A55s next to 2.4 GHz A78s).
         * Running on little cores as well only slows every step down to their pace, and llama.cpp's threads spin-wait for
         * each other at every step, so one of them losing its core to another thread stalls them all (measured on a
         * 4-core machine with one core busy: 4 threads 0.3 tok/s, 2 threads 12-14 tok/s): hence two cores for the game.
         */
        fun threadsFor(cores: Int, capacities: List<Long>, clocks: List<Long> = emptyList()): Int {
            val cap = minOf(6, cores - 2).coerceAtLeast(1)
            val big = when {
                capacities.size >= 2 -> capacities.max().let { top -> capacities.count { it * 2 >= top } }
                clocks.size >= 2 -> clocks.min().let { low -> clocks.count { it > low } }.takeIf { it > 0 } ?: clocks.size
                else -> return cap
            }
            return big.coerceIn(1, cap)
        }

        /**
         * Hands the native allocator's free pages back to the system (scudo's mallopt(M_PURGE_ALL), else M_PURGE; looked up
         * at run time). Returns mallopt's result (1 done, 0 not supported), or -1 when the library is not here.
         */
        fun purgeNativeHeap(): Int = if (libraryError != null) -1 else runCatching { nativePurge() }.getOrDefault(-1)

        @JvmStatic private external fun nativePurge(): Int

        /**
         * The NPU's power vote now, for the Debug tab and the probes: "auto idle, wake 1.20 ms +0.31 ms" (per burst; the
         * last burst vote's cost and the gap to its first compute), "auto burst", "fixed burst", "not loaded" (the
         * backends are not loaded in this process yet), "auto (unpatched: burst)". The vote's events since the last call
         * (each burst's hold, wake cost and release) go to the breadcrumbs.
         */
        fun npuPower(): String {
            if (libraryError != null || !backendReady) return "not loaded"
            runCatching { nativeNpuPowerEvents() }.getOrNull()?.lineSequence()?.filter { it.isNotBlank() }?.forEach {
                runCatching { com.timerpg.street.shot.Crumbs.add("npu: $it") }
            }
            return runCatching { nativeNpuPower() }.getOrNull() ?: "unknown"
        }

        @JvmStatic private external fun nativeSetNpuPower(mode: ByteArray, holdMs: Int)
        @JvmStatic private external fun nativeSetGpuBackend(atStart: Boolean)
        @JvmStatic private external fun nativeSetTileCache(dir: ByteArray, write: Boolean, budget: Long)
        @JvmStatic private external fun nativeTileStatus(): String?
        @JvmStatic private external fun nativeHeadVocab(path: ByteArray): Long

        /**
         * The NPU's pre-tiled weights on flash from the next NPU load (docs/ENGINE_TAILORING.md, idea 5): [dir] null turns
         * it off; [write]: tiles made at the first compute after a load are saved (else only read); [budgetBytes] for the
         * whole directory. Keyed by the model file and the backend's build; any failure only skips the cache.
         */
        fun tileCache(dir: File?, write: Boolean, budgetBytes: Long) {
            if (libraryError != null) return
            runCatching { nativeSetTileCache((dir?.path ?: "").toByteArray(Charsets.UTF_8), write, budgetBytes) }
        }

        /** What the tile cache did since the last NPU load, for the breadcrumbs ("off", "read 180 (2010 MB in 640 ms)..."). */
        fun tileStatus(): String = if (libraryError != null) "no library" else runCatching { nativeTileStatus() }.getOrNull() ?: "unknown"

        /** The English-first head's size in a model file (its "timerpg.head_vocab"), 0 for a whole-vocabulary file. */
        fun headVocab(file: File): Long = if (libraryError != null) 0L else runCatching { nativeHeadVocab(file.path.toByteArray(Charsets.UTF_8)) }.getOrDefault(0L)
        @JvmStatic private external fun nativeNpuPower(): String?
        @JvmStatic private external fun nativeNpuPowerEvents(): String?

        /** MemAvailable from /proc/meminfo in MB, or -1 if unknown. */
        private fun availableMemoryMb(): Long = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024)
            }
        }.getOrNull() ?: -1L
    }
}
