package com.lifemax.rpg.feature.habits.presentation.scene3d

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance

/**
 * What streaming lets go of, destroyed a little each frame instead of all at once (docs/HEAT.md, "Streaming hitches").
 *
 * Measured on the route (GpuRouteTest with a sampling profile): nearly all of a streaming step's 40-250 ms worst frames
 * were `Engine.destroyMaterialInstance`, called once for every batch of a building, a lot's yard or a chunk's impostors
 * as it went (Filament's destroy costs far more than its create, and a lot has dozens). Now what goes leaves the scene at
 * once (its entities removed and destroyed: nothing of it is drawn again), and its material instances, then the textures
 * only it held, are destroyed in order over the next frames within [drain]'s budget.
 *
 * The engine's thread only (as every Filament call); [drainAll] before the engine or the materials go.
 */
object Graveyard {
    private val queue = java.util.ArrayDeque<() -> Unit>()
    /** Off: everything is destroyed at once, as before (to compare: -Dgraveyard=0). */
    @JvmStatic var enabled = System.getProperty("graveyard") != "0"
    /** The longest the queue has been, and how many items it has taken (reports). */
    var peak = 0; private set
    var buried = 0L; private set

    /** [mi] destroyed later (its renderable already gone). */
    fun bury(engine: Engine, mi: MaterialInstance) = later { engine.destroyMaterialInstance(mi) }

    /** [f] run later, after everything buried before it (a texture released after the instances that wore it). */
    fun later(f: () -> Unit) {
        if (!enabled) { f(); return }
        queue.addLast(f); buried++
        if (queue.size > peak) peak = queue.size
    }

    val size: Int get() = queue.size

    /**
     * Destroys what waits for up to [budgetMs] (at least one item; twice the budget while more than [BACKLOG] wait, so the
     * queue never grows without end on a long drive).
     */
    fun drain(budgetMs: Float) {
        if (queue.isEmpty()) return
        val t0 = System.nanoTime()
        val ms = if (queue.size > BACKLOG) budgetMs * 2f else budgetMs
        do {
            queue.pollFirst()?.invoke()
        } while (queue.isNotEmpty() && (System.nanoTime() - t0) / 1e6f < ms)
    }

    /** Everything now (the town going, its engine or materials next). */
    fun drainAll() { while (queue.isNotEmpty()) queue.pollFirst()?.invoke() }

    const val BACKLOG = 2000
}
