package com.timerpg.street.dialogue

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Everything the game's AI needs, fetched once from Hugging Face into the app's own storage: the dialogue model (Qwen3.5-9B, the strongest Qwen that fits a phone)
 * both voice engines (Kokoro-82M and Supertonic-3) and the speech recognizer (Qwen3-ASR), about 8.9 GB. Downloads resume where they stopped, every file's
 * size is checked, and nothing is used until it is complete. Blocking IO: run it off the main thread.
 */
object ModelDownloads {
    private const val KOKORO = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/"
    private const val SUPERTONIC = "https://huggingface.co/Supertone/supertonic-3/resolve/main/"
    private const val ASR = "https://huggingface.co/ggml-org/Qwen3-ASR-1.7B-GGUF/resolve/main/"
    private const val EMBED_URL = "https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf"
    private const val EMBED_BYTES = 639_150_592L
    private const val QWEN = "https://huggingface.co/unsloth/Qwen3.5-9B-GGUF/resolve/main/Qwen3.5-9B-Q4_K_M.gguf"

    /** One file: where it comes from, where it goes (under filesDir/models), and its size in bytes. */
    class Item(val url: String, val path: String, val bytes: Long)

    // Kokoro's English voices (US and UK); the game blends two per NPC
    private val KOKORO_VOICES = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river", "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
    )
    private val SUPERTONIC_STYLES = listOf("F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5")

    val ITEMS: List<Item> = buildList {
        add(Item(QWEN, ModelFiles.NAME, 5_680_522_464L))
        // hearing the player: Qwen3-ASR 1.7B and its audio encoder
        add(Item(ASR + "Qwen3-ASR-1.7B-Q8_0.gguf", com.timerpg.street.voice.PlayerVoice.ASR_MODEL, 2_165_034_944L))
        add(Item(ASR + "mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", com.timerpg.street.voice.PlayerVoice.ASR_MMPROJ, 355_709_344L))
        // the people's memories: Qwen3-Embedding
        add(Item(EMBED_URL, MemoryService.MODEL, EMBED_BYTES))
        add(Item(KOKORO + "onnx/model.onnx", "kokoro/model.onnx", 325_532_232L))
        add(Item(KOKORO + "tokenizer.json", "kokoro/tokenizer.json", 3_497L))
        for (v in KOKORO_VOICES) add(Item(KOKORO + "voices/$v.bin", "kokoro/voices/$v.bin", 522_240L))
        add(Item(SUPERTONIC + "onnx/tts.json", "supertonic/onnx/tts.json", 8_253L))
        add(Item(SUPERTONIC + "onnx/unicode_indexer.json", "supertonic/onnx/unicode_indexer.json", 277_676L))
        add(Item(SUPERTONIC + "onnx/duration_predictor.onnx", "supertonic/onnx/duration_predictor.onnx", 3_700_147L))
        add(Item(SUPERTONIC + "onnx/text_encoder.onnx", "supertonic/onnx/text_encoder.onnx", 36_416_150L))
        add(Item(SUPERTONIC + "onnx/vector_estimator.onnx", "supertonic/onnx/vector_estimator.onnx", 256_534_781L))
        add(Item(SUPERTONIC + "onnx/vocoder.onnx", "supertonic/onnx/vocoder.onnx", 101_424_195L))
        for (s in SUPERTONIC_STYLES) add(Item(SUPERTONIC + "voice_styles/$s.json", "supertonic/voice_styles/$s.json", -1L))
    }

    val totalBytes: Long get() = ITEMS.sumOf { if (it.bytes > 0) it.bytes else 300_000L }

    fun dir(ctx: Context): File = ModelFiles.dir(ctx)

    /** The files still missing (or the wrong size). */
    fun missing(ctx: Context): List<Item> = ITEMS.filter { val f = File(dir(ctx), it.path); !f.isFile || (it.bytes > 0 && f.length() != it.bytes) }

    sealed interface Result {
        object Done : Result
        class Failed(val reason: String, val cancelled: Boolean = false) : Result
    }

    /**
     * Downloads every missing file, reporting (bytes done so far across everything, total) a few times a second.
     * [cancelled] is checked between chunks; a cancelled or failed download keeps its partial file to resume next time.
     */
    fun download(ctx: Context, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): Result {
        val root = dir(ctx); root.mkdirs()
        val todo = missing(ctx)
        val total = totalBytes
        var done = total - todo.sumOf { if (it.bytes > 0) it.bytes else 300_000L }
        val need = todo.sumOf { if (it.bytes > 0) it.bytes else 300_000L }
        if (root.usableSpace < need + (128L shl 20)) return Result.Failed("Not enough free storage: ${need shr 20} MB needed, ${root.usableSpace shr 20} MB free.")
        val buf = ByteArray(1 shl 20)
        var lastReport = 0L
        for (item in todo) {
            val target = File(root, item.path); target.parentFile?.mkdirs()
            val part = File(target.path + ".part")
            var attempt = 0
            while (true) {
                if (cancelled()) return Result.Failed("Download paused.", cancelled = true)
                val have = if (part.isFile) part.length() else 0L
                try {
                    val c = URL(item.url).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 20_000; c.readTimeout = 30_000
                    c.setRequestProperty("User-Agent", "timerpg-street")
                    if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                    val code = c.responseCode
                    if (code !in 200..299) { c.disconnect(); throw java.io.IOException("server answered $code") }
                    val append = code == 206 && have > 0
                    RandomAccessFile(part, "rw").use { out ->
                        if (append) out.seek(have) else out.setLength(0)
                        var got = if (append) have else 0L
                        c.inputStream.use { inp ->
                            while (true) {
                                if (cancelled()) { c.disconnect(); return Result.Failed("Download paused.", cancelled = true) }
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 250) { lastReport = now; progress(done + got, total) }
                            }
                        }
                        out.fd.sync()
                    }
                    c.disconnect()
                    val size = part.length()
                    if (item.bytes > 0 && size != item.bytes) { part.delete(); throw java.io.IOException("${item.path}: got $size bytes, expected ${item.bytes}") }
                    target.delete()
                    if (!part.renameTo(target)) throw java.io.IOException("could not move ${item.path} into place")
                    done += if (item.bytes > 0) item.bytes else 300_000L
                    progress(done, total)
                    break
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 4) return Result.Failed("Could not download ${item.path}: ${e.message ?: e.javaClass.simpleName}")
                    Thread.sleep(1500L * attempt)
                }
            }
        }
        return Result.Done
    }
}
