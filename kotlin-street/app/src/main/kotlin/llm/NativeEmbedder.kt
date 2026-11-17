package com.timerpg.street.llm

import android.util.Log
import java.io.File

/** Text to a unit vector (Qwen3-Embedding, natively), for finding the memories that bear on what is said. Blocking. */
class NativeEmbedder : AutoCloseable {
    private var handle = 0L
    @Volatile var loaded = false
        private set
    var error: String? = null
        private set

    fun load(model: File, threads: Int = NativeLlm.defaultThreads()): Boolean = synchronized(this) {
        if (loaded) return true
        if (!libraryOk) { error = "The memory engine is not available on this device."; return false }
        if (!model.isFile) { error = "The memory model is not downloaded."; return false }
        if (handle == 0L) handle = runCatching { nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) { error = "Could not create the memory engine."; return false }
        loaded = nativeLoad(handle, model.path.toByteArray(), threads)
        error = if (loaded) null else nativeError(handle) ?: "Could not load the memory model."
        loaded
    }

    /** The vector of [text]; null on failure. */
    fun embed(text: String): FloatArray? = synchronized(this) {
        if (!loaded) return null
        nativeEmbed(handle, text.toByteArray()) ?: run { error = nativeError(handle); null }
    }

    override fun close() = synchronized(this) {
        if (handle != 0L) runCatching { nativeDestroy(handle) }
        handle = 0L
        loaded = false
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeLoad(handle: Long, model: ByteArray, threads: Int): Boolean
    private external fun nativeError(handle: Long): String?
    private external fun nativeEmbed(handle: Long, text: ByteArray): FloatArray?

    companion object {
        private val libraryOk: Boolean by lazy {
            runCatching { System.loadLibrary("timerpg_llm"); true }.getOrElse { Log.w("NativeEmbedder", "native library not loaded", it); false }
        }
    }
}
