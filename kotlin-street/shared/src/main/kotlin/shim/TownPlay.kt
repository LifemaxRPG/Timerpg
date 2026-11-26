package com.lifemax.rpg.feature.habits.presentation.scene

import com.lifemax.rpg.feature.habits.domain.Ambient
import com.lifemax.rpg.feature.habits.domain.Passer
import com.lifemax.rpg.feature.habits.domain.Placement
import com.lifemax.rpg.feature.habits.domain.PlacementKind
import com.lifemax.rpg.feature.habits.domain.Row
import com.lifemax.rpg.feature.habits.domain.StreetGait
import com.lifemax.rpg.feature.habits.domain.StreetLife
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
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
    /** Feet above the town's ground (the player's on a landmark's steps; everyone else 0). */
    var y = 0f
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
    /** The bicycle this figure rides (the player's, in the open world), or null on foot. */
    var bike: BikeState? = null
    /** Which of [TownFolk.sheets] they wear (-1: none, the old character sheets by [sheet]). */
    var folk = -1
    /** A passer's seed (the loop takes it for one of the town's people: LoopTown.passerId); -1 for others. */
    var who = -1
}

/**
 * The walkable town as the game plays it (docs/TOWN.md T10): the one [TownWalk] the keys drive, stepped by the stage's
 * game loop in place of the old crossroads' walk while [active]; the people on the rows the walk keeps live (street life
 * from [StreetLife], the people to talk to at their doors, the player) as [figures] for the renderer; who is near enough
 * to talk to; the HUD's street and neighbourhood; and the events a crash report wants ([onEvent]: the row changes, the
 * turns and crossings). Nothing here draws or touches a platform, so the JVM tests play it as the app does.
 */
object TownPlay {
    /**
     * The walk being played now, while the town is (null: the old crossroads, or the town still loading): [open] in the
     * open world ([OrbitCamera.open], the default), else [street]. A switch of view hands the player over at the next
     * [step], where they stand and the way they face.
     */
    @Volatile var walk: TownWalkView? = null
        private set
    val active: Boolean get() = walk != null
    /** The street view's walk (rows, lanes, the turns at corners): the "View: Street" option. */
    @Volatile var street: TownWalk? = null
        private set
    /** The open world's walk (anywhere, against the town's collision); null if the town has no collision map. */
    @Volatile var open: OpenWalk? = null
        private set
    /**
     * The town's cars (docs/VEHICLES.md): parked, got into and driven; stepped with the open world's walk. Set by the app
     * (and previews) once the town is started, where the physics library loads; null: no cars.
     */
    @Volatile var cars: com.lifemax.rpg.feature.habits.vehicle.VehicleWorld? = null

    /**
     * The planet's terrain round the town, once the renderer has made it (a worker): the cars' ground outside the town's
     * flat ground ([groundFor]). Null until then.
     */
    @Volatile var terrain: com.lifemax.rpg.feature.habits.domain.WorldTerrain? = null

    /**
     * The cars' ground function for a town placed on the planet by [site]: the terrain's height (m) at physics metres
     * (street units / [com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U]), null while the terrain is not made. The
     * terrain is measured in metres from its plan's origin, the town's frame's origin when the plan was made.
     */
    fun groundFor(site: com.lifemax.rpg.feature.habits.domain.WorldSite): (Float, Float) -> Float? {
        val u = site.frame.unitsPerMetre
        return { xm, zm ->
            val t = terrain
            if (t == null) null else {
                val o = t.plan.origin
                val dx = o.dx(site.frame.origin); val dz = o.dz(site.frame.origin)
                t.groundLocal(xm * com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U / u + dx, zm * com.lifemax.rpg.feature.habits.vehicle.VehicleWorld.U / u + dz).toFloat()
            }
        }
    }

    /** The player drives (or is getting in or out of) a car. */
    val inCar: Boolean get() = (walk as? OpenWalk)?.carried == true

