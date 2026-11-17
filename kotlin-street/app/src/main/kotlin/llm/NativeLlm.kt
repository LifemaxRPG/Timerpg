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
    /** Where the loaded model runs. */
    val runsOn: Int get() = if (handle == 0L) CPU else runCatching { nativeDevice(handle) }.getOrDefault(CPU)
    /** This phone and build's accelerators (after the native library loaded): bit 0 a GPU, bit 1 an NPU. */
    val accelerators: Int get() = runCatching { nativeAccelerators() }.getOrDefault(0)
    @Volatile override var error: String? = null
        private set
    @Volatile override var stats: LlmStats = LlmStats()
        private set
    /** Why the last [generate] returned early or empty ("cancelled", "prompt too long: ..."), or null. */
    @Volatile var lastGenerateError: String? = null
        private set
    /** Which CPU kernels llama.cpp runs on this phone, for the speed panel (e.g. "...armv8.6_1.so (...); cpu: neon dotprod fp16 i8mm"). */
    val backendInfo: String get() = libraryError ?: runCatching { nativeBackendInfo() }.getOrNull() ?: "unknown"
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
            runCatching { nativeSetDevice(handle, device or (if (fastCpu) 16 else 0)) }
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
            genTokens = s[4].toInt(), genMs = s[5], firstTokenMs = s[6], threads = s[7].toInt())
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

    /** How the model's work splits between devices (llama.cpp's "graph splits" line; more: more fell back to the CPU). */
    val loadInfo: String? get() = if (libraryError != null) null else runCatching { nativeLoadInfo() }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        const val CPU = 0
        const val GPU = 1
        const val NPU = 2
        private const val TAG = "NativeLlm"
        /** Qwen3-0.6B's KV cache in f16: 28 layers x 8 heads x 128 x 2 (K and V) x 2 bytes, rounded up. */
        private const val KV_BYTES_PER_TOKEN = 120L * 1024
        /** Compute buffers, the sampler, the app's own headroom. */
        private const val OVERHEAD_BYTES = 96L shl 20

        /**
         * Null once libtimerpg_llm.so (and the llama.cpp libraries it needs) loaded; otherwise why not. Lazy, so the
         * libraries load on the first [load] (a background thread), not when the class is first touched.
         */
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

        /** MemAvailable from /proc/meminfo in MB, or -1 if unknown. */
        private fun availableMemoryMb(): Long = runCatching {
            File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.div(1024)
            }
        }.getOrNull() ?: -1L
    }
}
