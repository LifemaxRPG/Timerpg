package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownPoint
import com.lifemax.rpg.feature.habits.domain.TransitionKind
import kotlin.math.abs
import kotlin.math.hypot

/**
 * One person the town's renderer draws this frame: feet at world ([x], [z]), walking along ([dx], [dz]) (unit; the
 * way they face when standing), which character sheet ([sheet]: -1 the player's own, else a seed the renderer picks
 * another sheet by), the frame of the sheet's walk or idle row, and whether they stand. [keeper]: the sprite (bN) of the
 * building whose person to talk to this is, standing at its door facing the street.
 */
class TownFigure {
    var x = 0f
    var z = 0f
    var dx = 1f
    var dz = 0f
    var sheet = 0
    var frame = 0
    var still = false
    var me = false
    var keeper: String? = null
    /** The player's own view of the sheet when it is not the side view (1 back, 3 front: walking up or down the pavement). */
    var facing = 0
}

/**
 * The walkable town as the game plays it (docs/TOWN.md T10): the one [TownWalk] the keys drive, stepped by the stage's
 * game loop in place of the old crossroads' walk while [active]; the people on the rows the walk keeps live (street life
 * from [StreetLife], the people to talk to at their doors, the player) as [figures] for the renderer; who is near enough
 * to talk to; the HUD's street and neighbourhood; and the events a crash report wants ([onEvent]: the row changes, the
 * turns and crossings). Nothing here draws or touches a platform, so the JVM tests play it as the app does.
 */
object TownPlay {
    /** The walk, while the town is being played (null: the old crossroads, or the town still loading). */
    @Volatile var walk: TownWalk? = null
        private set
    val active: Boolean get() = walk != null
    val town: Town? get() = walk?.town

    /** Rare events for crash reports' breadcrumbs (never per frame): row changes, turns, crossings. Set by the app. */
    @Volatile var onEvent: ((String) -> Unit)? = null

    /** The people to talk to, by the sprite of the building they stand at (its keeper's look seed). Set by the app. */
    @Volatile var keepers: Map<String, Int> = emptyMap()

    /** Where a world point (feet, [y] up) is on the screen as last drawn (set by the renderer each frame; null: not drawn). */
    @Volatile var projector: ((x: Float, y: Float, z: Float) -> androidx.compose.ui.geometry.Offset?)? = null

    /** This frame's people ([figureCount] of [figures]), filled by [people]. */
    val figures = ArrayList<TownFigure>()
    var figureCount = 0
        private set

    /** Counts the rows walked onto, so the HUD and the dialogue's order can follow it. */
    var rowChanges = 0
        private set

    private var lastRow: String? = null
    private var turning = false
    private var lastMark = Float.NaN

    /**
     * The renderer still has building to do (the ground, buildings near the player): frames should keep coming at the
     * display's rate while it lasts, even with the player standing still. Set by the renderer each frame.
     */
    @Volatile var renderBusy = false

    /**
     * The player cannot move (the town's first view is still being built behind the loading screen): the keys, the game
     * pad and the stick push nothing, so the view is built round the spot the player will start from. Set by the app.
     */
    @Volatile var frozen = false

    /** Each row's buildings' middles (where its passers stop to look), kept: the people are stood every frame. */
    private val centersOf = HashMap<String, List<Float>?>()

    /** Starts playing [town]: the player stands at its spawn (the civic avenue). */
    fun start(town: Town): TownWalk {
        CornerWalk.reset()
        GameState.moveX = 0f; GameState.moveLane = 0f
        GameState.street = 0
        GameState.facing = 0; GameState.dir = 1f; GameState.vx = 0f
        val w = TownWalk(town)
        lastRow = w.row.id
        turning = false
        lastMark = Float.NaN
        centersOf.clear()
        walk = w
        event("town ${town.seed} ${town.year}: spawned on ${w.row.id} (${streetName() ?: "?"}) at x ${w.x.toInt()}, lane ${w.lane}")
        return w
    }

    /** Stops playing the town (back to the old crossroads). */
    fun stop() {
        if (walk == null) return
        walk = null
        CornerWalk.reset()
        GameState.street = 0
        figureCount = 0
        renderBusy = false
        projector = null
        event("town stopped")
    }

    private fun event(what: String) { runCatching { onEvent?.invoke(what) } }

