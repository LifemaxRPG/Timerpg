package com.lifemax.rpg.feature.habits.presentation.scene3d

/**
 * The Java heap while the town builds (TOWN.md T10): the heap's limit and the most of it used, sampled every [PERIOD_MS]
 * on a daemon thread from [start] to [stop], with a mark at each stage ([mark]). The phone writes it to the breadcrumbs
 * and the AI panel's RAM line; the desktop preview to town.txt, and its memory check fails a build over the budget.
 *
 * What "used" means differs: on the phone (ART) a direct ByteBuffer is a Java array, so the heap used is everything the
 * build holds, and its peak is near what it truly needed (ART collects early and often). On the desktop the JVM lets
 * garbage pile up to the limit before it collects and keeps direct buffers outside the heap, so there [live] (the heap
 * just after the last collection: what was still held) and [direct] (the direct buffers held) are sampled as well, and
 * their sum is what the phone would have needed.
 */
object TownMemory {
    /** The heap used after the last collection (the desktop: its memory pools' collection usage), or null where unknown. */
    @Volatile var live: (() -> Long)? = null
    /** The direct buffers held (the desktop: the "direct" buffer pool), or null where they are on the heap (the phone). */
    @Volatile var direct: (() -> Long)? = null

    val limit: Long get() = Runtime.getRuntime().maxMemory()
    @Volatile var peakUsed = 0L; private set
    @Volatile var peakLive = 0L; private set
    @Volatile var peakDirect = 0L; private set
    /** The most of live heap and direct buffers held at once (the desktop's stand-in for the phone's heap). */
    @Volatile var peakHeld = 0L; private set
    private val marks = ArrayList<String>()
    @Volatile private var thread: Thread? = null

    fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Starts sampling from nothing (a new town's build). */
    @Synchronized fun start() {
        peakUsed = 0L; peakLive = 0L; peakDirect = 0L; peakHeld = 0L
        synchronized(marks) { marks.clear() }
        if (thread != null) return
        thread = Thread({
            while (thread === Thread.currentThread()) {
                sample()
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { break }
            }
        }, "town-memory").also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY; it.start() }
    }

    /** Stops sampling (the build is over); the peaks stay. */
    @Synchronized fun stop() { sample(); thread?.interrupt(); thread = null }

    fun sample() {
        val u = used()
        if (u > peakUsed) peakUsed = u
        val l = live?.invoke() ?: 0L
        if (l > peakLive) peakLive = l
        val d = direct?.invoke() ?: 0L
        if (d > peakDirect) peakDirect = d
        if (l + d > peakHeld) peakHeld = l + d
    }

    /** Notes the heap at stage [what] (e.g. "ground in"): its used and peak so far. */
    fun mark(what: String) {
        sample()
        synchronized(marks) { marks += "$what ${mb(used())}/${mb(peakUsed)}" }
    }

    /** One line: the limit, the peak used, the stages (MB). */
    fun summary(): String {
        val m = synchronized(marks) { marks.joinToString(", ") }
        val desk = if (live != null) ", live after GC peak ${mb(peakLive)} MB, direct buffers peak ${mb(peakDirect)} MB, held (live + direct) peak ${mb(peakHeld)} MB" else ""
        return "heap limit ${mb(limit)} MB, peak used ${mb(peakUsed)} MB$desk" + if (m.isEmpty()) "" else " (used/peak at: $m)"
    }

    fun mb(b: Long): Long = b / (1L shl 20)

    const val PERIOD_MS = 20L
}
