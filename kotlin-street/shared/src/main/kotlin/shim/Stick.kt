package com.lifemax.rpg.feature.habits.presentation.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * The on-screen stick's maths, kept free of Compose so it can be tested on its own. The stick floats: where the thumb
 * lands is its centre (so every start is at rest, wherever the grip puts the thumb), and how far and which way the thumb
 * has moved from there is the push. Distances are in whatever unit the caller uses (the composable gives pixels).
 *
 * Its output is [x] (along the street: -1 left .. 1 right) and [y] (across it: -1 up, away from us, into the buildings
 * .. 1 down, towards the kerb), the same -1..1 the game's [GameState.moveX] and [GameState.moveLane] take.
 */
class Stick(
    /** How far the knob can go from the centre: a push this far is a full push. */
    var travel: Float = 1f,
    /** How far past the rim the thumb may go before the centre follows it (so turning round never needs a long drag back). */
    var follow: Float = 0f,
) {
    /** The pointer the stick follows, or [NONE]. */
    var pointer = NONE
        private set
    /** Where the stick's centre is now. */
    var baseX = 0f
        private set
    var baseY = 0f
        private set
    /** The knob's offset from the centre, never more than [travel] long. */
    var knobX = 0f
        private set
    var knobY = 0f
        private set
    /** The push: along the street and across it (-1..1 each, at most 1 long together). */
    var x = 0f
        private set
    var y = 0f
        private set
    /** How hard the stick is pushed: 0 in the dead zone .. 1 at the rim. */
    var amount = 0f
        private set
    /** Held at nothing until the thumb comes back near the middle (or lifts): see [hold]. */
    var holding = false
        private set

    val active get() = pointer != NONE

    /**
     * The screen (the stick's units) and how close to its edge a thumb can usefully push: a stick that starts nearer an
     * edge than [travel] reaches a full push towards it in the room left (never less than [MIN_REACH] of the travel), so a
     * thumb landing low still walks and runs down.
     */
    var width = Float.POSITIVE_INFINITY
    var height = Float.POSITIVE_INFINITY
    var edge = 0f

    private val out = FloatArray(2)
    /** How far out the thumb is (a share of the way to a full push, the edges' reach counted), as of its last move. */
    private var out0 = 0f

    /** How far a push towards an edge [room] away goes before it is a full push. */
    private fun reach(room: Float) = min(travel, max(travel * MIN_REACH, room - edge))

    /**
     * How far a push from a stick centred at ([cx], [cy]) goes before it is a full push, towards each edge in turn (left,
     * right, up, down) into [into]: the travel, or less near the screen's edges. The drawn ring of the run follows it.
     */
    fun reaches(cx: Float, cy: Float, into: FloatArray) {
        into[0] = reach(cx); into[1] = reach(width - cx); into[2] = reach(cy); into[3] = reach(height - cy)
    }

    /** A thumb [id] lands at ([px], [py]): the stick starts there, at rest. */
    fun down(id: Long, px: Float, py: Float) {
        pointer = id
        baseX = px; baseY = py
        knobX = 0f; knobY = 0f
        holding = false
        out0 = 0f
        x = 0f; y = 0f; amount = 0f
    }

    /** The thumb has moved to ([px], [py]). */
    fun move(px: Float, py: Float) {
        if (!active) return
        var dx = px - baseX
        var dy = py - baseY
        val d = hypot(dx, dy)
        val far = travel + follow
        if (d > far) {
            // dragged well past the rim: the centre comes along behind the thumb, so the way back is never long
            val k = (d - far) / d
            baseX += dx * k; baseY += dy * k
            dx -= dx * k; dy -= dy * k
        }
        val r = hypot(dx, dy)
        if (r > travel) { knobX = dx * travel / r; knobY = dy * travel / r } else { knobX = dx; knobY = dy }
        val ux = dx / reach(if (dx > 0f) width - baseX else baseX)
        val uy = dy / reach(if (dy > 0f) height - baseY else baseY)
        shape(ux, uy, out)
        out0 = hypot(ux, uy)
        // held: it counts again once the thumb is back near the middle (a slow walk's push), not only in the dead zone,
        // so a thumb that only relaxes does not have to find the exact spot it landed on
        if (holding && out0 <= REARM) holding = false
        if (holding) { x = 0f; y = 0f; amount = 0f } else { x = out[0]; y = out[1]; amount = min(1f, hypot(x, y)) }
    }

    /** The thumb has lifted, or the stick was let go of (the window lost focus, the screen went away). */
    fun up() {
        pointer = NONE
        holding = false
        out0 = 0f
        knobX = 0f; knobY = 0f
        x = 0f; y = 0f; amount = 0f
    }

    /**
     * The push counts for nothing until the thumb comes back within [REARM] of the middle or lifts: after a door, the corner
     * turn or when a conversation opens, so a thumb still held does not walk the player straight back out or away. A thumb
     * already that near the middle is not held (it is where holding would let go, and its next push out counts).
     */
    fun hold() {
        if (!active || out0 <= REARM) return
        holding = true
        x = 0f; y = 0f; amount = 0f
    }

    companion object {
        const val NONE = -1L
        /** The dead zone: the first 12% of the knob's travel does nothing (a resting thumb never creeps). */
        const val DEAD = .12f
        /** Pushes this close to straight along the street (degrees) are taken as straight along it ... */
        const val SNAP_ALONG = 15f
        /** ... and this close to straight in or out as straight in or out (so walking up to a door never drifts sideways). */
        const val SNAP_ACROSS = 10f
        /** A held push counts again once the thumb is back within this share of the travel (a push of about a quarter). */
        const val REARM = .35f
        /** Near a screen edge, a full push towards it needs at least this share of the travel. */
        const val MIN_REACH = .5f

        /** How far from the centre (a share of the travel) a push of [amount] (0..1, after the dead zone) is. */
        fun radiusFor(amount: Float) = DEAD + amount * (1f - DEAD)

        /**
         * A thumb [dx], [dy] from the centre (in units of the knob's travel) as the push the game gets, into [out]
         * (along, across). Nothing in the dead zone; beyond it, the amount rises from 0 at its edge to 1 at the rim (so there
         * is no jump). The angle is drawn to the nearest axis near it, the rest of each quarter stretched to fill (so the
         * mapping stays continuous as the thumb sweeps round).
         */
        fun shape(dx: Float, dy: Float, out: FloatArray) {
            val r = min(1f, hypot(dx, dy))
            if (r <= DEAD) { out[0] = 0f; out[1] = 0f; return }
            val m = (r - DEAD) / (1f - DEAD)
            val a = atan2(abs(dy), abs(dx)) * (180f / PI.toFloat())
            val top = 90f - SNAP_ACROSS
            when {
                a <= SNAP_ALONG -> { out[0] = m * sign(dx); out[1] = 0f }
                a >= top -> { out[0] = 0f; out[1] = m * sign(dy) }
                else -> {
                    val b = (a - SNAP_ALONG) / (top - SNAP_ALONG) * (PI.toFloat() / 2f)
                    out[0] = m * cos(b) * sign(dx)
                    out[1] = m * sin(b) * sign(dy)
                }
            }
        }
    }
}

