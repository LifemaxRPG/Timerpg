package com.timerpg.street.dialogue

import java.util.PriorityQueue
import kotlin.math.abs

/**
 * The lines still to be written ahead of time, nearest the player first (a "render distance"): the buildings within
 * [near] building widths of the player on the street they are on, then the rest of that street outwards, then the other
 * street from its crossroads end (where the player will come out of the corner turn). Ties keep the order the jobs were
 * made in (an NPC's topics in order). Re-sorted whenever the player moves to another building or street. Not thread-safe:
 * the engine guards it with its lock.
 *
 * [lengths]: buildings on each street. The crossroads is at the far end of street 0 (the main street) and at the start of
 * the others (as GameState.cornerAtEnd has it).
 */
class PregenQueue(private val lengths: IntArray, private val near: Int = 2) {
    /** A building: its street and its place along it. */
    data class Spot(val street: Int, val index: Int)

    /** One line to write. [spots]: every building where it is said (the same person can own a shop and head a home). */
    class Job(val key: String, val spots: List<Spot>, val order: Int)

    var street = 0
        private set
    var position = 0
        private set
    private val jobs = HashMap<String, Job>()
    private var heap = PriorityQueue(16, comparator())

    /** Where the crossroads is on [street]. */
    fun junction(street: Int): Int = if (street == 0) (lengths.getOrElse(0) { 1 } - 1).coerceAtLeast(0) else 0

    /**
     * The walkable town: the streets (rows) next to the player's, nearest first ([setLive]); their people come after the
     * player's own street and before the rest of the town.
     */
    var live: List<Int> = emptyList()
        private set

    /** Smaller is sooner: tier (0 near the player, 1 the rest of their street, then the live rows, 2 another street), then distance. */
    fun rank(spot: Spot): Long =
        if (spot.street == street) {
            val d = abs(spot.index - position)
            (if (d <= near) 0L else 1L) * TIER + d
        } else live.indexOf(spot.street).let { k ->
            if (k >= 0) TIER + TIER / 2 + k * 1000L + spot.index
            else 2L * TIER + abs(spot.index - junction(spot.street))
        }

    /** The rows kept live round the player (see [live]); true if that changed the order. */
    fun setLive(rows: List<Int>): Boolean {
        if (rows == live) return false
        live = rows
        heap = PriorityQueue(maxOf(16, jobs.size), comparator()).apply { addAll(jobs.values) }
        return true
    }

    fun rank(job: Job): Long = job.spots.minOfOrNull { rank(it) } ?: (3L * TIER)

    private fun comparator(): Comparator<Job> = compareBy<Job>({ rank(it) }, { it.order })

    /**
     * The player is at building [index] of [street] (-1: between buildings, so where they were, or the crossroads end of a
     * street they have just come round the corner into). Returns true if the order changed.
     */
    fun setPlayer(street: Int, index: Int): Boolean {
        val pos = if (index >= 0) index else if (street == this.street) position else junction(street)
        if (street == this.street && pos == position) return false
        this.street = street
        position = pos
        heap = PriorityQueue(maxOf(16, jobs.size), comparator()).apply { addAll(jobs.values) }
        return true
    }

    /** Adds a job (a job already queued under the same key is kept). */
    fun add(job: Job) {
        if (jobs.containsKey(job.key)) return
        jobs[job.key] = job
        heap.add(job)
    }

    fun remove(key: String): Boolean {
        val j = jobs.remove(key) ?: return false
        heap.remove(j)
        return true
    }

    fun poll(): Job? {
        val j = heap.poll() ?: return null
        jobs.remove(j.key)
        return j
    }

    operator fun contains(key: String): Boolean = key in jobs
    val size: Int get() = jobs.size

    fun clear() {
        jobs.clear()
        heap.clear()
    }

    /** Every job in the order they would be polled. */
    fun ordered(): List<Job> = jobs.values.sortedWith(comparator())

    private companion object {
        const val TIER = 1_000_000L
    }
}
