package com.timerpg.street.llm

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One lane for every heavy model on the phone: the dialogue model, speech recognition, memory search and the voices take
 * turns (fair, first come first served) instead of running at once. A conversation is a chain anyway (hear, remember,
 * think, speak), so nothing waits that would not have waited; and only one model's weights and buffers are busy at a
 * time, so the phone's RAM holds the one at work (the weights are mapped from their files: an idle model's pages are
 * dropped by the system and read back when it runs again), and all the CPU cores go to it.
 *
 * A native call that never returns would hold the lane for good (0.1.930: the memory model ran on the NPU's session and
 * never came back; every spoken turn after it waited behind it with no reply). So the dialogue waits for the lane at most
 * a while ([run] with a wait: [Busy]), and the lane says who holds it and since when ([describe]) for the breadcrumbs.
 */
object AiLane {
    private val lock = ReentrantLock(true)
    /** What is running now ("dialogue", "speech", "memory", "voice"), for the AI panel. */
    @Volatile var current: String? = null
        private set
    /** The thread running [current], and since when (System.nanoTime). */
    @Volatile private var holder: String? = null
    @Volatile private var sinceNs = 0L

    /** The lane was not free within the wait: [holder] had it, [heldMs] by then. */
    class Busy(val what: String, val holder: String?, val heldMs: Long, val waitedMs: Long) :
        RuntimeException("the AI lane was held by ${holder ?: "?"} for $heldMs ms (waited $waitedMs ms for $what)")

    /**
     * The dialogue model runs off the CPU (the NPU or the GPU): the voices need not wait for it, they run beside it on
     * the CPU ([voice]). Set by the dialogue engine when it loads.
     */
    @Volatile var dialogueOffCpu = false

    /** A voice's work: in the lane while the dialogue model shares the CPU, beside it when the model is on the NPU or GPU. */
    fun <T> voice(block: () -> T): T = if (dialogueOffCpu) block() else run("voice", block)

    fun <T> run(what: String, block: () -> T): T = lock.withLock { held(what, block) }

    /** [block] in the lane if it is free within [waitMs]; else [Busy] (nothing run). */
    fun <T> run(what: String, waitMs: Long, block: () -> T): T {
        val t0 = System.nanoTime()
        val got = try { lock.tryLock(waitMs, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!got) throw Busy(what, current?.let { "$it (${holder ?: "?"})" }, heldMs(), (System.nanoTime() - t0) / 1_000_000)
        try { return held(what, block) } finally { lock.unlock() }
    }

    private fun <T> held(what: String, block: () -> T): T {
        val before = current; val beforeHolder = holder; val beforeSince = sinceNs
        current = what; holder = Thread.currentThread().name; sinceNs = System.nanoTime()
        try { return block() } finally { current = before; holder = beforeHolder; sinceNs = beforeSince }
    }

    /** How long the lane has been held by [current] (0 when free). */
    fun heldMs(): Long = if (current == null) 0 else (System.nanoTime() - sinceNs) / 1_000_000

    /** "memory (npc-memory) for 21400 ms, 2 waiting", or "free": for the breadcrumbs. */
    fun describe(): String {
        val c = current ?: return "free" + lock.queueLength.takeIf { it > 0 }?.let { ", $it waiting" }.orEmpty()
        return "$c (${holder ?: "?"}) for ${heldMs()} ms" + lock.queueLength.takeIf { it > 0 }?.let { ", $it waiting" }.orEmpty()
    }
}
