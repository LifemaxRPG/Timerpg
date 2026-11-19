package com.timerpg.street.night

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * "A writer is writing the bank": a lock on a file (filesDir/writer.lock) held by the process whose session writes
 * (Writer mode and the overnight writer run in their own process, ":writer"). The game's process looks at it before it
 * reads the bank ([awaitFree]): the writer saves its last lines as it stops, and the game reads them. Android lets the
 * lock go with the process, so a killed writer never leaves it held.
 */
object WriterLock {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "writer.lock")

    /** Takes the lock for this process (false if another process holds it). */
    @Synchronized
    fun hold(ctx: Context): Boolean {
        if (lock != null) return true
        return try {
            val ch = RandomAccessFile(file(ctx), "rw").channel
            val l = ch.tryLock()
            if (l == null) { ch.close(); false } else { channel = ch; lock = l; true }
        } catch (e: Exception) { false }
    }

    @Synchronized
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null; channel = null
    }

    /** Another process writes the bank now. */
    @Synchronized
    fun heldElsewhere(ctx: Context): Boolean {
        if (lock != null) return false
        return try {
            RandomAccessFile(file(ctx), "rw").channel.use { ch -> val l = ch.tryLock(); if (l == null) true else { l.release(); false } }
        } catch (e: java.nio.channels.OverlappingFileLockException) { false } catch (e: Exception) { false }
    }

    /** Waits (up to [maxMs]) while another process writes the bank; true once it is free. Not on the main thread. */
    fun awaitFree(ctx: Context, maxMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + maxMs
        var told = false
        while (heldElsewhere(ctx)) {
            if (!told) { told = true; com.timerpg.street.shot.Crumbs.add("dialogue: the writer is saving its last lines; the bank is read once it has") }
            if (SystemClock.elapsedRealtime() > until) return false
            try { Thread.sleep(250) } catch (e: InterruptedException) { return false }
        }
        return true
    }
}