    /** The open world is being played (the player's own camera, walking anywhere). */
    val openWorld: Boolean get() = walk is OpenWalk
    val town: Town? get() = walk?.town

    /** Rare events for crash reports' breadcrumbs (never per frame): row changes, turns, crossings. Set by the app. */
    @Volatile var onEvent: ((String) -> Unit)? = null

    /** The people to talk to, by the sprite of the building they stand at (its keeper's look seed). Set by the app. */
    @Volatile var keepers: Map<String, Int> = emptyMap()

    /** The people to talk to's sex and age, by the same sprite: age * 2, plus 1 for a woman ([keeperTraits]). Set by the app. */
    @Volatile var keeperTraits: Map<String, Int> = emptyMap()

    /** How many of the town's people the weather and season let out (1 a fine day; rain keeps most in). Set by the stage. */
    @Volatile var outdoors = 1f

    /** The share of each row's passers out ([TownCrowd.share]) last worked out, and for which hour and weather. */
    private val shareOf = HashMap<String, Float>()
    private var shareHour = Float.NaN
    private var shareOut = Float.NaN

    /** Where a world point (feet, [y] up) is on the screen as last drawn (set by the renderer each frame; null: not drawn). */
    @Volatile var projector: ((x: Float, y: Float, z: Float) -> androidx.compose.ui.geometry.Offset?)? = null

    /**
     * The people drawn a moment ago (passers and the people at their doors, not the player), as a list the game's
     * threads may read (the loop's witnesses: docs/LOOP.md); refreshed by [people] a few times a second.
     */
    @Volatile var crowd: List<com.lifemax.rpg.feature.habits.loop.LoopFigure> = emptyList()
        private set
    private var crowdAt = Float.NaN

    /**
     * More people to draw where they are, set by the game (the loop's police officers on their routes: x, z, dx, dz and
     * a look seed each, and a sixth, 1, for someone standing still: a fence); drawn while within [EXTRA_RANGE] of the player.
     */
    @Volatile var extras: List<FloatArray> = emptyList()
    private val EXTRA_RANGE = 3000f

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

    /**
     * Starts playing [town]: the player stands at its spawn (the civic avenue). With [collision] (the town's solid shapes,
     * built from the same [town]) and its [yards], the open world's walk is made too and played while [OrbitCamera.open];
     * returns the street view's walk (both stand at the spawn).
     */
    fun start(town: Town, collision: TownCollision? = null, yards: TownYards? = null): TownWalk {
        CornerWalk.reset()
        GameState.moveX = 0f; GameState.moveLane = 0f
        GameState.street = 0
        GameState.facing = 0; GameState.dir = 1f; GameState.vx = 0f
        val w = TownWalk(town)
        val o = collision?.let { OpenWalk(town, it, yards ?: TownYards(emptyList(), emptyList())) }
        o?.takeOver(w)
        lastRow = w.row.id
        turning = false
        lastMark = Float.NaN
        lastYard = null; talkLast = null
        centersOf.clear()
        street = w
        open = o
        walk = if (OrbitCamera.open && o != null) o else w
        event("town ${town.seed} ${town.year}: spawned on ${w.row.id} (${streetName() ?: "?"}) at x ${w.x.toInt()}, lane ${w.lane}; " +
            (if (walk === o) "open world" else "street view") + (if (o == null) " (no collision map)" else ""))
        return w
    }

    /** Stands the player at world ([x], [z]) facing ([dx], [dz]) in both walks (where they were last time; a preview's spot). */
    fun placeAt(x: Float, z: Float, dx: Float, dz: Float) {
        street?.placeAt(x, z, dx, dz)
        open?.placeAt(x, z, dx, dz)
        lastMark = Float.NaN
    }

