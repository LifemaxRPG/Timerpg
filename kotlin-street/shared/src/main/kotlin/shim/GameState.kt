package com.lifemax.rpg.feature.habits.presentation.scene

/**
 * The game's player on the street. The platform's controls set [moveX] and [moveLane] (-1..1); each frame the
 * stage calls [step], which walks the player along the street and in and out across the pavement, and the camera
 * follows [x]. When [enabled] is false the stage behaves as the habit tab did.
 */
object GameState {
    var enabled = false
    /** Where along the street (street units), how far out across the pavement, and which way they face (1 right, -1 left). */
    var x = 0f
    var lane = 6f
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
    /** Index of the building the player is in front of (-1 between buildings). */
    var nearest = -1
    /** True within reach of either end of the street, where the crossroads is. */
    var atCorner = false
    /** Where this street's crossroads is: at its far (east) end, or at its start. */
    var cornerAtEnd = true
    /** Half the screen's width in street units (set by the stage as it draws), so the camera can stop at the street's ends. */
    var halfView = 0f
    /** The stage's framing as it last drew (screen pixels): its ground line, its horizon, and screen pixels per sprite pixel. */
    var frameGround = 0f
    var frameHorizon = 0f
    var framePx = 0f
    /** How far the sky has panned (street units) while the corner turn swings the camera; 0 otherwise. */
    @Volatile var skyPan = 0f

    const val SPEED = 46f       // street units a second
    const val LANE_SPEED = 7f   // units a second in and out
    const val LANE_MIN = 3f

    fun step(dt: Float, from: Float, to: Float, laneMax: Float) {
        val dx = moveX * SPEED * dt
        val nx = (x + dx).coerceIn(from + 20f, to - 20f)
        walking = kotlin.math.abs(moveX) > .05f || kotlin.math.abs(moveLane) > .05f
        if (kotlin.math.abs(moveX) > .05f) { dir = if (moveX > 0) 1f else -1f; facing = 0 }
        else if (moveLane < -.05f) facing = 1
        else if (moveLane > .05f) facing = 3
        travel += kotlin.math.abs(nx - x) + kotlin.math.abs(moveLane) * LANE_SPEED * dt * .6f
        x = nx
        lane = (lane + moveLane * LANE_SPEED * dt).coerceIn(LANE_MIN, laneMax)
    }
}
