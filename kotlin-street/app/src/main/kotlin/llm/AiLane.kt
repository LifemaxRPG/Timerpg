package com.timerpg.street.llm

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One lane for every heavy model on the phone: the dialogue model, speech recognition, memory search and the voices take
 * turns (fair, first come first served) instead of running at once. A conversation is a chain anyway (hear, remember,
 * think, speak), so nothing waits that would not have waited; and only one model's weights and buffers are busy at a
 * time, so the phone's RAM holds the one at work (the weights are mapped from their files: an idle model's pages are
 * dropped by the system and read back when it runs again), and all the CPU cores go to it.
 */
object AiLane {
    private val lock = ReentrantLock(true)
    /** What is running now ("dialogue", "speech", "memory", "voice"), for the AI panel. */
    @Volatile var current: String? = null
        private set

    fun <T> run(what: String, block: () -> T): T = lock.withLock {
        val before = current
        current = what
        try { block() } finally { current = before }
    }
}
