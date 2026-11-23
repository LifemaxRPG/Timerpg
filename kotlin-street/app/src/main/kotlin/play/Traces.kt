package com.timerpg.street.play

import android.content.Context
import android.os.Build
import android.os.Bundle
import java.io.File
import java.util.concurrent.Executor

/**
 * System traces from Android's ProfilingManager (Android 15, API 35+): a short Perfetto trace of every thread, the CPU
 * cores and their clocks, and the GPU's work, asked for by the "mark this" button and by severe frame spikes
 * (rate-limited: at most one a [SPIKE_EVERY_MS] for spikes, one a [MARK_EVERY_MS] for marks; Android adds its own
 * limits). The project builds against SDK 34, so the API is reached by reflection, and only on API 35+.
 *
 * The trace starts when asked (Android 15 cannot keep a trace of the past for an app's own reasons), so a spike's trace
 * shows what follows it: useful for trouble that lasts or comes back, not for a single hitch.
 *
 * Each finished trace is gzipped into the reports folder (`trace~<date>~<time>-<why>.perfetto-trace.gz`) and sent only
 * on Wi-Fi while charging ([PlayUploads]); at most [KEEP] wait, the oldest dropped.
 */
class Traces(private val ctx: Context, private val dir: File, private val onEvent: (String, String, Long, String?) -> Unit) {
    @Volatile private var inFlight = false
    private var lastSpike = 0L
    private var lastMark = 0L

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 35 && manager() != null

    private fun manager(): Any? = runCatching {
        val cls = Class.forName("android.os.ProfilingManager")
        ctx.getSystemService(cls)
    }.getOrNull()

    /** Asks for a trace of [durationMs] for [why] ("mark", "spike"); false if not now (unsupported, rate-limited, busy). */
    fun request(why: String, durationMs: Int = DURATION_MS, now: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        if (Build.VERSION.SDK_INT < 35 || inFlight) return false
        if (why == "spike") { if (lastSpike != 0L && now - lastSpike < SPIKE_EVERY_MS) return false; lastSpike = now }
        else { if (lastMark != 0L && now - lastMark < MARK_EVERY_MS) return false; lastMark = now }
        val pm = manager() ?: return false
        return try {
            val cls = pm.javaClass
            val type = runCatching { Class.forName("android.os.ProfilingManager").getField("PROFILING_TYPE_SYSTEM_TRACE").getInt(null) }.getOrDefault(4)
            val params = Bundle().apply {
                putInt("KEY_DURATION_MS", durationMs)
                putInt("KEY_SIZE_KB", BUFFER_KB)
                // (a ring buffer: the trace keeps its last seconds if it fills)
                putInt("KEY_BUFFER_FILL_POLICY", 2)
            }
            val consumerCls = Class.forName("java.util.function.Consumer")
            val listener = java.lang.reflect.Proxy.newProxyInstance(consumerCls.classLoader, arrayOf(consumerCls)) { proxy, m, args ->
                when (m.name) {
                    "accept" -> { runCatching { done(why, args?.getOrNull(0)) }; null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "PlayLog trace listener"
                    else -> null
                }
            }
            val exec = Executor { r -> Thread(r, "playlog-trace").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start() }
            val req = cls.methods.first { it.name == "requestProfiling" && it.parameterTypes.size == 6 }
            inFlight = true
            req.invoke(pm, type, params, "timerpg-$why", null, exec, listener)
            onEvent("trace", why, 0L, "requested ${durationMs / 1000} s")
            true
        } catch (t: Throwable) {
            inFlight = false
            onEvent("trace", why, 0L, "not started: ${t.javaClass.simpleName}: ${t.message ?: (t.cause?.message ?: "")}".take(200))
            false
        }
    }

    private fun done(why: String, result: Any?) {
        inFlight = false
        if (result == null) return
        val c = result.javaClass
        val err = runCatching { c.getMethod("getErrorCode").invoke(result) as Int }.getOrDefault(-1)
        val path = runCatching { c.getMethod("getResultFilePath").invoke(result) as String? }.getOrNull()
        if (err != 0 || path == null) {
            val msg = runCatching { c.getMethod("getErrorMessage").invoke(result) as String? }.getOrNull()
            onEvent("trace", why, 0L, "failed: error $err${msg?.let { " ($it)" } ?: ""}".take(200))
            return
        }
        val src = File(path)
        val name = "trace~" + java.text.SimpleDateFormat("yyyy-MM-dd'~'HHmmss", java.util.Locale.US).format(java.util.Date()) + "-$why.perfetto-trace.gz"
        val out = File(dir, name)
        runCatching {
            src.inputStream().use { i -> java.util.zip.GZIPOutputStream(out.outputStream(), 64 * 1024).use { o -> i.copyTo(o, 64 * 1024) } }
            val raw = src.length()
            src.delete()
            if (out.length() > MAX_BYTES) { out.delete(); onEvent("trace", why, raw, "dropped: ${out.length() shr 20} MB gzipped is over the cap") }
            else onEvent("trace", why, out.length(), out.name.replace('~', '/'))
        }.onFailure { onEvent("trace", why, 0L, "not kept: ${it.javaClass.simpleName}") }
        prune(dir)
    }

    companion object {
        const val DURATION_MS = 10_000
        const val BUFFER_KB = 32 * 1024
        const val SPIKE_EVERY_MS = 5 * 60_000L
        const val MARK_EVERY_MS = 30_000L
        /** Traces waiting to be sent, at most; the GitHub contents API takes up to 100 MB a file, its base64 body is built in memory: kept to 25 MB gzipped. */
        const val KEEP = 6
        const val MAX_BYTES = 25L shl 20

        fun pending(dir: File): List<File> = (dir.listFiles { f -> f.name.startsWith("trace~") && f.name.endsWith(".gz") } ?: emptyArray()).sortedByDescending { it.name }
        fun prune(dir: File) { pending(dir).drop(KEEP).forEach { it.delete() } }
    }
}