/**
 * Where the player's push comes from: the on-screen [stick] (and a keyboard or game pad, when one is used), gated by what
 * the screen is doing. The game reads [GameState.moveX] and [GameState.moveLane]; [publish] writes them, on every touch
 * and every frame, so code that wants to walk the player itself (a scripted walk round a corner) sets [scripted] rather
 * than writing those two directly.
 */
object MoveInput {
    val stick = Stick()
    /** A hardware stick or keys (-1..1 each), already shaped like the on-screen stick's push; 0 when none is used. Set by [pad]. */
    var padX = 0f
        private set
    var padY = 0f
        private set
    /** The pad's push counts for nothing until its keys are let go or its stick comes back near the middle (see [holdUntilNeutral]). */
    var padHolding = false
        private set
    /** Nothing moves the player (the corner turn, a door, a panel over the street): the stick still follows a held thumb. */
    var closed = false
    /** A push from the game itself (x along, y across, as [GameState.moveX] and [GameState.moveLane]), in place of the player's while set. */
    var scripted: FloatArray? = null

    /** The keyboard's or game pad's push now (0, 0 when nothing is pressed or the stick is centred), and writes it into the game. */
    fun pad(x: Float, y: Float) {
        padX = x; padY = y
        if (padHolding && hypot(x, y) <= PAD_REARM) padHolding = false
        publish()
    }

    /** Writes the push into the game: called on every touch and again every frame (so a held stick picks up after a pause). */
    fun publish() {
        val s = scripted
        if (s != null) { GameState.moveX = s[0]; GameState.moveLane = s[1]; return }
        if (closed) { GameState.moveX = 0f; GameState.moveLane = 0f; return }
        // whichever is pushed further: the screen's stick or the pad
        val px = if (padHolding) 0f else padX
        val py = if (padHolding) 0f else padY
        if (hypot(px, py) > stick.amount) { GameState.moveX = px; GameState.moveLane = py }
        else { GameState.moveX = stick.x; GameState.moveLane = stick.y }
    }

    /**
     * A push held now counts for nothing until the thumb comes back near the middle or lifts, and a held key or a pushed
     * game pad stick likewise until it is let go or comes back near the middle (a key's repeat or a pad's next report
     * would otherwise bring the push straight back).
     */
    fun holdUntilNeutral() {
        stick.hold()
        if (hypot(padX, padY) > PAD_REARM) padHolding = true
        publish()
    }

    /** Lets go of everything (the window lost focus, the game paused, a game pad went away): nothing is held down after it. */
    fun releaseAll() {
        stick.up()
        padX = 0f; padY = 0f
        padHolding = false
        publish()
    }

    /** A held pad counts again from this push down: about what [Stick.REARM] is on the screen's stick. */
    const val PAD_REARM = .26f
}