    /**
     * Stands the player where they were last time ([placeAt]), but never somewhere they cannot walk away from: a saved
     * spot inside something or shut in (no way out over clear ground, as a save from before the town was replanned may
     * be) goes to the nearest street's pavement, else the spawn ([OpenWalk.rescue]); the solids built after this (the
     * landmarks, stores and farms) are checked again as they come up. Returns what the rescue did, or null if the spot was
     * fine.
     */
    fun restoreAt(x: Float, z: Float, dx: Float, dz: Float): String? {
        placeAt(x, z, dx, dz)
        val o = open ?: return null
        val what = o.rescue() ?: return null
        street?.placeAt(o.wx, o.wz, o.hx, o.hz)
        event("restore: $what")
        return what
    }

    /**
     * The walk the chosen view plays ([OrbitCamera.open]); on a switch the player is handed over to it where they stand,
     * facing the way they faced (into the street view: onto the nearest pavement, as its walk places them).
     */
    private fun sync(): TownWalkView? {
        val cur = walk ?: return null
        val o = open
        val want: TownWalkView = if (OrbitCamera.open && o != null) o else street ?: return cur
        if (want === cur) return cur
        if (want is OpenWalk) want.takeOver(cur) else { (cur as? OpenWalk)?.dismount(); (want as TownWalk).placeAt(cur.wx, cur.wz, cur.hx, cur.hz) }
        CornerWalk.reset()
        GameState.facing = 0
        walk = want
        lastMark = Float.NaN
        event((if (want is OpenWalk) "view open world" else "view street") + " at (${want.wx.toInt()}, ${want.wz.toInt()}) on ${want.row.id}; " + OrbitCamera.describe())
        return want
    }

    /** Stops playing the town (back to the old crossroads). */
    fun stop() {
        crowd = emptyList(); extras = emptyList()
        if (walk == null) return
        walk = null
        street = null; open = null
        cars?.close(); cars = null
        CornerWalk.reset()
        GameState.street = 0
        figureCount = 0
        renderBusy = false
        projector = null
        event("town stopped")
    }

    private fun event(what: String) { runCatching { onEvent?.invoke(what) } }

    // ------------------------------------------------------------------ the bicycle

    /** The player rides the bicycle (the open world only). */
    val riding: Boolean get() = (walk as? OpenWalk)?.riding == true

    /** The bicycle can be had here: the open world is played and the town is not still loading. */
    val canRide: Boolean get() = walk is OpenWalk && !frozen

    /**
     * The HUD's bike button: gets on (where there is room for the bike) or off. Returns whether the player now rides.
     * The breadcrumbs say which, and where.
     */
    fun toggleBike(): Boolean {
        val w = walk as? OpenWalk ?: return false
        if (w.carried) return false
        if (w.riding) { w.dismount(); event("bike off at (${w.wx.toInt()}, ${w.wz.toInt()})"); return false }
        if (frozen) return false
        val ok = w.mount()
        event(if (ok) "bike on (${w.bike.style.label}) at (${w.wx.toInt()}, ${w.wz.toInt()})" else "bike: no room at (${w.wx.toInt()}, ${w.wz.toInt()})")
        return ok
    }

    /** Off the bike, if on it (a door, a conversation, a room). */
    fun dismount(why: String) {
        val w = walk as? OpenWalk ?: return
        if (!w.riding) return
        w.dismount()
        event("bike off ($why)")
    }

    /**
     * One frame of the game loop (the keys are in [GameState.moveX], [GameState.moveLane]): walks, turns or crosses, and
     * keeps the HUD's state ([GameState.nearest]: the building in front, by its place in the row; [GameState.atCorner]: in
     * a mouth a turn leads off). Returns a number that changes whenever the view must be drawn again.
     */
    fun step(dt: Float): Float {
        val t0 = System.nanoTime()
        try { return stepTimed(dt) } finally { FrameWork.add(FrameWork.STEP, System.nanoTime() - t0) }
    }

