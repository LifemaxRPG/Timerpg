package com.timerpg.street.llm

import android.util.Log
import java.io.File

/**
 * Speech to text for talking to NPCs: Qwen3-ASR (1.7B, llama.cpp's multimodal library) in the same native library as the
 * dialogue model. [load] and [transcribe] block (seconds): call them from a background thread, one at a time.
 */
class NativeAsr : AutoCloseable {
    private var handle = 0L
    @Volatile var loaded = false
        private set
    var error: String? = null
        private set
    /** The rate [transcribe] expects (16000), once loaded. */
    var sampleRate = 16000
        private set
    /** Milliseconds of the last transcription: reading the audio, and in all. */
    var lastEncodeMs = 0L
        private set
    var lastTotalMs = 0L
        private set

    fun load(model: File, mmproj: File, threads: Int = NativeLlm.defaultThreads()): Boolean = synchronized(this) {
        if (!libraryOk) { error = "The speech engine is not available on this device."; return false }
        if (!model.isFile || !mmproj.isFile) { error = "The speech model is not downloaded."; return false }
        if (handle == 0L) handle = runCatching { nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) { error = "Could not create the speech engine."; return false }
        loaded = nativeLoad(handle, model.path.toByteArray(), mmproj.path.toByteArray(), threads)
        if (loaded) { sampleRate = nativeRate(handle).takeIf { it > 0 } ?: 16000; error = null }
        else error = nativeError(handle) ?: "Could not load the speech model."
        Log.i(TAG, if (loaded) "speech model loaded ($sampleRate Hz, $threads threads)" else "speech model: $error")
        loaded
    }

    /** What was said in [pcm] (mono, [sampleRate]), with [context] words to expect (names). "" for silence, null on failure. */
    fun transcribe(pcm: FloatArray, context: String): String? = synchronized(this) {
        if (!loaded || handle == 0L) { error = "The speech model is not loaded."; return null }
        val t = nativeTranscribe(handle, pcm, context.toByteArray())
        nativeTimes(handle)?.let { lastEncodeMs = it[0]; lastTotalMs = it[1] }
        if (t == null) error = nativeError(handle) ?: "Transcription failed."
        t
    }

    /** Stops a transcription in progress (any thread). */
    fun cancel() { val h = handle; if (h != 0L) nativeCancel(h) }

    override fun close() {
        cancel()
        synchronized(this) {
            if (handle != 0L) runCatching { nativeDestroy(handle) }
            handle = 0L
            loaded = false
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoad(handle: Long, model: ByteArray, mmproj: ByteArray, threads: Int): Boolean
    private external fun nativeRate(handle: Long): Int
    private external fun nativeError(handle: Long): String?
    private external fun nativeCancel(handle: Long)
    private external fun nativeTranscribe(handle: Long, pcm: FloatArray, context: ByteArray): String?
    private external fun nativeTimes(handle: Long): LongArray?

    companion object {
        private const val TAG = "NativeAsr"
        private val libraryOk: Boolean by lazy {
            runCatching { System.loadLibrary("timerpg_llm"); true }.getOrElse { Log.w(TAG, "native library not loaded", it); false }
        }
    }
}
