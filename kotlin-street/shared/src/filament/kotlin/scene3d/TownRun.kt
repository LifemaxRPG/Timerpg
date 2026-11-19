package com.lifemax.rpg.feature.habits.presentation.scene3d

import androidx.compose.ui.graphics.ImageBitmap
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene.TownWalkView
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

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
        if (finished) com.lifemax.rpg.feature.habits.presentation.scene.Stage3D.townBuilding = false
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
        val p = if (OrbitCamera.open) orbit(walk, w, h, seconds) else TownFrame.street(walk, w, h)
        town.frame(p, hour, seconds.toDouble())
        // (no people behind the loading screen: their sheets are still being read, and nobody sees them)
        val n = if (TownPlay.walk === walk && firstViewMs >= 0f) TownPlay.people(seconds) else 0
        peopleDrawn = town.people(TownPlay.figures, n, sheets)
        TownPlay.projector = projector
        pose = p
        return p
    }

    private var lastSeconds = Float.NaN

    /**
     * The open world's camera this frame: [OrbitCamera] eased by the clock's step (only the player's own input), following
     * the walk's player, as [TownFrame.orbit]. It never moves by itself: whatever stands between it and the player (a
     * building, a yard's shed or tree) or right by the eye is cut away ([FilamentTown.occlude], [FilamentTown.fadeBetween]).
     * Only with [OrbitCamera.pullIn] on, and only when the eye itself would stand inside a building, does it come in to
     * that building's wall, eased in and out. The stage's sky under it is told the lens and the horizon
     * ([OrbitCamera.lensPx], [OrbitCamera.horizonY]).
     */
    fun orbit(walk: TownWalkView, w: Float, h: Float, seconds: Float): TownPose {
        val dt = if (lastSeconds.isNaN()) 0f else (seconds - lastSeconds).coerceIn(0f, .1f)
        lastSeconds = seconds
        val c = OrbitCamera
        // (a new scene far from where the camera last was: a new game, a spawn, a jump: behind the player, at the start's
        // distance and pitch; coming back out of a room it is where it was; back from the street view: behind the player)
        val heading = Math.toDegrees(walk.heading.toDouble()).toFloat()
        if (dt == 0f && hypot(c.tx - walk.wx, c.tz - walk.wz) > RESET_FAR) c.reset(walk.wx, walk.wz, heading)
        else if (c.behindNext) c.behind(walk.wx, walk.wz, heading)
        c.behindNext = false
        c.step(dt)
        c.follow(walk.wx, walk.wz)
        // (the open walk's stick pushes relative to where the camera looks; the walk never turns the camera)
        (walk as? com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk)?.cameraYaw = c.yawRad
        val want = TownFrame.orbit(walk.wx, OrbitCamera.EYE, walk.wz, c.yaw, c.pitch, c.dist, w, h, OrbitCamera.FOV_Y)
        town.occlude(walk.wx, OrbitCamera.EYE, walk.wz, want.ex, want.ey, want.ez)
        val fit = if (c.pullIn && town.eyeInside < 1f) (town.eyeInside * c.dist - PULL_MARGIN).coerceIn(min(PULL_MIN, c.dist), c.dist) else c.dist
        town.fadeBetween(fit / c.dist)
        // (eased both ways: in quickly, out slowly; never a jump)
        val tau = if (fit < c.pulled) PULL_IN else PULL_OUT
        // (no pull: the camera stands where the player put it, their own zoom unlagged)
        c.pulled = if (dt == 0f || !c.pullIn) fit else c.pulled + (fit - c.pulled) * (1f - kotlin.math.exp(-dt / tau))
        if (c.pulled > c.dist) c.pulled = c.dist
        val p = if (abs(c.pulled - c.dist) < .5f) want else TownFrame.orbit(walk.wx, OrbitCamera.EYE, walk.wz, c.yaw, c.pitch, c.pulled, w, h, OrbitCamera.FOV_Y)
        c.lensPx = p.fl
        // (the horizon: where a level line from the eye falls; above the screen's top when looking well down)
        c.horizonY = p.cy - p.fl * (-p.fy) / hypot(p.fx, p.fz).coerceAtLeast(1e-4f)
        return p
    }

    companion object {
        /** The orbit camera stays this far in front of a wall it is pulled in to, and never nearer the player than [PULL_MIN]. */
        const val PULL_MARGIN = 8f
        const val PULL_MIN = 60f
        /** The orbit camera starts afresh behind the player when a scene starts this far from where it last followed. */
        const val RESET_FAR = 300f
        /** Seconds it takes to ease in to a wall ([OrbitCamera.pullIn]) and back out once clear. */
        const val PULL_IN = .15f
        const val PULL_OUT = .5f

        /** The first view waits for the buildings this near the player (street units): the street in view and the next blocks. */
        const val READY_RADIUS = 1800f
    }
}