    private fun stepTimed(dt: Float): Float {
        val v = sync() ?: return 0f
        if (frozen) { GameState.moveX = 0f; GameState.moveLane = 0f }
        if (v is OpenWalk) return stepOpen(v, dt)
        val w = v as TownWalk
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

    private var lastYard: String? = null

    /**
     * The open world's frame: the walk steps in the world against the town's collision, the stick relative to the camera
     * ([OpenWalk.cameraYaw], set by the renderer's orbit camera); no corners, turns or crossings. The HUD's state follows
     * the derived street frame: [GameState.nearest] is the building on the derived row nearest the player (the dialogue's
     * writing order goes by it), never at a corner. Breadcrumbs: the row and the yard (area) changes.
     */
    private fun stepOpen(w: OpenWalk, dt: Float): Float {
        val wasRescued = w.rescued
        val cs = cars
        cs?.step(dt, w, GameState.moveX, GameState.moveLane)
        w.step(dt)
        if (w.rescued !== wasRescued) { w.rescued?.let { event("rescue: $it") }; street?.placeAt(w.wx, w.wz, w.hx, w.hz) }
        if (w.row.id != lastRow) {
            lastRow = w.row.id
            rowChanges++
            event("row ${w.row.id}: ${streetName() ?: "?"}" + (hoodName()?.let { ", $it" } ?: "") + " at (${w.wx.toInt()}, ${w.wz.toInt()}); " + OrbitCamera.describe())
        }
        val y = w.yard?.sprite
        if (y != lastYard) {
            event(if (y != null) "area: yard of $y" + (hoodName()?.let { ", $it" } ?: "") else "area: ${lastYard?.let { "out of the yard of $it" } ?: "?"}, on ${streetName()}")
            lastYard = y
        }
        GameState.nearest = nearestOnRow(w.row, w.x)
        GameState.atCorner = false
        val mark = w.wx * 3f + w.wz * 7f + w.hx * 13f + w.hz * 17f + rowChanges * 31f + (if (GameState.walking) GameState.gait * 5f else 0f) +
            (if (w.riding) w.bike.lean * 1000f + w.bike.crank * 50f else 0f) + (cs?.driven?.pose?.let { it.y * 11f + it.spin[0] * 100f } ?: 0f)
        GameState.inMotion = mark != lastMark || GameState.walking || renderBusy || (w.riding && (w.bike.speed > 0f || abs(w.bike.lean) > 1e-3f)) || cs?.moving == true
        lastMark = mark
        return mark
    }

    /** The building of [r] whose front holds [x] along it, else the one whose middle is nearest; -1 for a row without any. */
    private fun nearestOnRow(r: Row, x: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for ((i, b) in r.buildings.withIndex()) {
            val d = abs(b.mid - x)
            if (d < bestD) { best = i; bestD = d }
        }
        return best
    }

    // ------------------------------------------------------------------ the HUD

    /** The street the player is on (the row's road), or null when not playing the town. */
    fun streetName(): String? { val w = walk ?: return null; return (w as? OpenWalk)?.streetName() ?: w.town.road(w.row.road).name }

    /** The neighbourhood here (the yard's, the building in front's, else the nearest lot's). */
    fun hoodName(): String? { val w = walk ?: return null; return if (w is OpenWalk) w.hoodName() else w.town.neighbourhoodAt(w.row, w.x)?.name }

    /** The building in front of the player (its sprite), none mid-transit; in the open world the door they stand at, facing it. */
    fun frontSprite(): String? { val w = walk ?: return null; if (w.transit != null) return null; return w.front?.sprite }

    private var talkLast: String? = null

    /**
     * Whose people the "Talk to" buttons offer: in the street view the building in front; in the open world the person to
     * talk to nearest the player within [TalkRange.distance] from any direction (kept until the player is past
     * [TalkRange.leaveDistance], so it does not flicker between two), else the door the player stands at.
     */
    fun talkSprite(): String? {
        val w = walk ?: return null
        if (w !is OpenWalk) return frontSprite()
        talkLast?.let { if (it in keepers && w.fromKeeper(it) <= TalkRange.leaveDistance) {
            val n = w.nearestKeeper(keepers, TalkRange.distance)
            // (someone else clearly nearer takes over)
            if (n == null || n == it || w.fromKeeper(n) + 20f > w.fromKeeper(it)) return it
        } }
        talkLast = w.nearestKeeper(keepers, TalkRange.distance)
        // (else the door the player stands at, unless its person stands further off than a conversation lasts)
        return talkLast ?: w.door?.sprite?.takeIf { it !in keepers || w.fromKeeper(it) <= TalkRange.leaveDistance }
    }

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
        if (w is OpenWalk) return w.nearestKeeper(keepers, range)
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
        if (w is OpenWalk) return w.fromKeeper(sprite)
        if (w.transit != null) return Float.MAX_VALUE
        val b = w.row.buildings.firstOrNull { it.sprite == sprite } ?: return Float.MAX_VALUE
        return TalkRange.apart(w.x, w.lane, b.mid + TalkRange.KEEPER_DX, TalkRange.KEEPER_LANE)
    }

