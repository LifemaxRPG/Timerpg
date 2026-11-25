package com.timerpg.street.dialogue

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.timerpg.street.llm.NativeLlm
import java.io.File
import java.io.RandomAccessFile

/**
 * Runs [ModelPrep] on the phone: the light model's download prepared once (the English-first vocabulary order), in the
 * background on its own thread, never during a conversation ([DialogueEngine.quietForPrep]; a reorder that a
 * conversation interrupts is cancelled and starts again later: safe, its part file is removed), one process at a time
 * (a lock file: the game and the overnight writer's process may both have it). Progress in the AI panel
 * ([AiStatus.prep]), each step and its time and peak memory in the breadcrumbs. Kicked at start, after a download
 * (the engine's reload) and when the K setting changes; a held or cancelled run is tried again every [RETRY_MS], one
 * short of space every [SPACE_RETRY_MS]; a failed one waits for the next kick.
 */
class ModelPrepJob(private val app: Context, private val engine: DialogueEngine) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var running = false
    /** A light model file changed while it could not be reloaded (a conversation): reloaded at the next quiet look. */
    @Volatile private var reloadPending = false
    private val retry = Runnable { kick("retry") }

    fun close() { closed = true; main.removeCallbacks(retry) }

    fun kick(why: String, delayMs: Long = 0) {
        if (closed) return
        main.removeCallbacks(retry)
        if (delayMs > 0) { main.postDelayed(retry, delayMs); return }
        synchronized(this) { if (running) return; running = true }
        Thread({
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Throwable) {}
            try { runOnce(why) } catch (t: Throwable) {
                com.timerpg.street.shot.Crumbs.add("model prep: stopped by ${t.javaClass.simpleName}: ${t.message}")
                engine.prepStatus(null, "Preparation stopped: ${t.message ?: t.javaClass.simpleName}")
            } finally { running = false }
        }, "model-prep").apply { isDaemon = true }.start()
    }

    private fun later(ms: Long) { if (!closed) main.postDelayed(retry, ms) }

    private fun runOnce(why: String) {
        val dir = ModelFiles.dir(app)
        if (reloadPending) reloadPending = !engine.onLiveFileChanged()
        val k = engine.headKWanted
        // nothing to do (the usual case): no lock, no breadcrumb
        val first = ModelPrep.next(dir, k, { NativeLlm.headVocab(it) }, dir.usableSpace)
        if (first == ModelPrep.Step.Idle) { if (reloadPending) later(RETRY_MS); return }
        if (!engine.quietForPrep()) { engine.prepStatus("Preparing the light model: waiting for the conversation to end"); later(RETRY_MS); return }
        // one process at a time
        val lock = RandomAccessFile(File(dir, ".prep.lock"), "rw")
        try {
            val held = runCatching { lock.channel.tryLock() }.getOrNull()
            if (held == null) { com.timerpg.street.shot.Crumbs.add("model prep: another process is preparing; later"); later(RETRY_MS); return }
            val list = listFile() ?: run {
                held.release()
                engine.prepStatus(null, "Preparation not possible: head_vocab.txt is missing from the app")
                com.timerpg.street.shot.Crumbs.add("model prep: head_vocab.txt missing from the assets"); return
            }
            val t0 = SystemClock.elapsedRealtime()
            com.timerpg.street.shot.Crumbs.add("model prep ($why): ${describe(first)}; ${dir.usableSpace shr 20} MB free")
            var lastPc = -1
            val out = ModelPrep.run(dir, k, object : ModelPrep.Ops {
                override fun headK(f: File) = NativeLlm.headVocab(f)
                override fun freeBytes(dir: File) = dir.usableSpace
                override fun reorder(src: File, dst: File, k: Int, progress: (Double) -> Boolean) = NativeLlm.reorderVocab(src, dst, list, k, progress)
                override fun retarget(f: File, k: Int) = NativeLlm.retargetVocab(f, list, k)
            }, allowed = { !closed && engine.quietForPrep() }) { f ->
                val pc = (f * 100).toInt()
                if (pc != lastPc) { lastPc = pc; engine.prepStatus("Preparing the light model (English-first head, K ${"%,d".format(k)}): $pc%") }
            }
            held.release()
            val s = (SystemClock.elapsedRealtime() - t0) / 1000.0
            out.log.forEach { com.timerpg.street.shot.Crumbs.add("model prep: $it") }
            val live = ModelPrep.live(dir)
            val note = when {
                out.ok -> "Light model prepared: ${live?.file?.name} (English-first head, K ${"%,d".format(NativeLlm.headVocab(live?.file ?: dir))}" +
                    (if (live?.mtp == true) ", MTP layer" else "") + ") in ${"%.1f".format(s)} s"
                else -> "Preparation ${out.stopped}"
            }
            com.timerpg.street.shot.Crumbs.add("model prep: ${if (out.ok) "done" else out.stopped} after ${"%.1f".format(s)} s; ${dir.usableSpace shr 20} MB free")
            engine.prepStatus(null, note)
            if (out.changed) reloadPending = !engine.onLiveFileChanged()
            when {
                out.ok -> if (reloadPending) later(RETRY_MS)
                out.stopped?.startsWith("not enough free space") == true -> later(SPACE_RETRY_MS)
                out.stopped?.startsWith("failed") == true || out.stopped?.startsWith("K not changed") == true || out.stopped?.startsWith("could not") == true -> {}
                else -> later(RETRY_MS)
            }
        } finally { runCatching { lock.close() } }
    }

    private fun describe(s: ModelPrep.Step) = when (s) {
        is ModelPrep.Step.Reorder -> "reorder ${s.src.name} (${s.src.length() shr 20} MB) into ${s.dst.name}, K ${s.k}"
        is ModelPrep.Step.Retarget -> "K of ${s.file.name} to ${s.k}"
        is ModelPrep.Step.Delete -> "delete ${s.files.joinToString { it.name }} (${s.why})"
        is ModelPrep.Step.NoSpace -> "no space (${s.needBytes shr 20} MB needed, ${s.freeBytes shr 20} MB free)"
        ModelPrep.Step.Idle -> "nothing"
    }

    /** head_vocab.txt from the APK's assets as a file the native side can read (copied each process: 390 KB). */
    private fun listFile(): File? = runCatching {
        val f = File(app.noBackupFilesDir, "head_vocab.txt")
        app.assets.open(ASSET).use { inp -> f.outputStream().use { inp.copyTo(it) } }
        f
    }.getOrNull()

    companion object {
        const val ASSET = "llm/head_vocab.txt"
        const val START_DELAY_MS = 45_000L
        const val RETRY_MS = 30_000L
        const val SPACE_RETRY_MS = 10 * 60_000L
    }
}
