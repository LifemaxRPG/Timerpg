package com.lifemax.rpg.feature.habits.presentation.scene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lifemax.rpg.feature.habits.domain.Crossroads
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Walking round the corner. At the crossroads a press of the up key (in the main street's mouth) walks the player into
 * the cross street; at the cross street's start a press of the up key, or walking on against its end, walks them back. The
 * walk is the player's own: a curve from where they stand to where the other street's view will have them, walked at
 * the street's pace (a run when held), and the camera turns with how far along it they are, so letting go mid-way
 * (with [TurnTuning.autoWalk] off) pauses the turn and the back key walks it back. The stage calls [update] every frame
 * instead of stepping the player while it runs, and swaps the street in the frame the walk ends.
 */
object CornerWalk {
    /** The app's gate: something must keep the player where they are (inside, a door, a conversation, listening, tuning). */
    @Volatile var blocked = false

    /** True while the player walks round the corner: snapshot state, so the app can hold the AI's writing ahead. */
    var active by mutableStateOf(false)
        private set

    /** From the main street into the cross street (false: back out of it). */
    var forward = true
        private set

    /** How far along the walk (0 where it began, 1 round the corner), and the camera with it. */
    var p = 0f
        private set

    /** The camera's turn now (radians, 0 the main street's view, PI/2 the cross street's) and how far it has gone (0..1). */
    var yaw = 0f
        private set
    var ease = 0f
        private set

    /**
     * The sky's turn (radians, as [yaw]) and how far it has gone: the same swing, settled a little before the walk's ends.
     * Near either end the street keeps most of its own flat look and hardly turns while the camera still does, so a sky
     * turning to the last frame went on sliding behind buildings that had already stopped.
     */
    val skyEase: Float get() = smootherstep((p - SKY_EARLY) / (1f - 2f * SKY_EARLY))
    val skyYaw: Float get() = (if (forward) skyEase else 1f - skyEase) * (PI.toFloat() / 2f)

    /** Where the player is on the ground (world), and which way they walk. */
    var wx = 0f
        private set
    var wz = 0f
        private set
    var hx = 0f
        private set
    var hz = 1f
        private set

    /** Speed along the walk (street units a second, negative walking back). */
    var v = 0f
        private set

    /** The camera's targets at either end (world X, Z), set by the stage as the walk starts: where each street's own view looks. */
    var fromX = 0f
    var fromZ = 0f
    var toX = 0f
    var toZ = 0f
    /** The two streets' views (their middles, street x) at either end, for the sky's drift. */
    var fromMiddle = 0f
    var toMiddle = 0f

    /** The up key as the last frame saw it, and a press the app saw between frames (a tap shorter than one). */
    private var upBefore = false
    @Volatile var upTapped = false
    private var runFor = 0f
    private var needRelease = false
    private val bx = FloatArray(4)
    private val bz = FloatArray(4)
    private val arc = FloatArray(SEGMENTS + 1)
    private var length = 1f
    private var view = 1

    /** For the seam test only: the walk stays at its end (p = 1) without handing over, so that frame can be seen alone. */
    @Volatile var holdEnd = false

    /** Which street's people the player is drawn among: the one they walk from, and round the corner the one they reach. */
    fun playerStreet(): Int = if (!active) GameState.street else if (p >= 1f) (if (forward) 1 else 0) else (if (forward) 0 else 1)

    /** Forgets any walk (a new street was loaded). */
    fun reset() {
        active = false; p = 0f; yaw = 0f; ease = 0f; needRelease = false; v = 0f; upBefore = false; upTapped = false
    }

    /**
     * Watches the keys while the player walks [street] (0 the main street) of [cr]: true once the walk has started (the
     * stage reads the camera's two ends from [forward] and sets [fromX].. before the next [walk]). It starts in the frame
     * the up key goes down, before the player has stepped anywhere, so the shortest tap turns; a key already held (the
     * player walking into the mouth with it down, pressed against the fronts) does not.
     */
    fun arm(cr: Crossroads, street: Int): Boolean {
        if (active) return false
        val up = GameState.moveLane < -.5f
        val left = GameState.moveX < -.5f
        val pressed = (up && !upBefore) || upTapped
        upBefore = up
        upTapped = false
        if (needRelease) {
            if (!up && !left) needRelease = false
            return false
        }
        val want = !blocked && when (street) {
            0 -> pressed && cr.inMouth(GameState.x)
            else -> (pressed && cr.atCrossStart(GameState.x)) || (left && GameState.edge == -1)
        }
        if (!want) return false
        start(cr, street)
        return true
    }

    private fun start(cr: Crossroads, street: Int) {
        forward = street == 0
        val x0: Float
        val z0: Float
        if (forward) { x0 = cr.mainX(GameState.x); z0 = cr.mainZ(GameState.lane) }
        else { x0 = cr.crossX(GameState.lane); z0 = cr.crossZ(GameState.x) }
        val x3: Float
        val z3: Float
        val h: Float
        if (forward) { x3 = cr.crossX(ARRIVE_LANE); z3 = cr.crossZ(cr.arriveX); h = 1f }
        else { x3 = cr.mainX(cr.returnX); z3 = cr.mainZ(ARRIVE_LANE); h = -1f }
        // a cubic leaving straight on (north into the cross street, south out of it) and arriving the same way
        val c = HANDLE * hypot(x3 - x0, z3 - z0)
        bx[0] = x0; bz[0] = z0
        bx[1] = x0; bz[1] = z0 + h * c
        bx[2] = x3; bz[2] = z3 - h * c
        bx[3] = x3; bz[3] = z3
        arc[0] = 0f
        var lx = x0
        var lz = z0
        for (i in 1..SEGMENTS) {
            val t = i.toFloat() / SEGMENTS
            val nx = bez(bx, t)
            val nz = bez(bz, t)
            arc[i] = arc[i - 1] + hypot(nx - lx, nz - lz)
            lx = nx; lz = nz
        }
        length = arc[SEGMENTS].coerceAtLeast(1f)
        p = 0f
        // walking on at the pace the player had
        v = abs(GameState.vx).coerceAtLeast(0f)
        runFor = 0f
        view = GameState.facing
        place()
        active = true
    }

    /**
     * One frame of the walk: the keys move the player along it, the camera turns with it, and the player's place, view
     * and stride are set on [GameState] in [street]'s units (the street they started on). Returns 1 if the walk just
     * reached the other street, -1 if it was walked back to where it began, 0 while it goes on.
     */
    fun walk(dt: Float, cr: Crossroads, rx: Float, rz: Float, dxv: Float, dzv: Float): Int {
        if (!active) return 0
        // held at its end ([holdEnd]) and now let go: handed over with the player as the walk's last step drew them, as in
        // the game, where the hand-over is in the step that reaches the end
        if (p >= 1f && !holdEnd) { finish(cr, true); return 1 }
        // the keys: on (up, or left walking back out), back (down, or right), else on by itself
        val on = if (forward) GameState.moveLane < -.5f else (GameState.moveLane < -.5f || GameState.moveX < -.5f)
        val back = if (forward) GameState.moveLane > .5f else (GameState.moveX > .5f || GameState.moveLane > .5f)
        runFor = if (on) runFor + dt else 0f
        val want = when {
            back -> -GameState.SPEED
            on || TurnTuning.autoWalk -> GameState.SPEED * (if (runFor > .6f) GameState.RUN else 1f)
            else -> 0f
        }
        v += (want - v) * min(1f, dt * 16f)
        if (want == 0f && abs(v) < .5f) v = 0f
        // (the stride follows what was really walked: nothing past the end)
        val was = p
        p = (p + v * dt / length).coerceIn(0f, 1f)
        val step = (p - was) * length
        GameState.travel += abs(step)
        GameState.walking = abs(step) > 1e-3f
        GameState.vx = v
        place()
        // the player's place in the street they started on (round the corner, in the one they arrived in)
        if (forward == (p < 1f)) { GameState.x = wx + cr.westEdge; GameState.lane = -wz / Crossroads.LANE }
        else { GameState.x = wz - cr.zOff; GameState.lane = wx / Crossroads.LANE }
        // which of the sheet's views shows: the walk's direction against the camera's (a margin either side of 45 degrees)
        val sx = (if (v < 0f) -hx else hx)
        val sz = (if (v < 0f) -hz else hz)
        val right = sx * rx + sz * rz
        val away = sx * dxv + sz * dzv
        val sideNow = view == 0
        val side = if (sideNow) abs(right) > abs(away) - VIEW_MARGIN else abs(right) > abs(away) + VIEW_MARGIN
        if (side) { view = 0; GameState.facing = 0; GameState.dir = if (right >= 0f) 1f else -1f }
        else { view = if (away > 0f) 1 else 3; GameState.facing = view }
        return when {
            p >= 1f && holdEnd -> 0
            p >= 1f -> { finish(cr, true); 1 }
            p <= 0f && v < 0f -> { finish(cr, false); -1 }
            else -> 0
        }
    }

    private fun place() {
        val t = tAt(p * length)
        wx = bez(bx, t); wz = bez(bz, t)
        val tx = tan(bx, t); val tz = tan(bz, t)
        val l = sqrt(tx * tx + tz * tz).coerceAtLeast(1e-4f)
        hx = tx / l; hz = tz / l
        // the camera turns with the walk, easing out of one street's view and into the other's (strictly between its ends
        // until the walk is: in floats the ease reaches 1 a few frames early, and the stage would hand the picture over
        // to the new street's own drawing while the player still walks among the old street's people)
        val s = if (p <= 0f) 0f else if (p >= 1f) 1f else smootherstep(p).coerceIn(1e-7f, ALMOST_ONE)
        ease = s
        yaw = (if (forward) s else 1f - s) * (PI.toFloat() / 2f)
    }

    private fun finish(cr: Crossroads, arrived: Boolean) {
        active = false
        needRelease = true
        if (arrived) {
            GameState.street = if (forward) 1 else 0
            GameState.x = if (forward) cr.arriveX else cr.returnX
            GameState.lane = ARRIVE_LANE
            if (forward) {
                GameState.dir = 1f; GameState.facing = 0
                // still held: the walk goes on along the new street until let go; else a few steps on by itself, clear of
                // the screen's edge, at the pace it had
                if (GameState.moveLane < -.05f) GameState.upWalks = true else GameState.walkOn = WALK_ON
                GameState.vx = v
            } else {
                GameState.facing = 3; GameState.vx = 0f
            }
        } else {
            GameState.vx = 0f
            GameState.facing = if (forward) 3 else 0
        }
        v = 0f
    }

    /** How far the camera's cabinet look is left for the main street's rows and the cross street's at turn [yawNow]. */
    fun leanMain(yawNow: Float): Float = 1f - smoothstep(0f, TurnTuning.LEAN_BAND, Math.toDegrees(yawNow.toDouble()).toFloat())
    fun leanCross(yawNow: Float): Float = smoothstep(90f - TurnTuning.LEAN_BAND, 90f, Math.toDegrees(yawNow.toDouble()).toFloat())

    /** The walk's length (street units) and whether it is smooth at [t] (for tests): its heading at t. */
    val pathLength: Float get() = length
    fun pointAt(t: Float): FloatArray = floatArrayOf(bez(bx, t), bez(bz, t), tan(bx, t), tan(bz, t))

    private fun tAt(s: Float): Float {
        var i = 1
        while (i < SEGMENTS && arc[i] < s) i++
        val a = arc[i - 1]
        val b = arc[i]
        val f = if (b > a) ((s - a) / (b - a)).coerceIn(0f, 1f) else 0f
        return (i - 1 + f) / SEGMENTS
    }

    private fun bez(c: FloatArray, t: Float): Float {
        val u = 1f - t
        return u * u * u * c[0] + 3f * u * u * t * c[1] + 3f * u * t * t * c[2] + t * t * t * c[3]
    }

    private fun tan(c: FloatArray, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * (c[1] - c[0]) + 6f * u * t * (c[2] - c[1]) + 3f * t * t * (c[3] - c[2])
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun smootherstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return (t * t * t * (t * (t * 6f - 15f) + 10f)).coerceIn(0f, 1f)
    }

    /** How far the player walks on along the cross street by themselves once round the corner (street units). */
    const val WALK_ON = 60f
    /** The lane the walk arrives in, either way: the front band of the pavement, where the player walks. */
    const val ARRIVE_LANE = 7f
    private const val HANDLE = .45f
    private const val SKY_EARLY = .05f
    /** The largest float below 1. */
    private const val ALMOST_ONE = .99999994f
    private const val SEGMENTS = 32
    private const val VIEW_MARGIN = .12f
}

