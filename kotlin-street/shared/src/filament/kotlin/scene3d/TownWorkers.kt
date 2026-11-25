package com.lifemax.rpg.feature.habits.presentation.scene3d

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The town's build workers (`town-build` threads): a fixed pool whose queue is ordered by priority, not by arrival
 * (TOWN.md, "The first view first"). Lower runs sooner; equal priorities run in the order given. What the first view
 * waits for comes first: the ground and the yards ([NOW]), then the buildings by their distance from the player
 * ([submit] with that distance), then what the first view does not wait for (the hand-made landmarks, the stores, the
 * cinema, the farms and the world round the town: [LATER] plus their distance, or a [lane] of them). A job already
 * running is never stopped: a worker that finds only later work takes it, and the next near job waits for the first
 * worker to come free.
 *
 * Every job is timed (how long it waited in the queue, how long it ran) and told to [TownJobs.onJob] (the app writes
 * them to the play log as `span` lines).
 */
internal class TownWorkers(threads: Int) : ThreadPoolExecutor(
    threads, threads, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>(),
    { r -> Thread(r, "town-build").also { it.isDaemon = true; it.priority = Thread.NORM_PRIORITY - 1 } },
) {
    init { TownJobs.threads = threads }
    private val order = AtomicLong()

    /** A queued job: [task] run at [priority] (lower first, then in the order given), named for the log. */
    private inner class Job(val task: Runnable, val priority: Float, val kind: String, val name: String?) : Runnable, Comparable<Job> {
        val seq = order.incrementAndGet()
        val queuedAt = System.nanoTime()
        override fun compareTo(other: Job): Int = if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)
        override fun run() {
            val t0 = System.nanoTime()
            TownJobs.started(t0)
            try { task.run() } finally {
                val t1 = System.nanoTime()
                TownJobs.ended(t0, t1)
                TownJobs.done(kind, name, (t0 - queuedAt) / 1e6f, (t1 - t0) / 1e6f, priority)
            }
        }
    }

    // (anything given without a priority, a plain submit or execute: at once, in order)
    override fun execute(command: Runnable) {
        super.execute(command as? Job ?: Job(command, NOW, "job", null))
    }

    /** Runs [c] at [priority] (lower first); [kind] and [name] name it in the play log. */
    fun <T> submit(priority: Float, kind: String, name: String?, c: Callable<T>): Future<T> {
        val f = FutureTask(c)
        super.execute(Job(f, priority, kind, name))
        return f
    }

    /** Runs [r] at [priority]. */
    fun execute(priority: Float, kind: String, name: String?, r: Runnable) {
        super.execute(Job(r, priority, kind, name))
    }

    /** These workers as an [ExecutorService] whose every job runs at [priority], named [kind] (for code given a pool). */
    fun lane(priority: Float, kind: String): ExecutorService = object : AbstractExecutorService() {
        override fun execute(command: Runnable) = this@TownWorkers.execute(priority, kind, null, command)
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = this@TownWorkers.isShutdown
        override fun isTerminated(): Boolean = this@TownWorkers.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = this@TownWorkers.awaitTermination(timeout, unit)
    }

    companion object {
        /** The first view's own work: the ground, the yards, anything given no priority. */
        const val NOW = 0f
        /**
         * Added to the distance of work the first view does not wait for: after every building within
         * [TownRun.READY_RADIUS] of the player (one that near has a priority under it).
         */
        const val LATER = TownRun.READY_RADIUS
        /** Work for when there is nothing else to do (a far landmark's height field, ahead of the player coming near). */
        const val IDLE = 1e9f
    }
}

/**
 * The town's worker jobs as they finish, for the play log (the app sets [onJob]: one `span` line a job). Jobs given no
 * name ("job") are told only when they ran [QUIET_MS] or more, so the small ones (a picture warmed, a tree cell) do not
 * fill the log.
 */
object TownJobs {
    /** (kind, name or null, ms waiting in the queue, ms running, priority); called on the worker's thread. */
    @Volatile var onJob: ((kind: String, name: String?, waitMs: Float, runMs: Float, priority: Float) -> Unit)? = null
    const val QUIET_MS = 20f

    /** How many workers the town has (0: none made yet). */
    @Volatile var threads = 0
    private val running = java.util.concurrent.ConcurrentHashMap<Thread, Long>()
    private val doneNs = java.util.concurrent.atomic.AtomicLong()
    internal fun started(t0: Long) { running[Thread.currentThread()] = t0 }
    internal fun ended(t0: Long, t1: Long) { running.remove(Thread.currentThread()); doneNs.addAndGet(t1 - t0) }

    /**
     * The workers' time spent in jobs so far (ns, summed over the workers, the running jobs' part included): the difference
     * between two readings is how long they worked between them (the app's performance hint for the workers, docs/HEAT.md).
     */
    fun busyNs(now: Long = System.nanoTime()): Long { var b = doneNs.get(); for (s in running.values) b += now - s; return b }

    internal fun done(kind: String, name: String?, waitMs: Float, runMs: Float, priority: Float) {
        val f = onJob ?: return
        if (name == null && runMs < QUIET_MS) return
        try { f(kind, name, waitMs, runMs, priority) } catch (_: Throwable) {}
    }
}
