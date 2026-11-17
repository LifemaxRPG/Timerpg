package com.timerpg.street.dialogue

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * The model file. The APK does not carry it (about 400 MB): the player picks it once with the system file picker and it
 * is copied to filesDir/models/qwen3.5-9b.gguf. Everything here is blocking IO: call it off the main thread.
 */
object ModelFiles {
    const val NAME = "qwen3.5-9b.gguf"
    /** The same model in Q4_0 for the NPU (see NpuTune). */
    const val NPU_Q4_0 = "qwen3.5-9b-q4_0.gguf"
    /** What [problem] says when there is no model file. */
    const val MISSING = "missing"
    private const val MIN_BYTES = 16L shl 20
    /** Free space to leave on the device besides the model. */
    private const val SPARE_BYTES = 64L shl 20

    fun dir(ctx: Context): File = File(ctx.filesDir, "models")
    fun file(ctx: Context): File = File(dir(ctx), NAME)
    private fun part(ctx: Context): File = File(dir(ctx), "$NAME.part")

    /** A copy left half-done when the app was killed. */
    fun cleanPartial(ctx: Context) { part(ctx).delete() }

    /** Null if [f] looks like a usable GGUF model; [MISSING] if there is none; otherwise why it cannot be used. */
    fun problem(f: File): String? {
        if (!f.isFile) return MISSING
        if (f.length() < MIN_BYTES) return "The model file is too small (${f.length() shr 10} KB) to be a model. Choose the .gguf file again."
        return try {
            val head = ByteArray(24)
            RandomAccessFile(f, "r").use { it.readFully(head) }
            headerProblem(head, head.size)
        } catch (e: Exception) {
            "The model file cannot be read (${e.message})."
        }
    }

    /** Checks a GGUF header: the magic "GGUF", a version llama.cpp reads (2 or 3), and sane tensor and metadata counts. */
    fun headerProblem(h: ByteArray, n: Int): String? {
        if (n < 4 || h[0] != 'G'.code.toByte() || h[1] != 'G'.code.toByte() || h[2] != 'U'.code.toByte() || h[3] != 'F'.code.toByte())
            return "That file is not a GGUF model (choose Qwen3.5-9B-Q4_K_M.gguf)."
        if (n < 24) return "The model file is cut short."
        val version = le(h, 4, 4)
        if (version !in 2..3) return "This GGUF version ($version) is not supported."
        val tensors = le(h, 8, 8)
        val keys = le(h, 16, 8)
        if (tensors !in 1..1_000_000 || keys !in 1..100_000) return "The model file's header looks damaged."
        return null
    }

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    sealed interface Copy {
        /** Copied to [part]; [install] moves it into place. */
        class Done(val part: File, val bytes: Long) : Copy
        class Failed(val reason: String, val cancelled: Boolean = false) : Copy
    }

    /**
     * Copies the document at [uri] into the app as a not-yet-installed part file, checking the GGUF header first and the
     * free space. Reports [progress] (bytes so far, total or -1) a few times a second. A failed or cancelled copy leaves
     * nothing behind.
     */
    fun copy(ctx: Context, uri: Uri, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Copy {
        val cr = ctx.contentResolver
        val total = try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) { -1L }
        val dir = dir(ctx)
        dir.mkdirs()
        if (total in 1 until MIN_BYTES) return Copy.Failed("That file is too small (${total shr 10} KB) to be the model.")
        if (total > 0 && dir.usableSpace < total + SPARE_BYTES)
            return Copy.Failed("Not enough free storage: the model needs ${total shr 20} MB and ${dir.usableSpace shr 20} MB are free.")
        val part = part(ctx)
        var ok = false
        try {
            val input: InputStream = cr.openInputStream(uri) ?: return Copy.Failed("The file could not be opened.")
            input.use { inp ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(1 shl 20)
                    // the header first: do not copy 400 MB of something else
                    var n = 0
                    while (n < 24) { val r = inp.read(buf, n, 24 - n); if (r < 0) break; n += r }
                    headerProblem(buf, n)?.let { return Copy.Failed(it) }
                    out.write(buf, 0, n)
                    var done = n.toLong()
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) return Copy.Failed("Copy cancelled.", cancelled = true)
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 200) { lastReport = now; progress(done, total) }
                    }
                    out.flush()
                    out.fd.sync()
                    progress(done, total)
                    if (total > 0 && done != total) return Copy.Failed("The copy is incomplete (${done shr 20} of ${total shr 20} MB).")
                    if (done < MIN_BYTES) return Copy.Failed("That file is too small (${done shr 10} KB) to be the model.")
                    ok = true
                    return Copy.Done(part, done)
                }
            }
        } catch (e: Exception) {
            return Copy.Failed("Could not copy the file: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (!ok) part.delete()
        }
    }

    /** Moves a finished copy into place (replacing any old model). */
    fun install(part: File, target: File): Boolean {
        if (part.renameTo(target)) return true
        target.delete()
        val moved = part.renameTo(target)
        if (!moved) part.delete()
        return moved
    }
}