    /**
     * One frame of the game loop (the keys are in [GameState.moveX], [GameState.moveLane]): walks, turns or crosses, and
     * keeps the HUD's state ([GameState.nearest]: the building in front, by its place in the row; [GameState.atCorner]: in
     * a mouth a turn leads off). Returns a number that changes whenever the view must be drawn again.
     */
    fun step(dt: Float): Float {
        val w = walk ?: return 0f
        if (frozen) { GameState.moveX = 0f; GameState.moveLane = 0f }
        val wasTransit = w.transit
        val done = w.step(dt)
        val t = w.transit
        if (wasTransit == null && t != null) {
            turning = t.kind == TransitionKind.TURN_IN
            event((if (turning) "turn start" else "cross start") + ": ${t.from.id} -> ${t.to.id} at node ${t.node}")
        }
        if (done != null) {
            val what = when (done.kind) { TransitionKind.TURN_IN -> "turn"; TransitionKind.CROSS -> "cross"; else -> "straight" }
            if (done.kind != TransitionKind.STRAIGHT) event("$what end: ${done.from} -> ${done.to}" + if (done.arrived) "" else " (walked back)")
        } else if (wasTransit != null && t == null) event((if (turning) "turn" else "cross") + " end")
        if (w.row.id != lastRow) {
            lastRow = w.row.id
            rowChanges++
            event("row ${w.row.id}: ${streetName() ?: "?"}" + (hoodName()?.let { ", $it" } ?: ""))
        }
        val f = w.front
        GameState.nearest = if (f == null || t != null) -1 else w.row.buildings.indexOf(f)
        GameState.atCorner = t == null && (
            (w.row.end.turnIn != null && w.x >= w.row.length - com.lifemax.rpg.feature.habits.domain.Crossroads.MOUTH_ZONE && w.x <= w.row.length) ||
                (w.row.start.turnIn != null && w.x >= 0f && w.x <= com.lifemax.rpg.feature.habits.domain.Crossroads.MOUTH_ZONE))
        val mark = w.wx * 3f + w.wz * 7f + GameState.facing * 13f + GameState.dir * 17f + (t?.progress ?: 0f) * 1000f + rowChanges * 31f +
            (if (GameState.walking) GameState.gait * 5f else 0f)
        // every frame only while something moves (the player, a transit) or the renderer is still building; standing
        // still, the stage's own clock (about 30 Hz) redraws the street's people
        GameState.inMotion = mark != lastMark || t != null || GameState.walking || renderBusy
        lastMark = mark
        return mark
    }

    // ------------------------------------------------------------------ the HUD

    /** The street the player is on (the row's road), or null when not playing the town. */
    fun streetName(): String? { val w = walk ?: return null; return w.town.road(w.row.road).name }

    /** The neighbourhood here (the building in front's, else the nearest lot's). */
    fun hoodName(): String? { val w = walk ?: return null; return w.town.neighbourhoodAt(w.row, w.x)?.name }

    /** The building in front of the player (its sprite), none mid-transit. */
    fun frontSprite(): String? { val w = walk ?: return null; if (w.transit != null) return null; return w.front?.sprite }

    /** Index of [row] in the town's rows (the dialogue's queue orders by it); -1 if unknown. */
    fun rowIndex(row: Row?): Int = row?.let { r -> town?.rows?.indexOfFirst { it.id == r.id } } ?: -1

    // ------------------------------------------------------------------ the people to talk to

    /** Where the person to talk to at [b] on [r] stands: [TalkRange.KEEPER_DX] along from its middle, [TalkRange.KEEPER_LANE] out. */
    fun keeperSpot(r: Row, b: Placement): TownPoint = r.pointAt(b.mid + TalkRange.KEEPER_DX, TalkRange.KEEPER_LANE)

    /**
     * The person to talk to (by building sprite) nearest the player on the row they walk and within [range] (ground
     * distance, as [TalkRange.apart]); null if nobody is that near, or mid-transit.
     */
    fun nearestKeeper(range: Float = TalkRange.distance): String? {
        val w = walk ?: return null
        if (w.transit != null) return null
        var best: String? = null
        var bestD = Float.MAX_VALUE
        for (b in w.row.buildings) {
            if (b.sprite !in keepers) continue
            val d = TalkRange.apart(w.x, w.lane, b.mid + TalkRange.KEEPER_DX, TalkRange.KEEPER_LANE)
            if (d <= range && d < bestD) { best = b.sprite; bestD = d }
        }
        return best
    }

    /** How far the player stands from the person at [sprite] (Float.MAX_VALUE off this row, or mid-transit). */
    fun fromPlayer(sprite: String): Float {
        val w = walk ?: return Float.MAX_VALUE
        if (w.transit != null) return Float.MAX_VALUE
        val b = w.row.buildings.firstOrNull { it.sprite == sprite } ?: return Float.MAX_VALUE
        return TalkRange.apart(w.x, w.lane, b.mid + TalkRange.KEEPER_DX, TalkRange.KEEPER_LANE)
    }

    /** A head [TalkRange.HEAD_PIXELS] sprite pixels over the feet at world ([x], [z]), on the screen as last drawn. */
    fun headOnScreen(x: Float, z: Float): androidx.compose.ui.geometry.Offset? =
        projector?.invoke(x, TalkRange.HEAD_PIXELS * SpriteArt.PIXEL, z)

    /** The head of the person to talk to at [sprite], on the screen. */
    fun keeperHead(sprite: String): androidx.compose.ui.geometry.Offset? {
        val t = town ?: return null
        val (r, b) = t.placement(sprite) ?: return null
        val p = keeperSpot(r, b)
        return headOnScreen(p.x, p.z)
    }