    // ------------------------------------------------------------------ who hears the player (Earshot)

    /**
     * Everyone who could hear the player now: the people at their doors and the passers walking by, as drawn a moment ago
     * ([crowd]: not the officers or a fence, who are the loop's own figures); positions in street units.
     */
    fun hearers(): List<Hearer> = crowd.map { Hearer(it.keeper, it.who, it.x, it.z) }

    /**
     * Who hears what the player says now ([Earshot.choose]: within [range], the one they face and are nearest, [current]
     * kept while still in earshot), in the open world; on the street view's pavement the nearest person at a door, as before.
     * Null: nobody in earshot, or mid-transit.
     */
    fun listener(current: String? = null, range: Float = TalkRange.distance, can: (Hearer) -> Boolean = { true }): Hearer? {
        val w = walk ?: return null
        if (w.transit != null) return null
        if (w !is OpenWalk) return nearestKeeper(range)?.let { sp -> Hearer(sp, -1, w.wx, w.wz) }?.takeIf(can)
        // ([can]: only those the game can answer for, e.g. a passer is a townsperson only while the loop runs)
        return Earshot.choose(w.wx, w.wz, w.hx, w.hz, hearers().filter(can), range, current)
    }

    /** Where the passer [who] was drawn last (x, z), null when not drawn now (gone round a corner, off the live rows). */
    fun passerAt(who: Int): Pair<Float, Float>? {
        if (who < 0) return null
        // (the render thread refills [figures] each frame; read without a lock, a torn read is one stale frame)
        val n = figureCount
        for (i in 0 until n) { val f = runCatching { figures[i] }.getOrNull() ?: return null; if (f.who == who && f.keeper == null) return f.x to f.z }
        return crowd.firstOrNull { it.who == who && it.keeper == null }?.let { it.x to it.z }
    }

    /** How far the player is from the passer [who] (Float.MAX_VALUE when they are not drawn now). */
    fun fromPasser(who: Int): Float {
        val w = walk ?: return Float.MAX_VALUE
        val (x, z) = passerAt(who) ?: return Float.MAX_VALUE
        return hypot(x - w.wx, z - w.wz)
    }

    /** The passer [who]'s head on the screen (their bubble follows them as they walk on). */
    fun passerHead(who: Int): androidx.compose.ui.geometry.Offset? = passerAt(who)?.let { (x, z) -> headOnScreen(x, z) }

    /** A head [TalkRange.HEAD_PIXELS] sprite pixels over the feet at world ([x], [z]), on the screen as last drawn. */
    fun headOnScreen(x: Float, z: Float, feet: Float = 0f): androidx.compose.ui.geometry.Offset? =
        projector?.invoke(x, feet + TalkRange.HEAD_PIXELS * SpriteArt.PIXEL, z)

