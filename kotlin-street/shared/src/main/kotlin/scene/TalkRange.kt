package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.ui.geometry.Offset
import kotlin.math.round

/**
 * Who the player can talk to by voice, and where people are on the screen (for the speech bubbles over their heads).
 * Street units throughout, as the stage lays the street out: the people to talk to stand at their building's door,
 * [KEEPER_DX] along from its middle, [KEEPER_LANE] out across the pavement (as [StreetLifePainter] puts them, and the
 * Filament street draws them from the same passers).
 */
object TalkRange {
    /**
     * How near (ground distance in street units, see [Locomotion.streetDistance]) the player must stand to someone for
     * their words to be for them: standing beside them at the door, up to ~70 along the street (a building's front is 184)
     * or ~37 along from the kerb (the pavement is deep ground: 9 lanes across are ~59 units).
     */
    var distance = 70f
    /** Walking further than this from someone in a conversation ends it (a little past [distance], so it does not flicker). */
    var leaveDistance = 90f

    /** Where a person to talk to stands from their building's middle, and how far out across the pavement. */
    const val KEEPER_DX = 30f
    const val KEEPER_LANE = 3.6f

    /** A person's head over their feet, in sprite pixels of our 32x64 character sheets (the top of the drawn figure). */
    const val HEAD_PIXELS = 50f

    /** Where the person to talk to at building [index] stands along the street ([centers]: the buildings' middles). */
    fun keeperX(centers: List<Float>, index: Int): Float = centers[index] + KEEPER_DX

    /** Ground distance between two spots on the pavement (along, across in lanes). */
    fun apart(x0: Float, lane0: Float, x1: Float, lane1: Float): Float = Locomotion.streetDistance(x1 - x0, lane1 - lane0)

    /**
     * Of the people standing at buildings [doors] (building indices along the street whose middles are [centers]), the one
     * nearest the player at ([x], [lane]) and within [range]: its place in [doors], or -1 if nobody is that near.
     */
    fun nearest(x: Float, lane: Float, doors: List<Int>, centers: List<Float>, range: Float = distance): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for ((k, b) in doors.withIndex()) {
            if (b !in centers.indices) continue
            val d = apart(x, lane, keeperX(centers, b), KEEPER_LANE)
            if (d <= range && d < bestD) { best = k; bestD = d }
        }
        return best
    }

    /** How far the player stands now from the person at building [index] of this street (Float.MAX_VALUE if unknown). */
    fun fromPlayer(index: Int): Float {
        val c = GameState.centers
        if (index !in c.indices) return Float.MAX_VALUE
        return apart(GameState.x, GameState.lane, keeperX(c, index), KEEPER_LANE)
    }

    /**
     * Where a spot on the street ([x] along, [lane] across) is on the screen (device pixels from the stage's top left) as
     * the stage last drew it, lifted [up] sprite pixels (a head's height). Null before the stage has drawn, and while the
     * camera turns the corner (people are then seen through the turning camera, not the street's own view).
     */
    fun screen(x: Float, lane: Float, up: Float = HEAD_PIXELS): Offset? {
        val u = GameState.unit
        if (u <= 0f || CornerWalk.active) return null
        // the stage draws the street from round(width / 2 - middle * unit), its pavement line at round(ground)
        val ox = round(GameState.halfView * u - GameState.viewMiddle * u)
        return Offset(ox + x * u, round(GameState.frameGround) + lane * u - up * GameState.framePx)
    }

    /** The head of the person standing at building [index] of the street the player is on. */
    fun keeperHead(index: Int): Offset? {
        val c = GameState.centers
        if (index !in c.indices) return null
        return screen(keeperX(c, index), KEEPER_LANE)
    }

    /** The player's head. */
    fun playerHead(): Offset? = screen(GameState.drawnX(), GameState.lane)

    /**
     * Where a spot in a room ([x], [y]: the room's own units, feet) is on a [width] x [height] screen, as [drawRoom] fits the
     * room, lifted [up] room units (a person there is drawn 64 units tall, head about 50 over the feet).
     */
    fun inRoom(room: InteriorRoom, x: Float, y: Float, width: Float, height: Float, up: Float = HEAD_PIXELS): Offset {
        val s = minOf(width / room.width, height / room.height)
        val ox = (width - room.width * s) / 2f; val oy = (height - room.height * s) / 2f
        return Offset(ox + x * s, oy + (y - up) * s)
    }
}
