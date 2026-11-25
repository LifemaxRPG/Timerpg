package com.timerpg.street.dialogue

import java.io.File

/**
 * The light model's files and their one-time preparation on the phone (docs/MODEL_FILES.md; the owner's decision
 * 2026-11-25: the customized files are made on the phone from the normal Hugging Face downloads, nothing is hosted).
 *
 * The light model comes as one of two downloads, best first ([SOURCES]): the 4B with its MTP layer ([ModelFiles.LIVE_MTP],
 * downloaded now) and the 4B without ([ModelFiles.LIVE], downloaded by earlier builds: used until the MTP one is complete,
 * then deleted, so an existing download is not wasted). Either is **prepared** once: the English-first vocabulary order
 * (tools/llm/vocab_reorder.cpp in the app's library, `NativeLlm.reorderVocab`) writes [preparedName], verifies it, and
 * then the download is deleted: the prepared file *is* the model from then on (same weights, rows moved, the key
 * `timerpg.head_vocab` = K that the engine's head patch reads). K (49,152 by default, 65,536 the quality setting) changes
 * in place in the same file (`NativeLlm.retargetVocab`), with no new download.
 *
 * Pure decisions over a directory, so the whole state machine is unit tested with fake files (ModelPrepTest); the job
 * that runs it on the phone (in the background, never during a conversation) is [ModelPrepJob].
 */
object ModelPrep {
    /** A light model download: its file name, exact size, and whether it carries the MTP (nextn) layer. */
    class Source(val name: String, val bytes: Long, val mtp: Boolean)

    /** Best first. */
    val SOURCES = listOf(
        Source(ModelFiles.LIVE_MTP, ModelFiles.LIVE_MTP_BYTES, mtp = true),
        Source(ModelFiles.LIVE, ModelFiles.LIVE_BYTES, mtp = false),
    )

    /** The English-first head's default size (head_vocab.txt's own default) and the quality setting. */
    const val K_DEFAULT = 49_152
    const val K_QUALITY = 65_536
    /** A prepared file is its source plus a few hundred bytes of keys (measured: +128 for the 4B MTP file, +160 for the
     *  0.8B); anything in (source, source + this] is one. It is only ever renamed into place after its verification. */
    const val PREPARED_SLACK = 1L shl 20
    /** Free space to keep beside the second copy while preparing. */
    const val SPARE_BYTES = 256L shl 20

    fun preparedName(src: String) = src.removeSuffix(".gguf") + "-en.gguf"
    fun preparedFile(dir: File, s: Source) = File(dir, preparedName(s.name))
    fun rawFile(dir: File, s: Source) = File(dir, s.name)

    /** The download is complete (its exact size). */
    fun isRaw(dir: File, s: Source) = rawFile(dir, s).let { it.isFile && it.length() == s.bytes }
    /** The prepared file is in place (renamed there only after its verification). */
    fun isPrepared(dir: File, s: Source) = preparedFile(dir, s).let { it.isFile && it.length() > s.bytes && it.length() <= s.bytes + PREPARED_SLACK }
    /** The source is on the phone, as downloaded or prepared: nothing to download. */
    fun isHere(dir: File, s: Source) = isPrepared(dir, s) || isRaw(dir, s)

    /** The light model file to load: [prepared] (the English-first head) or as downloaded; [mtp] it has the MTP layer. */
    data class Live(val file: File, val mtp: Boolean, val prepared: Boolean, val source: Source)

    /** The best light model on the phone: the best source that is here, its prepared file first. Null: none. */
    fun live(dir: File): Live? {
        for (s in SOURCES) {
            if (isPrepared(dir, s)) return Live(preparedFile(dir, s), s.mtp, prepared = true, source = s)
            if (isRaw(dir, s)) return Live(rawFile(dir, s), s.mtp, prepared = false, source = s)
        }
        return null
    }

    /** The next thing the preparation does. */
    sealed interface Step {
        /** Reorder [src] into [dst] (K from the setting), then delete [src]. */
        data class Reorder(val src: File, val dst: File, val k: Int) : Step
        /** Change K of the prepared [file] in place. */
        data class Retarget(val file: File, val k: Int) : Step
        /** Delete [files] ([why]: superseded, a finished swap, a stale part). */
        data class Delete(val files: List<File>, val why: String) : Step
        /** Not enough free space for the second copy: [needBytes] wanted, [freeBytes] free. Tried again later. */
        data class NoSpace(val needBytes: Long, val freeBytes: Long) : Step
        /** Nothing to do: the best file is prepared at the wanted K (or there is no light model yet). */
        object Idle : Step
    }

