package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalkView
import kotlin.math.hypot

/**
 * The town as the game draws it each frame (TOWN.md T10), the one path the app and the desktop preview share: the ground
 * made on workers and uploaded in budgeted steps ([FilamentTown.startGround], [FilamentTown.uploadGround]), then the
 * buildings nearest the player first ([FilamentTown.buildNearest]), the street view's true camera from the walk
 * ([TownFrame.street], [CameraRig]'s eye), the town lit for the hour, and the people of [TownPlay] stood in it. The
 * screen positions of people (speech bubbles, who hears the player) come from the same camera ([TownPlay.projector]).
 */
class TownRun(val town: FilamentTown, sheets: List<ImageBitmap> = emptyList()) {
    private val t0 = System.nanoTime()
    private var groundDone = false
    private var finished = false
    /** Milliseconds from the start until the first view was ready (-1 until then). */
    var firstViewMs = -1f; private set
    /** The pose drawn last. */
    var pose: TownPose? = null; private set
    /** People drawn last frame. */
    var peopleDrawn = 0; private set
    /** Where the people's heads fall on the screen: the town's camera as last drawn (made once, not a frame). */
    private val projector: (Float, Float, Float) -> androidx.compose.ui.geometry.Offset? = town::project

    /** The ground is still coming in, or buildings are left to build: frames should keep coming while the player stands. */
    val busy: Boolean get() = !groundDone || town.pending > 0
    /** The ground in and every building built (or known to have no art): the town's whole build is over. */
    val done: Boolean get() = groundDone && town.pending == 0

    init {
        // (the heap is watched from here to the whole town's end: the breadcrumbs and the AI panel's RAM line say it)
        TownMemory.start()
        town.startGround()
        // the character sheets read on a worker while the first view is built (the people stand only once it is ready)
        town.warm(sheets)
    }

    /**
     * Spends up to [budgetMs] of this frame building (the ground's uploads first, then the nearest buildings); true once
     * the view at ([x], [z]) is ready (the ground in, every building within [READY_RADIUS] built).
     */
    fun build(x: Float, z: Float, budgetMs: Float): Boolean {
        val s = System.nanoTime()
        if (!groundDone) { groundDone = town.uploadGround(budgetMs); if (groundDone) TownMemory.mark("ground in") }
        val left = budgetMs - (System.nanoTime() - s) / 1e6f
        if (groundDone || left > 1f) town.buildNearest(x, z, left.coerceAtLeast(1f))
        val ok = ready(x, z)
        if (ok && firstViewMs < 0f) { firstViewMs = (System.nanoTime() - t0) / 1e6f; TownMemory.mark("first view") }
        if (ok && !finished && done) { finished = true; TownMemory.mark("all built"); TownMemory.stop() }
        return ok
    }

    /** The ground in and every building within [READY_RADIUS] of ([x], [z]) built (or known to have no art). */
    fun ready(x: Float, z: Float): Boolean =
        groundDone && town.slots.none { !it.isBuilt && !it.missing && hypot(it.cx - x, it.cz - z) < READY_RADIUS }

    /** How far the first view's building has come (0..1): the ground counts half, the buildings near the player the rest. */
    fun progress(x: Float, z: Float): Float {
        val near = town.slots.filter { hypot(it.cx - x, it.cz - z) < READY_RADIUS }
        val b = if (near.isEmpty()) 1f else near.count { it.isBuilt || it.missing }.toFloat() / near.size
        return (if (groundDone) .5f else .25f) + .5f * b * (if (groundDone) 1f else .5f)
    }

    /**
     * Aims the street view at [walk] on a [w] x [h] screen, lights [hour], and stands this frame's people ([seconds] of
     * the street's clock) wearing [sheets]. Then render [FilamentTown.view].
     */
    fun draw(walk: TownWalkView, w: Float, h: Float, hour: Float, seconds: Float, sheets: List<ImageBitmap>): TownPose {
        val p = TownFrame.street(walk, w, h)
        town.frame(p, hour, seconds.toDouble())
        // (no people behind the loading screen: their sheets are still being read, and nobody sees them)
        val n = if (TownPlay.walk === walk && firstViewMs >= 0f) TownPlay.people(seconds) else 0
        peopleDrawn = town.people(TownPlay.figures, n, sheets)
        TownPlay.projector = projector
        pose = p
        return p
    }

    companion object {
        /** The first view waits for the buildings this near the player (street units): the street in view and the next blocks. */
        const val READY_RADIUS = 1800f
    }
}
