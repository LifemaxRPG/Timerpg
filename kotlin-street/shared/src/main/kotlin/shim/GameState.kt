package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

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
    var moveX = 0f
    var moveLane = 0f
    var walking = false
    /** Which of the sheet's four views the player shows: 0 right (flipped for left), 1 back (walking away), 3 front (towards us). */
    var facing = 0
    /** Distance walked, for the walk cycle. */
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
    /** The middle of each building along this street (street units), as the stage last laid it out. */
    @Volatile var centers: List<Float> = emptyList()
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
    /** Walking speed along the street (street units a second): it eases in and out, and becomes a run when held. */
    var vx = 0f
    private var heldFor = 0f
    private var heldDir = 0f
    private var x0 = 0f
    private var lane0 = 0f
    /** The stage's framing as it last drew (screen pixels): its ground line, its horizon, and screen pixels per sprite pixel. */
    var frameGround = 0f
    var frameHorizon = 0f
    var framePx = 0f
    /** How far the sky has panned (street units) while the corner turn swings the camera; 0 otherwise. */
    @Volatile var skyPan = 0f

    const val SPEED = 46f       // street units a second
    const val RUN = 1.9f        // held one way for a moment: a run (a lot and its gap are ~236 units)
    const val LANE_SPEED = 7f   // units a second in and out
    const val LANE_MIN = 3f

    fun step(dt: Float, from: Float, to: Float, laneMax: Float) {
        x0 = x; lane0 = lane
        if (abs(moveX) > .05f) { dir = if (moveX > 0) 1f else -1f; facing = 0 }
        else if (moveLane < -.05f) facing = 1
        else if (moveLane > .05f) facing = 3
        // held one way for a moment: break into a run; starting and stopping ease over ~60 ms
        val want = if (abs(moveX) > .05f) sign(moveX) else 0f
        heldFor = if (want != 0f && want == heldDir) heldFor + dt else 0f
        heldDir = want
        val target = moveX * SPEED * (if (heldFor > .6f) RUN else 1f)
        vx += (target - vx) * min(1f, dt * 16f)
        if (want == 0f && abs(vx) < .5f) vx = 0f
        val nx = (x + vx * dt).coerceIn(from + 20f, to - 20f)
        if (nx != x + vx * dt) vx = 0f            // against the street's end
        x = nx
        lane = (lane + moveLane * LANE_SPEED * dt).coerceIn(LANE_MIN, laneMax)
        edge = if (x <= from + 20.01f) -1 else if (x >= to - 20.01f) 1 else 0
    }

    /** After the stage has kept the player out of the furniture: the legs follow only what was really walked. */
    fun settle() {
        val moved = abs(x - x0) + abs(lane - lane0) * 6.5f   // stepping in and out at about the street's cadence
        walking = moved > 1e-3f
        travel += moved
    }

    /** [x] snapped to whole device pixels once the stage has drawn, for the camera and the player alike. */
    fun drawnX(): Float = if (unit > 0f) round(x * unit) / unit else x
}
