package com.timerpg.street.llm

import android.util.Log
import java.io.File

/** Whether a memory bears on what is said (Qwen3-Reranker, natively): the chance of "yes". Blocking. */
class NativeReranker : AutoCloseable {
    private var handle = 0L
    @Volatile var loaded = false
        private set
    var error: String? = null
        private set

    fun load(model: File, threads: Int = NativeLlm.defaultThreads()): Boolean = synchronized(this) {
        if (loaded) return true
        if (!libraryOk) { error = "The memory engine is not available on this device."; return false }
        if (!model.isFile) { error = "The memory judge is not downloaded."; return false }
        if (handle == 0L) handle = runCatching { nativeCreate() }.getOrDefault(0L)
        if (handle == 0L) { error = "Could not create the memory judge."; return false }
        loaded = nativeLoad(handle, model.path.toByteArray(), threads)
        error = if (loaded) null else nativeError(handle) ?: "Could not load the memory judge."
        loaded
    }

    /** 0..1, or < 0 on failure. */
    fun score(instruction: String, query: String, doc: String): Float = synchronized(this) {
        if (!loaded) return -1f
        nativeScore(handle, instruction.toByteArray(), query.toByteArray(), doc.toByteArray())
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
    private external fun nativeScore(handle: Long, inst: ByteArray, query: ByteArray, doc: ByteArray): Float

    companion object {
        private val libraryOk: Boolean by lazy {
            runCatching { System.loadLibrary("timerpg_llm"); true }.getOrElse { Log.w("NativeReranker", "native library not loaded", it); false }
        }
    }
}
