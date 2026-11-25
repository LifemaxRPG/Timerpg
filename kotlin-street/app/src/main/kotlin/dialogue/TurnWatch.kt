package com.timerpg.street.dialogue

import java.util.concurrent.atomic.AtomicBoolean

/**
 * A spoken turn's watch: which stage it is at (heard, looking up memories, asked the model, writing) and since when, and
 * whether it has ended. A turn that gives the player nothing in [GIVE_UP_MS] is ended by the watchdog with the topic's
 * template and a breadcrumb naming the stage it was stuck at (0.1.930: "What's your name" waited 43 s on a memory lookup
 * that never came back, with "thinking" on screen and nothing logged). Whichever ends it first wins ([end]): the reply,
 * the watchdog or the player walking away.
 */
class TurnWatch(private val clock: () -> Long = ::nowMs) {
    private val t0 = clock()
    private val ended = AtomicBoolean(false)
    @Volatile var stage = "heard"
        private set
    @Volatile private var stageAt = t0

    fun stage(s: String) { stage = s; stageAt = clock() }

    /** True for the first caller only (the reply, or the watchdog); false once the turn has ended. */
    fun end(): Boolean = ended.compareAndSet(false, true)
    val isEnded: Boolean get() = ended.get()

    /** "looking up memories for 12004 ms (turn 12010 ms)". */
    fun note(): String { val now = clock(); return "$stage for ${now - stageAt} ms (turn ${now - t0} ms)" }

    companion object {
        /** A turn with nothing for the player by then gets the template. */
        const val GIVE_UP_MS = 12_000L
        fun nowMs(): Long = System.nanoTime() / 1_000_000
    }
}

/** [deliver] called at most once: by the result or by its deadline, whichever comes first; the other is dropped. */
class Once<T>(private val deliver: (T) -> Unit) {
    private val done = AtomicBoolean(false)
    fun offer(v: T): Boolean = if (done.compareAndSet(false, true)) { deliver(v); true } else false
    val isDone: Boolean get() = done.get()
}