    /**
     * The next step for [dir] with K = [k]. [headK] reads a prepared file's `timerpg.head_vocab` (0: none or unreadable);
     * [freeBytes] is the free space now. In order: stale part files of prepared files, and the old download's part (it is
     * not downloaded any more); the sources after the best one here (superseded, raw, prepared or partly downloaded); a
     * finished swap's download; K; else the reorder, when the second copy fits beside what the better source's download
     * still needs.
     */
    fun next(dir: File, k: Int, headK: (File) -> Long, freeBytes: Long): Step {
        val stale = SOURCES.map { File(dir, preparedName(it.name) + ".part") }.filter { it.exists() } +
            listOf(File(dir, ModelFiles.LIVE + ".part")).filter { it.exists() }
        if (stale.isNotEmpty()) return Step.Delete(stale, "a preparation or download that will not be finished")
        val best = SOURCES.indexOfFirst { isHere(dir, it) }
        if (best < 0) return Step.Idle
        val s = SOURCES[best]
        val superseded = SOURCES.drop(best + 1).flatMap { listOf(rawFile(dir, it), preparedFile(dir, it), File(dir, it.name + ".part")) }.filter { it.exists() }
        if (superseded.isNotEmpty()) return Step.Delete(superseded, "superseded by ${s.name}")
        val prepared = preparedFile(dir, s)
        val raw = rawFile(dir, s)
        if (isPrepared(dir, s)) {
            if (raw.exists()) return Step.Delete(listOf(raw), "prepared into ${prepared.name}")
            val now = headK(prepared)
            if (now <= 0) return Step.Delete(listOf(prepared), "not a prepared file (no timerpg.head_vocab)")
            return if (now != k.toLong()) Step.Retarget(prepared, k) else Step.Idle
        }
        // (a better source still downloading needs its remaining bytes too)
        val reserve = SOURCES.take(best).sumOf { b -> File(dir, b.name + ".part").takeIf { it.isFile }?.let { (b.bytes - it.length()).coerceAtLeast(0) } ?: 0L }
        val need = s.bytes + SPARE_BYTES + reserve
        if (freeBytes < need) return Step.NoSpace(need, freeBytes)
        return Step.Reorder(raw, prepared, k)
    }

    /** What the preparation's native side does (NativeLlm on the phone, fakes in the tests). */
    interface Ops {
        /** `timerpg.head_vocab` of [f]; 0 for none. */
        fun headK(f: File): Long
        fun freeBytes(dir: File): Long
        /** "done", "already", "cancelled" or "failed" with [Result.error]. */
        fun reorder(src: File, dst: File, k: Int, progress: (Double) -> Boolean): Result
        fun retarget(f: File, k: Int): Result
    }

    /** One native step's outcome: [code] as above, its time and memory (for the breadcrumbs). */
    data class Result(val code: String, val ms: Long = 0, val rssStartKb: Long = 0, val rssPeakKb: Long = 0, val error: String = "")

    /** How a [run] ended: [ok] (nothing left to do), [changed] (the light model's file is another one now, or its K: reload it). */
    data class Outcome(val ok: Boolean, val changed: Boolean, val log: List<String>, val stopped: String? = null)

    /**
     * Runs steps until there is nothing to do, a step fails, there is no space, or [allowed] says stop (a conversation
     * began: the reorder is cancelled through its progress callback, its part file removed; the next run starts it
     * again). [progress] gets the reorder's fraction. Each step is logged for the breadcrumbs.
     */
    fun run(dir: File, k: Int, ops: Ops, allowed: () -> Boolean, progress: (Double) -> Unit = {}): Outcome {
        val log = ArrayList<String>()
        val before = live(dir)?.file
        var stopped: String? = null
        var ok = false
        var retargeted = false
        for (i in 0 until 12) {
            if (!allowed()) { stopped = "held (a conversation, or the model loading)"; break }
            when (val st = next(dir, k, ops::headK, ops.freeBytes(dir))) {
                Step.Idle -> { ok = true; break }
                is Step.NoSpace -> { stopped = "not enough free space for the second copy: ${st.needBytes shr 20} MB needed, ${st.freeBytes shr 20} MB free"; break }
                is Step.Delete -> {
                    val gone = st.files.filter { it.delete() || !it.exists() }
                    log += "deleted ${st.files.joinToString { it.name }} (${st.why})"
                    if (gone.size != st.files.size) { stopped = "could not delete ${st.files.filter { it.exists() }.joinToString { it.name }}"; break }
                }
                is Step.Retarget -> {
                    val r = ops.retarget(st.file, st.k)
                    log += "K of ${st.file.name} to ${st.k}: ${r.code}" + (if (r.error.isNotEmpty()) " (${r.error})" else "") + " in ${r.ms} ms"
                    if (r.code != "done" && r.code != "already") { stopped = "K not changed: ${r.error}"; break }
                    if (r.code == "done") retargeted = true
                }
                is Step.Reorder -> {
                    val r = ops.reorder(st.src, st.dst, st.k) { f -> progress(f); allowed() }
                    log += "${st.src.name} -> ${st.dst.name} (K ${st.k}): ${r.code} in ${"%.1f".format(r.ms / 1000.0)} s, " +
                        "peak RSS ${r.rssPeakKb shr 10} MB (from ${r.rssStartKb shr 10})" + (if (r.error.isNotEmpty()) ": ${r.error}" else "")
                    when (r.code) {
                        // (the download is deleted by the next step, once the prepared file is seen in place)
                        "done", "already" -> {}
                        "cancelled" -> { stopped = "cancelled (a conversation began): runs again later"; break }
                        else -> { stopped = "failed: ${r.error}"; break }
                    }
                }
            }
        }
        return Outcome(ok, retargeted || live(dir)?.file != before, log, stopped)
    }
}