    /** The head of the person to talk to at [sprite], on the screen. */
    fun keeperHead(sprite: String): androidx.compose.ui.geometry.Offset? {
        val t = town ?: return null
        val (r, b) = t.placement(sprite) ?: return null
        val p = keeperSpot(r, b)
        return headOnScreen(p.x, p.z)
    }

    /** The player's head, on the screen. */
    fun playerHead(): androidx.compose.ui.geometry.Offset? { val w = walk ?: return null; return headOnScreen(w.wx, w.wz, (w as? OpenWalk)?.shownY ?: 0f) }

    // ------------------------------------------------------------------ the people drawn

    private val passers = Array(StreetLife.MOST + 2) { Passer() }

    private fun next(): TownFigure {
        if (figureCount == figures.size) figures += TownFigure()
        return figures[figureCount++].also { it.keeper = null; it.me = false; it.y = 0f; it.facing = 0; it.bike = null; it.folk = -1; it.who = -1 }
    }

    /**
     * Fills [figures] for [seconds] of the street's clock: on each row [TownWalk.liveRows] keeps live, its passers (the
     * street's own [StreetLife], seeded by the row) and the people to talk to at their doors; then the player. Returns
     * how many.
     */
    fun people(seconds: Float, seed: Int = 0, hour: Float = 13f): Int {
        figureCount = 0
        val w = walk ?: return 0
        val year = w.town.year
        val kidsOut = TownFolk.kidsOut(hour)
        // (the shares move slowly: worked out again every few game minutes or when the weather changes)
        if (shareHour.isNaN() || abs(hour - shareHour) > .05f || abs(outdoors - shareOut) > .02f) { shareOf.clear(); shareHour = hour; shareOut = outdoors }
        for (r in w.liveRows()) {
            val rowSeed = seed * 31 + r.id.hashCode()
            val centers = centersOf.getOrPut(r.id) { r.buildings.map { it.mid }.ifEmpty { null } }
            // the open town follows its people's day: few on a street of houses but at the commute, more where the shops are
            val share = shareOf.getOrPut(r.id) {
                var shops = 0; var homes = 0; var others = 0
                for (b in r.buildings) when (b.kind) { PlacementKind.SHOP -> shops++; PlacementKind.HOME -> homes++; else -> others++ }
                TownCrowd.share(hour, shops, homes, others, outdoors)
            }
            val n = StreetLife.passers(seconds, rowSeed, 0f, r.length, passers, centers)
            for (i in 0 until n) {
                val p = passers[i]
                if (!p.on) continue
                // (who is out is fixed by when they set out: nobody pops in or out mid-walk while the hour holds)
                if (Ambient.hash(rowSeed, p.slot, 233) >= share) continue
                // (the walk's margins run past the row's ends: those beyond are on the next row, or in the side street)
                if (p.x < -20f || p.x > r.length + 20f) continue
                val q = r.pointAt(p.x, p.lane)
                val a = r.alongAt(p.x.coerceIn(0f, r.length))
                val f = next()
                f.x = q.x; f.z = q.z
                f.dx = a.x * p.dir; f.dz = a.z * p.dir
                f.sheet = (p.slot * 7 + 3) and 0x7fffffff
                val who = (rowSeed * 131 + p.slot) and 0x7fffffff
                val (female, age) = TownFolk.passer(who, com.lifemax.rpg.feature.habits.domain.StreetLooks.ALL[p.look].elder)
                // (children are at school, or home after dark)
                if (age < 15 && !kidsOut) { figureCount--; continue }
                f.folk = TownFolk.pick(who, female, age, year)
                f.still = p.still > .5f
                f.frame = if (f.still) ((seconds * 5f).toInt() + p.slot * 3).mod(6) else (p.frame % StreetGait.FRAMES) * 6 / StreetGait.FRAMES
                f.who = who
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
                val t = keeperTraits[b.sprite]
                f.folk = if (t == null) TownFolk.pick(look, Ambient.hash(look, 17, 977) < .5f, -1, year) else TownFolk.pick(look, t and 1 == 1, t shr 1, year)
                f.still = true
                f.frame = ((seconds * 5f).toInt() + k * 3).mod(6)
                f.keeper = b.sprite
            }
        }
        for (e in extras) {
            if (e.size < 5 || abs(e[0] - w.wx) > EXTRA_RANGE || abs(e[1] - w.wz) > EXTRA_RANGE) continue
            val f = next()
            f.x = e[0]; f.z = e[1]; f.dx = e[2]; f.dz = e[3]
            f.sheet = e[4].toInt() and 0x7fffffff
            f.folk = TownFolk.pick(f.sheet, false, 35, year)
            f.still = e.size > 5 && e[5] > 0f
            f.frame = ((seconds * 7f).toInt()).mod(6)
            f.who = -1
        }
        // (the crowd the loop's witnesses come from, a few times a second)
        if (crowdAt.isNaN() || abs(seconds - crowdAt) > .3f) {
            crowdAt = seconds
            crowd = (0 until figureCount).map { figures[it] }.filter { it.who >= 0 || it.keeper != null }
                .map { com.lifemax.rpg.feature.habits.loop.LoopFigure(it.x, it.z, it.dx, it.dz, it.still, it.who, it.keeper) }
        }
        // (in a car the player sits inside it, unseen: the car is drawn by its own layer)
        if (w is OpenWalk && w.carried) return figureCount
        val me = next()
        me.me = true
        me.x = w.wx; me.z = w.wz; me.y = (w as? OpenWalk)?.shownY ?: 0f
        val moving = GameState.walking || w.transit != null
        val a = w.row.alongAt(w.x.coerceIn(0f, w.row.length))
        // on the pavement the player faces along the row by their direction; through a transit, the way they walk; in the
        // open world the way they face (the camera then shows their front, back or side, as everyone's)
        if (w.transit != null || w is OpenWalk) { me.dx = w.hx; me.dz = w.hz } else { me.dx = a.x * GameState.dir; me.dz = a.z * GameState.dir }
        me.sheet = -1
        me.bike = if (w is OpenWalk && w.riding) w.bike else null
        me.still = !moving
        me.facing = if (w.transit != null || w is OpenWalk) 0 else GameState.facing
        me.frame = if (me.still) ((seconds * 5f).toInt()).mod(6) else ((GameState.gait * StreetGait.FRAMES).toInt().coerceIn(0, StreetGait.FRAMES - 1)) * 6 / StreetGait.FRAMES
        return figureCount
    }

