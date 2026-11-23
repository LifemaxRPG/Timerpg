package com.lifemax.rpg.feature.habits.simjs

import java.io.Closeable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A JavaScript error from the simulation: its message and stack (sim_js.cpp). */
class SimJsException(message: String) : RuntimeException(message) {
    /** (from sim_js.cpp: the message as UTF-8) */
    constructor(utf8: ByteArray) : this(String(utf8, Charsets.UTF_8))
}

/**
 * The game's TypeScript simulation (src/crime, src/plan, src/police, src/loop), bundled as assets/sim/sim.js by
 * tools/bundle-sim.mjs and run in QuickJS-ng through JNI (app/src/main/cpp/simjs): the same rules on the phone and
 * the desktop, no Kotlin port (docs/LOOP.md, "The JavaScript bridge").
 *
 * [load] evaluates the bundle (it sets its API as globals); [call] calls one of them with a JSON string and returns
 * its JSON string. Its state lives inside the JS context. Not thread-safe: one thread at a time; the game uses
 * [SimJsThread], which keeps it on a thread of its own, off the render thread.
 */
class SimJs(memoryLimitBytes: Long = 512L shl 20, stackLimitBytes: Long = 1L shl 20) : Closeable {
    init { check(SimJsNative.load()) { "libtimerpg_simjs could not be loaded" } }
    private var h: Long = SimJsNative.create(memoryLimitBytes, stackLimitBytes)
    private fun h(): Long { check(h != 0L) { "SimJs closed" }; return h }

    /** Evaluates [bundle] as a global script; a JS error throws [SimJsException]. */
    fun load(bundle: String, name: String = "sim.js") = SimJsNative.eval(h(), bundle.toByteArray(Charsets.UTF_8), name)

    /** `globalThis[fn](json)`; a JS error throws [SimJsException] with its stack. */
    fun call(fn: String, json: String): String =
        String(SimJsNative.call(h(), fn, json.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /** Bytes the JS heap holds now. */
    val memoryUsed: Long get() = SimJsNative.memoryUsed(h())

    fun gc() = SimJsNative.gc(h())

    override fun close() { if (h != 0L) { SimJsNative.destroy(h); h = 0L } }
}

/**
 * A [SimJs] on a single thread of its own: everything runs there in order, so the render thread never waits on the
 * simulation (it runs in bursts: after an action, on the hour, while idle). The calls return [Future]s.
 */
class SimJsThread(memoryLimitBytes: Long = 512L shl 20, stackLimitBytes: Long = 1L shl 20) : Closeable {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        // (QuickJS recurses on the C stack: a thread with room for the stack limit and the JVM's own frames)
        Thread(null, r, "sim-js", stackLimitBytes + (4L shl 20)).apply { isDaemon = true }
    }
    private val sim: SimJs = exec.submit(Callable { SimJs(memoryLimitBytes, stackLimitBytes) }).get()

    fun load(bundle: String, name: String = "sim.js"): Future<Unit> = exec.submit(Callable { sim.load(bundle, name) })
    fun call(fn: String, json: String): Future<String> = exec.submit(Callable { sim.call(fn, json) })
    /** Any work on the simulation's thread (several calls in one burst). */
    fun <T> run(block: (SimJs) -> T): Future<T> = exec.submit(Callable { block(sim) })

    override fun close() {
        runCatching { exec.submit { sim.close() }.get() }
        exec.shutdown()
    }
}

/** The JNI entry points (sim_js.cpp). */
object SimJsNative {
    @Volatile private var loaded: Boolean? = null

    /**
     * Loads libtimerpg_simjs: from the file `-Dtimerpg.simjs.lib` names (the desktop's build), else by name (the
     * phone's APK, or java.library.path). False if it cannot be loaded.
     */
    fun load(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = runCatching {
                val file = System.getProperty("timerpg.simjs.lib")
                if (file != null && java.io.File(file).isFile) System.load(java.io.File(file).absolutePath) else System.loadLibrary("timerpg_simjs")
            }.isSuccess
            loaded = ok
            return ok
        }
    }

    @JvmStatic external fun create(memoryLimit: Long, stackLimit: Long): Long
    @JvmStatic external fun destroy(h: Long)
    @JvmStatic external fun eval(h: Long, code: ByteArray, name: String)
    @JvmStatic external fun call(h: Long, fn: String, json: ByteArray): ByteArray
    @JvmStatic external fun memoryUsed(h: Long): Long
    @JvmStatic external fun gc(h: Long)
}