    /** The player's head, on the screen. */
    fun playerHead(): androidx.compose.ui.geometry.Offset? { val w = walk ?: return null; return headOnScreen(w.wx, w.wz) }

    // ------------------------------------------------------------------ the people drawn

    private val passers = Array(StreetLife.MOST + 2) { Passer() }

    private fun next(): TownFigure {
        if (figureCount == figures.size) figures += TownFigure()
        return figures[figureCount++].also { it.keeper = null; it.me = false; it.facing = 0 }
    }

    /**
     * Fills [figures] for [seconds] of the street's clock: on each row [TownWalk.liveRows] keeps live, its passers (the
     * street's own [StreetLife], seeded by the row) and the people to talk to at their doors; then the player. Returns
     * how many.
     */
    fun people(seconds: Float, seed: Int = 0): Int {
        figureCount = 0
        val w = walk ?: return 0
        for (r in w.liveRows()) {
            val rowSeed = seed * 31 + r.id.hashCode()
            val centers = centersOf.getOrPut(r.id) { r.buildings.map { it.mid }.ifEmpty { null } }
            val n = StreetLife.passers(seconds, rowSeed, 0f, r.length, passers, centers)
            for (i in 0 until n) {
                val p = passers[i]
                if (!p.on) continue
                // (the walk's margins run past the row's ends: those beyond are on the next row, or in the side street)
                if (p.x < -20f || p.x > r.length + 20f) continue
                val q = r.pointAt(p.x, p.lane)
                val a = r.alongAt(p.x.coerceIn(0f, r.length))
                val f = next()
                f.x = q.x; f.z = q.z
                f.dx = a.x * p.dir; f.dz = a.z * p.dir
                f.sheet = (p.slot * 7 + 3) and 0x7fffffff
                f.still = p.still > .5f
                f.frame = if (f.still) ((seconds * 5f).toInt() + p.slot * 3).mod(6) else (p.frame % StreetGait.FRAMES) * 6 / StreetGait.FRAMES
            }
            for ((k, b) in r.buildings.withIndex()) {
                val look = keepers[b.sprite] ?: continue
                val q = keeperSpot(r, b)
                val n2 = r.normalAt(b.mid)
                val f = next()
                f.x = q.x; f.z = q.z
                // facing the street, out of the door
                f.dx = -n2.x; f.dz = -n2.z
                f.sheet = look
                f.still = true
                f.frame = ((seconds * 5f).toInt() + k * 3).mod(6)
                f.keeper = b.sprite
            }
        }
        val me = next()
        me.me = true
        me.x = w.wx; me.z = w.wz
        val moving = GameState.walking || w.transit != null
        val a = w.row.alongAt(w.x.coerceIn(0f, w.row.length))
        // on the pavement the player faces along the row by their direction; through a transit, the way they walk
        if (w.transit != null) { me.dx = w.hx; me.dz = w.hz } else { me.dx = a.x * GameState.dir; me.dz = a.z * GameState.dir }
        me.sheet = -1
        me.still = !moving
        me.facing = if (w.transit != null) 0 else GameState.facing
        me.frame = if (me.still) ((seconds * 5f).toInt()).mod(6) else ((GameState.gait * StreetGait.FRAMES).toInt().coerceIn(0, StreetGait.FRAMES - 1)) * 6 / StreetGait.FRAMES
        return figureCount
    }

    /**
     * The sheet's view a figure shows a camera looking along ([fx], [fz]) on the ground, its right ([rx], [rz]): 0 the
     * right-facing side (flipped: walking left), 1 the back (walking away), 3 the front (coming towards it). Returns the
     * view and the flip (1 or -1).
     */
    fun view(f: TownFigure, fx: Float, fz: Float, rx: Float, rz: Float): Pair<Int, Float> {
        if (f.me && f.facing != 0) return f.facing to 1f
        val l = hypot(f.dx, f.dz)
        if (l < 1e-5f) return 3 to 1f
        val across = (f.dx * rx + f.dz * rz) / l
        val away = (f.dx * fx + f.dz * fz) / l
        // (standing people face a little more towards the camera: someone at their door faces the street)
        return if (abs(away) > abs(across) * (if (f.still) .8f else 1.2f)) (if (away > 0f) 1 else 3) to 1f
        else 0 to (if (across >= 0f) 1f else -1f)
    }

    /** Which of [n] character sheets figure [f] wears: the player's own ([GameState.look]), the others never theirs. */
    fun sheetOf(f: TownFigure, n: Int): Int {
        if (n <= 0) return 0
        val mine = GameState.look.mod(n)
        if (f.sheet < 0 || n == 1) return mine
        val k = (Ambient.hash(f.sheet, 7, 211) * (n - 1)).toInt().coerceIn(0, n - 2)
        return if (k >= mine) k + 1 else k
    }
}
