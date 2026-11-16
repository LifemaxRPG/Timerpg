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
    /** Distance walked, for the walk cycle. */
    var travel = 0f

    const val SPEED = 46f       // street units a second
    const val LANE_SPEED = 7f   // units a second in and out
    const val LANE_MIN = 3f

    fun step(dt: Float, from: Float, to: Float, laneMax: Float) {
        val dx = moveX * SPEED * dt
        val nx = (x + dx).coerceIn(from + 20f, to - 20f)
        walking = kotlin.math.abs(moveX) > .05f || kotlin.math.abs(moveLane) > .05f
        if (kotlin.math.abs(moveX) > .05f) dir = if (moveX > 0) 1f else -1f
        travel += kotlin.math.abs(nx - x) + kotlin.math.abs(moveLane) * LANE_SPEED * dt * .6f
        x = nx
        lane = (lane + moveLane * LANE_SPEED * dt).coerceIn(LANE_MIN, laneMax)
    }
}
