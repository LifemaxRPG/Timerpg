package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.round

/**
 * The game's player on the street. The platform's controls set [moveX] and [moveLane] (-1..1); each frame the
 * stage calls [step], which walks the player along the street and in and out across the pavement, and the camera
 * follows [x]. When [enabled] is false the stage behaves as the habit tab did.
 */
object GameState {
    var enabled = false
    /** Where along the street (street units), how far out across the pavement, and which way they face (1 right, -1 left). */
    var x = 0f
    // the front band of the pavement, clear of the furniture line (lane < 6.5) where the first lamp would be a wall
    var lane = 7f
    var dir = 1f
    /** Which of our character sheets the player is. */
    var look = 0
    /**
     * The push (-1..1 each, at most 1 long together; its strength sets the speed): along the street (right +), and across
     * it (up, away from us into the buildings, is negative; down towards the kerb positive).
     */
    var moveX = 0f
    var moveLane = 0f
    var walking = false
    /** Which of the sheet's four views the player shows: 0 right (flipped for left), 1 back (walking away), 3 front (towards us). */
    var facing = 0
    /** Ground walked (street units; across the pavement counts as deep ground). */
    var travel = 0f
    /** Index of the building the player is in front of (-1 past the street's ends). Snapshot state: the HUD recomposes when it changes. */
    var nearest by mutableIntStateOf(-1)
    /** True within reach of the street's crossroads. Snapshot state: the HUD recomposes when it changes. */
    var atCorner by mutableStateOf(false)
    /** -1 pressed against the street's start, 1 against its end, 0 between. Snapshot state, for the HUD. */
    var edge by mutableIntStateOf(0)
    /** Where this street's crossroads is: at its far (east) end, or at its start. */
    var cornerAtEnd = true
    /** The middle of the junction's opening along this street (street units), or NaN if the side street does not open here. */
    @Volatile var junctionX = Float.NaN
    /** Where the middle of the view is along the street (street units), as the stage last drew it. */
    @Volatile var viewMiddle = 0f
    /**
     * The people the player can talk to, standing at their buildings: (building index along this street, a look seed from
     * who they are). Drawn by the door, facing the street, so "Talk to ..." has someone to talk to.
     */
    @Volatile var keepers: List<Pair<Int, Int>> = emptyList()
    /** Half the screen's width in street units (set by the stage as it draws), so the camera can stop at the street's ends. */
    var halfView = 0f
    /** Device pixels per street unit, as the stage last drew (0 until it has drawn). */
    var unit = 0f
    /** The pavement line in device pixels from the top of the stage, as last drawn (0 until it has drawn). */
    var groundPx = 0f
    /** The legs: the push eased into a ground velocity, the run, the walk cycle and the lean (see [Locomotion]). */
    val motion = Locomotion()
    /** Walking speed along the street (street units a second): it eases in and out, and becomes a run at a full push. */
    var vx: Float
        get() = motion.vx
        set(v) { motion.vx = v }
    /** Where the legs are in their cycle (0..1, two steps), from the ground really walked; the walk's sprite frame follows it. */
    val gait get() = motion.gait
    /** Feet put down so far, on the street and in the rooms: one footstep sound each. */
    var footfalls = 0
    /** Running (a full push), for the footsteps and the stick. */
    var running = false
    /** How far the figure leans into its walk (degrees, forward positive). */
    val lean get() = motion.lean
    /** The run's spring: sheet pixels the raised frames of the cycle lift (0 at a walk). */
    val hop get() = if (motion.runShare > .5f) MoveTuning.hop else 0f
    /** The camera's look ahead of the player (street units along the street; see [CameraLead]). */
    val camera = CameraLead()
    private var x0 = 0f
    private var lane0 = 0f
    /** The stage's framing as it last drew (screen pixels): its ground line, its horizon, and screen pixels per sprite pixel. */
    var frameGround = 0f
    var frameHorizon = 0f
    var framePx = 0f
    /** How far the sky has panned (street units) while the corner turn swings the camera; 0 otherwise. */
    @Volatile var skyPan = 0f

    /** Walking speeds along the street and in and out across it (street units, lanes a second); a run is faster (see [MoveTuning]). */
    val SPEED get() = MoveTuning.walk
    val LANE_SPEED get() = MoveTuning.walk * MoveTuning.laneRatio
    const val LANE_MIN = 3f

    fun step(dt: Float, from: Float, to: Float, laneMax: Float) {
        x0 = x; lane0 = lane
        // which view: from the side within 60 degrees of the street, the back or the front beyond 70, unchanged between;
        // the way they face changes only with a push along the street
        facing = Locomotion.streetFacing(facing, moveX, moveLane)
        if (moveX != 0f) dir = if (moveX > 0f) 1f else -1f
        motion.drive(dt, moveX, moveLane)
        motion.tilt(dt, dir, facing == 0)
        running = motion.running
        val t = kotlin.math.min(dt, MoveTuning.dtMax)
        val nx = (x + vx * t).coerceIn(from + 20f, to - 20f)
        if (nx != x + vx * t) motion.stopX()            // against the street's end
        x = nx
        // across: lanes are deep ground (MoveTuning.laneRatio), so the same push crosses the pavement in about a second
        val nl = lane + motion.vy * MoveTuning.laneRatio * t
        lane = nl.coerceIn(LANE_MIN, laneMax)
        if (lane != nl) motion.stopY()                  // against the wall or the kerb
        edge = if (x <= from + 20.01f) -1 else if (x >= to - 20.01f) 1 else 0
    }

    /** After the stage has kept the player out of the furniture: the legs follow only what was really walked. */
    fun settle() {
        val moved = Locomotion.streetDistance(x - x0, lane - lane0)
        walking = moved > 1e-3f
        travel += moved
        val feet = motion.footfalls
        motion.advance(moved)
        footfalls += motion.footfalls - feet
    }

    /** [x] snapped to whole device pixels once the stage has drawn, for the camera and the player alike. */
    fun drawnX(): Float = if (unit > 0f) round(x * unit) / unit else x
}