    /**
     * The sheet's view a figure shows a camera looking along ([fx], [fz]) on the ground, its right ([rx], [rz]): 0 the
     * right-facing side (flipped: walking left), 1 the back (walking away), 3 the front (coming towards it). Returns the
     * view and the flip (1 or -1).
     */
    fun view(f: TownFigure, fx: Float, fz: Float, rx: Float, rz: Float): Pair<Int, Float> {
        val c = viewCode(f, fx, fz, rx, rz)
        return (c shr 1) to (if (c and 1 == 1) -1f else 1f)
    }

    /** [view] as one number (the view times 2, plus 1 when flipped): nothing allocated, a person a frame. */
    fun viewCode(f: TownFigure, fx: Float, fz: Float, rx: Float, rz: Float): Int {
        if (f.me && f.facing != 0) return f.facing * 2
        val l = hypot(f.dx, f.dz)
        if (l < 1e-5f) return 3 * 2
        val across = (f.dx * rx + f.dz * rz) / l
        val away = (f.dx * fx + f.dz * fz) / l
        // (standing people face a little more towards the camera: someone at their door faces the street)
        return if (abs(away) > abs(across) * (if (f.still) .8f else 1.2f)) (if (away > 0f) 1 else 3) * 2
        else if (across >= 0f) 0 else 1
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
