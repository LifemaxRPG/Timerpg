package com.timerpg.street.llm

import android.app.ActivityManager
import android.content.Context

/**
 * Watches the phone's free memory while a model's copy loads ([NpuGate]'s tries, the NPU fit test) and calls [stop] once
 * if it falls to Android's threshold plus [NpuGate.VALVE_MB]: the load is cancelled and the copy freed before the
 * low-memory killer reaches the game. Also keeps the lowest free memory seen and the highest [MemoryWatch] level.
 */
class LoadValve private constructor(private val ctx: Context, private val onHit: () -> Unit, private val periodMs: Long) {
    /** Why it stopped the load, or null. */
    @Volatile var hit: String? = null; private set
    @Volatile var minFreeMb = Long.MAX_VALUE; private set
    @Volatile var maxLevel = 0; private set
    @Volatile var sawLow = false; private set
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "load-valve").apply { isDaemon = true }

    private fun loop() {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return
        val mi = ActivityManager.MemoryInfo()
        while (running) {
            runCatching {
                am.getMemoryInfo(mi)
                val free = mi.availMem shr 20; val th = mi.threshold shr 20
                if (free < minFreeMb) minFreeMb = free
                if (mi.lowMemory) sawLow = true
                if (MemoryWatch.level > maxLevel) maxLevel = MemoryWatch.level
                if (hit == null && free < th + NpuGate.VALVE_MB) {
                    hit = "$free MB free, Android's threshold $th MB"
                    com.timerpg.street.shot.Crumbs.add("load valve: stopping the load ($hit)")
                    runCatching { onHit() }
                }
            }
            try { Thread.sleep(periodMs) } catch (e: InterruptedException) { break }
        }
    }

    fun stop() { running = false; thread.interrupt() }

    companion object {
        fun start(ctx: Context, periodMs: Long = 150, onHit: () -> Unit): LoadValve =
            LoadValve(ctx.applicationContext, onHit, periodMs).also { it.thread.start() }
    }
}
