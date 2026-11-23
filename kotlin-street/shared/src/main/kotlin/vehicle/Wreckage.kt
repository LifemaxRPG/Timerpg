package com.lifemax.rpg.feature.habits.vehicle

import com.lifemax.rpg.feature.habits.domain.FarmSolids
import com.lifemax.rpg.feature.habits.domain.PropKind
import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYardItem
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.physics.Breaks
import com.lifemax.rpg.feature.habits.physics.Physics
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a town shape gives under a car (docs/VEHICLES.md, "Destruction"). */
enum class Give {
    /** Stops a car at any speed (buildings, brick and stone walls, big trees, the river's banks, the world's edge). */
    SOLID,
    /** Breaks into loose pieces above its impulse (wooden fences into their panels, bins, mailboxes, benches...). */
    BREAK,
    /** Snaps at its foot and falls over above its impulse, pushes back below it (lamp posts, signs, saplings). */
    BEND,
    /** Lets a car through, slowed (bushes: they spring back). */
    SOFT,
}

/**
 * What a shape is made of: how it gives, the impulse (N s: the car's mass times its speed into it) it gives at, a loose
 * piece's mass (kg; a fence's per metre), how tall it stands (m), the share of the car's speed into it that breaking it
 * costs on top of the pieces' momentum ([snap]), a bush's drag (1/s), a fence's panel (m), and a name for the log.
 */
class WreckMaterial(
    val give: Give, val impulse: Float = 0f, val mass: Float = 0f, val height: Float = 1f, val snap: Float = 0f,
    val drag: Float = 0f, val panel: Float = 0f, val name: String = "",
) {
    companion object {
        val SOLID = WreckMaterial(Give.SOLID)

        /**
         * The material of a town shape by its kind and [TownCollision.detail] (a yard item's "kind/variant/zone", "lamp",
         * a [PropKind]'s name, the farms' fences' [FarmSolids.DETAIL]...). Unknown things are solid: only what the renderer
         * can take away breaks (a store's fences and the cinema's rails stay solid until their sites can be drawn broken).
         */
        fun of(kind: SolidKind, detail: String?): WreckMaterial {
            val d = detail ?: return SOLID
            val k = d.substringBefore('/')
            val v = d.substringAfter('/', "0").substringBefore('/').toIntOrNull() ?: 0
            val zone = d.substringAfterLast('/', "")
            return when (kind) {
                SolidKind.FENCE -> when (k) {
                    // (the impulses: wooden fences give from 2,700-3,000 N s: the 976 kg Torrel goes through a picket or a
                    // board fence from 2.9-3.1 m/s (6.5-7 mph), the 1,685 kg sedan from 1.7-1.8 m/s; the heaviest car,
                    // 1,780 kg, nudging at 1.4 m/s is still stopped)
                    "frontFence" -> WreckMaterial(Give.BREAK, 2800f, 11f, .85f, .05f, panel = 40f / U, name = "picket fence")
                    "sideFence" -> WreckMaterial(Give.BREAK, 3000f, 16f, 1.17f, .06f, panel = 48f / U, name = "board fence")
                    "backFence" -> when (v % 3) {
                        0 -> WreckMaterial(Give.BREAK, 3000f, 15f, 1.3f, .06f, panel = 48f / U, name = "lap fence")
                        1 -> WreckMaterial(Give.BREAK, 3000f, 17f, 1.36f, .06f, panel = 48f / U, name = "board fence")
                        // (a chain-link fence's posts bend and its wire tears: heavier going)
                        else -> WreckMaterial(Give.BREAK, 3800f, 9f, 1.18f, .1f, panel = 50f / U, name = "chain-link fence")
                    }
                    "postRail" -> WreckMaterial(Give.BREAK, 2700f, 9f, .8f, .04f, panel = 40f / U, name = "post-and-rail fence")
                    "gate", "sideGate" -> WreckMaterial(Give.BREAK, 2700f, 22f, 1.1f, .05f, name = "garden gate")
                    // a farm field's post-and-rail fence (posts every 92 units, two rails: it breaks between posts and halfway)
                    FarmSolids.DETAIL -> WreckMaterial(Give.BREAK, 2700f, 8f, 1.32f, .04f, panel = 46f / U, name = "farm fence")
                    else -> SOLID
                }
                // a hedge gives way with a gap torn through it, slowing the car more than boards
                SolidKind.HEDGE -> if (k == "hedge") WreckMaterial(Give.BREAK, 2600f, 9f, if (zone == "front") .9f else 1.4f, .18f, panel = 24f / U, name = "hedge") else SOLID
                SolidKind.YARD_OBJECT -> when (k) {
                    "bush" -> WreckMaterial(Give.SOFT, drag = 1.6f, name = "bush")
                    "mailbox" -> WreckMaterial(Give.BREAK, 900f, 9f, 1.1f, .01f, name = "mailbox")
                    "bins" -> WreckMaterial(Give.BREAK, 900f, 14f, 1.05f, .01f, name = "bins")
                    "chair" -> WreckMaterial(Give.BREAK, 500f, 5f, .9f, 0f, name = "garden chair")
                    "table" -> WreckMaterial(Give.BREAK, 1400f, 22f, .75f, .01f, name = "garden table")
                    "bench" -> WreckMaterial(Give.BREAK, 2400f, 35f, .9f, .02f, name = "garden bench")
                    "grill" -> WreckMaterial(Give.BREAK, 1600f, 25f, 1f, .01f, name = "grill")
                    "birdbath" -> WreckMaterial(Give.BREAK, 2600f, 35f, .9f, .02f, name = "birdbath")
                    "planter" -> WreckMaterial(Give.BREAK, 2400f, 40f, .6f, .02f, name = "planter")
                    // a raised vegetable bed: its boards burst and the car ploughs through the soil, a stretch at a time
                    "vegBed" -> WreckMaterial(Give.BREAK, 2200f, 30f, .35f, .12f, panel = 26f / U, name = "raised bed")
                    "doghouse" -> WreckMaterial(Give.BREAK, 2200f, 30f, .9f, .03f, name = "doghouse")
                    "pallets" -> WreckMaterial(Give.BREAK, 1800f, 25f, .5f, .02f, name = "pallets")
                    "compost" -> WreckMaterial(Give.BREAK, 2000f, 25f, .9f, .03f, name = "compost bin")
                    "hammock" -> WreckMaterial(Give.BREAK, 1200f, 15f, 1f, .01f, name = "hammock")
                    "trampoline" -> WreckMaterial(Give.BREAK, 2400f, 45f, .9f, .03f, name = "trampoline")
                    "washingLine" -> WreckMaterial(Give.BEND, 1400f, 12f, 1.9f, .01f, name = "washing line post")
                    else -> SOLID
                }
                SolidKind.TREE -> if (k == "fruitTree") WreckMaterial(Give.BEND, 7000f, 120f, 2.6f, .08f, name = "fruit tree") else SOLID
                SolidKind.PROP -> when (k) {
                    "bush" -> WreckMaterial(Give.SOFT, drag = 1.6f, name = "bush")
                    "lamp" -> WreckMaterial(Give.BEND, 6000f, 90f, LAMP_H, .05f, name = "street lamp")
                    "streetBench", "plazaBench" -> WreckMaterial(Give.BREAK, 3400f, 60f, .85f, .03f, name = "bench")
                    else -> PropKind.entries.firstOrNull { it.name == k }?.let(::ofProp) ?: SOLID
                }
                else -> SOLID
            }
        }

        private fun ofProp(p: PropKind): WreckMaterial {
            val h = p.h / U
            return when (p) {
                PropKind.HYDRANT -> WreckMaterial(Give.BEND, 14000f, 60f, h, .08f, name = "fire hydrant")
                PropKind.MAILBOX -> WreckMaterial(Give.BREAK, 5000f, 95f, h, .03f, name = "mail collection box")
                PropKind.HOME_MAILBOX -> WreckMaterial(Give.BREAK, 900f, 9f, h, .01f, name = "mailbox")
                PropKind.WHEELIE_BIN -> WreckMaterial(Give.BREAK, 800f, 12f, h, .01f, name = "wheelie bin")
                PropKind.LITTER_BIN -> WreckMaterial(Give.BREAK, 1500f, 20f, h, .01f, name = "litter bin")
                PropKind.NEWS_BOX -> WreckMaterial(Give.BREAK, 2000f, 30f, h, .01f, name = "newspaper box")
                PropKind.PLANTER -> WreckMaterial(Give.BREAK, 4500f, 90f, h, .04f, name = "planter")
                PropKind.BIKE_RACK -> WreckMaterial(Give.BEND, 4000f, 35f, h, .03f, name = "bike rack")
                PropKind.STREET_SIGN, PropKind.STOP_SIGN, PropKind.PED_SIGN -> WreckMaterial(Give.BEND, 2600f, 20f, h, .02f, name = "sign post")
                PropKind.CALL_BOX -> WreckMaterial(Give.BEND, 6000f, 60f, h, .04f, name = "fire alarm post")
                PropKind.UTILITY_BOX -> WreckMaterial(Give.BREAK, 7000f, 120f, h, .06f, name = "utility cabinet")
                PropKind.CONE -> WreckMaterial(Give.BREAK, 150f, 3f, h, 0f, name = "traffic cone")
                PropKind.BENCH -> WreckMaterial(Give.BREAK, 3400f, 60f, h, .03f, name = "bench")
                PropKind.PARKING_METER -> WreckMaterial(Give.BEND, 3500f, 25f, h, .02f, name = "parking meter")
                PropKind.TREE_GUARD -> WreckMaterial(Give.BEND, 5000f, 50f, h, .03f, name = "young tree")
                // (bollards are there to stop cars)
                PropKind.BOLLARD -> SOLID
            }
        }

        private const val U = VehicleWorld.U
        /** A street lamp's post (m): TownGround's lamp. */
        const val LAMP_H = 2.75f
    }
}

/**
 * What a car broke (for the crime system: property damage): what it was ([name], its [kind], [detail] and [owner]: the
 * yard's building or the row), where (street units), when ([atS], the cars' clock in seconds), by which car (its key) at
 * what speed (m/s).
 */
class DamageEvent(val name: String, val kind: SolidKind, val detail: String?, val owner: String?, val x: Float, val z: Float, val atS: Float,
                  val car: String?, val speed: Float) {
    override fun toString() = "damage: a %s (%s) at (%.0f, %.0f) by %s at %.1f m/s, t %.1f s".format(java.util.Locale.US, name, owner ?: "-", x, z, car ?: "?", speed, atS)
}

/**
 * The cars' destruction (docs/VEHICLES.md, "Destruction"), run by [VehicleWorld]: the streamed town shapes get their give
 * ([WreckMaterial]: breakable, bendable, soft or solid); when Jolt reports a car went through a breakable one ([onBreaks]),
 * the static is taken out and loose dynamic pieces ([Piece]) put in its place with real masses (a fence's panels and
 * posts, a lamp post, a bin), given the car's momentum, which the car loses, so it carries on slowed; the walk's map
 * loses the shape too (and gets what is left of a fence back as shorter pieces), and the renderer is told what to take
 * away ([yardEdits], [hidden]) and what to draw moving ([pieces]). Debris settles and sleeps; past [LIVE] pieces in the
 * physics the oldest are frozen where they lie (drawn, no longer simulated), past [KEPT] the oldest go. Broken stays
 * broken for the session. Every break is a [DamageEvent] in [events] and a line through [VehicleWorld.onEvent].
 */
class Wreckage internal constructor(private val vw: VehicleWorld) {
    private val physics: Physics get() = vw.physics
    private val col: TownCollision? get() = vw.collision

    /**
     * A loose piece: a box of half extents [hx], [hy], [hz] (m) of [mass] kg, drawn as [look]. Its pose for drawing
     * ([drawn]: x, y, z street units, qx, qy, qz, qw) is written by the game loop and read under its lock.
     */
    class Piece internal constructor(val id: Int, val look: Look, val hx: Float, val hy: Float, val hz: Float, val mass: Float, val bornS: Float) {
        internal var handle = -1
        internal var slot = -1
        /** Frozen: out of the physics, lying where it was. */
        var frozen = false; internal set
        internal var settledKey: String? = null
        internal var asleepS = 0f
        val drawn = FloatArray(7)
        /** Copies its pose ([drawn]) into [o] under its lock. */
        fun read(o: FloatArray) = synchronized(this) { System.arraycopy(drawn, 0, o, 0, 7) }
    }

    /**
     * What a piece looks like: [source] "yard" (a yard item of [kind]/[variant]/[zone] facing ([faceX], [faceZ]) in rect
     * [rect], street units relative to the piece's spawn point), "prop" (the street's [kind]: a [PropKind] name, "lamp",
     * "streetBench", "plazaBench", found by the renderer at [srcX], [srcZ]); its mesh as it stood, relative to the ground
     * under its spawn point ([spawnX], [spawnZ]); [q0] the body's rotation then, [hy] its half height (street units): the
     * renderer draws it at pose * q0⁻¹ * (0, -hy, 0).
     */
    class Look(
        val source: String, val kind: String, val variant: Int, val zone: String, val faceX: Float, val faceZ: Float,
        val rect: FloatArray?, val srcX: Float, val srcZ: Float, val spawnX: Float, val spawnZ: Float, val q0: FloatArray, val hy: Float,
        /** The layer the yard item was in (an edge, an object). */
        val layer: String = "object",
    ) {
        /** The same look put down ([dx], [dz]) street units further on. */
        fun movedBy(dx: Float, dz: Float) = Look(source, kind, variant, zone, faceX, faceZ, rect, srcX, srcZ, spawnX + dx, spawnZ + dz, q0, hy, layer)

        /** A key for meshes that look the same (one built for all the panels of a fence). */
        val key: String = if (source == "yard") "$kind/$variant/$zone/${rect!![2] - rect[0]}/${rect[3] - rect[1]}/$faceX/$faceZ" else "$source/$kind/$srcX/$srcZ"
    }

    /** The pieces (the renderer iterates them on its own thread). */
    val pieces = java.util.concurrent.CopyOnWriteArrayList<Piece>()
    /** Changes when [pieces] gains or loses one. */
    @Volatile var piecesVersion = 0; private set

    /** The yards' items as they stand now by owner (the yard's building or the lane), for those changed; [yardVersion] counts the changes. */
    val yardEdits = java.util.concurrent.ConcurrentHashMap<String, List<TownYardItem>>()
    @Volatile var yardVersion = 0; private set

    /** Street things taken away (lamps, benches, the props by PropRules): where (street units) and what (the shape's detail). */
    class Hidden(val x: Float, val z: Float, val detail: String)
    val hidden = java.util.concurrent.CopyOnWriteArrayList<Hidden>()

    /**
     * Stretches of fences drawn by others than the yards (the farms' fields) broken out: x0, z0, x1, z1 (street units, along
     * the fence's line), for their renderer to leave out; [cutsVersion] counts them.
     */
    val cuts = java.util.concurrent.CopyOnWriteArrayList<FloatArray>()
    @Volatile var cutsVersion = 0; private set

    /** Everything broken this session. */
    val events = java.util.concurrent.CopyOnWriteArrayList<DamageEvent>()

    /** At most this many pieces live in the physics (at most [LIVE]; a test lowers it). */
    internal var liveCap = LIVE
    /** Live pieces in the physics now (not frozen), and whether any is awake. */
    val live: Int get() = liveCount
    val awake: Boolean get() { for (i in 0 until slotCount) if (now[i * Physics.POSE + 7] > .5f) return true; return false }
    private var liveCount = 0
    private var nextId = 0
    private var nextRemnant = 0
    /** Remnants of broken fences in the walk's map by their shape's key ([VehicleWorld.keyOf]): their extra's key. */
    private val remnants = HashMap<Long, String>()

    // the live pieces' physics handles and poses, by slot (one JNI call a step reads them all)
    private val slots = arrayOfNulls<Piece>(LIVE)
    private val handles = IntArray(LIVE)
    private var slotCount = 0
    private val now = FloatArray(LIVE * Physics.POSE)
    private val before = FloatArray(LIVE * Physics.POSE)
    private val breaks = FloatArray(Breaks.MAX * Breaks.COUNT)
    private val dd = FloatArray(18)
    private val tmp = FloatArray(6)

    /** Gives a just-streamed static its give (the town's shape [kind]/[detail]). */
    internal fun give(handle: Int, kind: SolidKind, detail: String?) {
        val m = WreckMaterial.of(kind, detail)
        when (m.give) {
            Give.SOLID -> {}
            Give.SOFT -> physics.setGive(handle, 0f, m.drag)
            Give.BREAK, Give.BEND -> physics.setGive(handle, m.impulse, 0f)
        }
    }

    /** After a physics step that reported breaks: each handled (or the shape made solid again if it cannot be). */
    internal fun onBreaks(simT: Float) {
        val n = physics.takeBreaks(breaks)
        for (k in 0 until n) {
            val o = k * Breaks.COUNT
            val h = breaks[o + Breaks.HANDLE].toInt()
            val s = vw.streamedShape(h)
            if (s == null || !breakShape(s, breaks, o, simT)) physics.setGive(h, 0f, 0f)
        }
    }

    /** After the steps of a frame: the pieces' poses read (per step, for drawing between them), sleepers settled, the cap kept. */
    internal fun afterStep(simT: Float) {
        if (slotCount == 0) return
        System.arraycopy(now, 0, before, 0, slotCount * Physics.POSE)
        physics.poses(handles, slotCount, now)
        for (i in 0 until slotCount) {
            val p = slots[i] ?: continue
            val awake = now[i * Physics.POSE + 7] > .5f
            if (awake) { p.asleepS = 0f; if (p.settledKey != null) unsettle(p) } else if (p.asleepS == 0f) p.asleepS = simT
        }
    }

    /** The pieces' drawn poses for this frame, [alpha] of the way from the step before to the last; sleepers settle into the walk's map. */
    internal fun publish(alpha: Float, simT: Float, carX: Float, carZ: Float) {
        for (i in 0 until slotCount) {
            val p = slots[i] ?: continue
            val o = i * Physics.POSE
            synchronized(p) {
                for (k in 0..2) p.drawn[k] = (before[o + k] + (now[o + k] - before[o + k]) * alpha) * U
                var dot = 0f
                for (k in 3..6) dot += before[o + k] * now[o + k]
                val sg = if (dot < 0f) -1f else 1f
                var l = 0f
                for (k in 3..6) { val q = before[o + k] + (sg * now[o + k] - before[o + k]) * alpha; p.drawn[k] = q; l += q * q }
                l = sqrt(l).coerceAtLeast(1e-6f)
                for (k in 3..6) p.drawn[k] /= l
            }
            // (asleep a while: into the walk's map if it stands up; far from the car: frozen, the terrain under it may go)
            if (p.asleepS > 0f && simT - p.asleepS > SETTLE_S && p.settledKey == null) settle(p, o)
            val far = hypot(now[o] * U - carX, now[o + 2] * U - carZ) > VehicleWorld.STREAM_M * U
            if (far && p.asleepS > 0f) freeze(p)
        }
    }

    // ------------------------------------------------------------------ breaking

    private fun carOf(o: Int): VehicleWorld.Car? = breaks[o + Breaks.VEHICLE].toInt().let { v -> vw.cars.firstOrNull { it.handle == v } }

    private fun breakShape(s: VehicleWorld.Streamed, b: FloatArray, o: Int, simT: Float): Boolean {
        val m = WreckMaterial.of(s.kind, s.detail)
        if (m.give != Give.BREAK && m.give != Give.BEND) return false
        val car = carOf(o)
        val vx = b[o + Breaks.VX]; val vz = b[o + Breaks.VZ]
        val speed = hypot(vx, vz)
        val cx = b[o + Breaks.X] * U; val cz = b[o + Breaks.Z] * U
        val made = ArrayList<Piece>()
        breaker = car
        val ok = try {
            when {
                s.owner != null && isYard(s.owner) -> breakYard(s, m, b, o, made, simT)
                s.kind == SolidKind.FENCE && s.detail == FarmSolids.DETAIL -> breakRun(s, m, b, o, made, simT)
                else -> breakProp(s, m, b, o, made, simT)
            }
        } finally { breaker = null }
        if (!ok) return false
        vw.dropStreamed(s.handle)
        // the car loses the momentum the pieces took, and what breaking them cost
        if (car != null) {
            var jx = 0f; var jz = 0f
            for (p in made) { val v = pendingV[p.id] ?: continue; jx += p.mass * v[0]; jz += p.mass * v[1] }
            val mass = b[o + Breaks.MASS].coerceAtLeast(1f)
            var dvx = -jx / mass - m.snap * vx; var dvz = -jz / mass - m.snap * vz
            // (never turned round by it: at most half its speed into the thing)
            val along = (dvx * vx + dvz * vz) / max(speed * speed, 1e-4f)
            if (along < -.5f) { dvx *= -.5f / along; dvz *= -.5f / along }
            physics.push(car.handle, dvx, 0f, dvz)
        }
        pendingV.clear()
        val e = DamageEvent(m.name, s.kind, s.detail, s.owner, cx, cz, simT, car?.spec?.key, speed)
        events += e
        vw.onEvent?.invoke(e.toString())
        return true
    }

    /** The car whose break is being handled: no piece is put down inside it ([clearOfCar]). */
    private var breaker: VehicleWorld.Car? = null

    /** The spawn velocities of the pieces made by the break being handled (x, z), for the car's momentum. */
    private val pendingV = HashMap<Int, FloatArray>()

    private fun isYard(owner: String): Boolean = vw.yards?.let { y -> y.of(owner) != null || y.lanes.any { it.id == owner } } == true

    /** The yard items of [owner] as they stand now (its edits, else the town's). */
    fun itemsOf(owner: String): List<TownYardItem>? = yardEdits[owner] ?: vw.yards?.let { y -> y.of(owner)?.items ?: y.lanes.firstOrNull { it.id == owner }?.items }

    private fun findItem(items: List<TownYardItem>, s: VehicleWorld.Streamed, kind: String): Int {
        val x = s.data[0]; val z = s.data[1]
        var best = -1; var bd = Float.MAX_VALUE
        for ((i, it) in items.withIndex()) {
            val r = it.rect
            val ok = if (kind == "gate" || kind == "sideGate") it.kind == kind && x >= r[0] - 4f && x <= r[2] + 4f && z >= r[1] - 4f && z <= r[3] + 4f
                else it.kind == kind
            if (!ok) continue
            val d = hypot((r[0] + r[2]) / 2f - x, (r[1] + r[3]) / 2f - z)
            if (d < bd) { bd = d; best = i }
        }
        val lim = if (kind == "gate" || kind == "sideGate") 40f else if (s.circle) 3f else 1f
        return if (bd <= lim) best else -1
    }

    private fun breakYard(s: VehicleWorld.Streamed, m: WreckMaterial, b: FloatArray, o: Int, made: MutableList<Piece>, simT: Float): Boolean {
        val owner = s.owner!!
        val kind = s.detail!!.substringBefore('/')
        val items = itemsOf(owner) ?: return false
        val idx = findItem(items, s, kind)
        if (idx < 0) return false
        val it = items[idx]
        val list = items.toMutableList()
        when {
            m.panel > 0f -> {
                val rem = splitEdge(it, m, b, o, made, simT) ?: return false
                list.removeAt(idx)
                list.addAll(idx, rem)
                takeOut(s)
                for (r in rem) {
                    val key = "broken:" + nextRemnant++
                    val rr = r.rect
                    val box = floatArrayOf((rr[0] + rr[2]) / 2f, (rr[1] + rr[3]) / 2f, 1f, 0f, (rr[2] - rr[0]) / 2f, (rr[3] - rr[1]) / 2f)
                    col?.setExtra(key, box, s.kind, owner, s.detail)
                    remnants[VehicleWorld.keyOf(box, s.kind)] = key
                }
            }
            kind == "gate" || kind == "sideGate" -> {
                list.removeAt(idx)
                // both its posts go, and the leaf
                col?.let { c ->
                    val r = it.rect
                    c.forEachNearDetail(r[0] - 4f, r[1] - 4f, r[2] + 4f, r[3] + 4f, tmp) { i, k, ow, d, _, _ ->
                        if (k == SolidKind.FENCE && ow == owner && d?.startsWith(kind) == true && i < c.size) c.remove(i)
                    }
                }
                takeOut(s)
                wholeItem(it, m, b, o, made, simT, asPanel = true)
            }
            else -> {
                list.removeAt(idx)
                takeOut(s)
                wholeItem(it, m, b, o, made, simT, asPanel = false)
            }
        }
        yardEdits[owner] = list
        yardVersion++
        return true
    }

    /**
     * A fence run drawn by others than the yards (a farm field's post-and-rail fence, [FarmSolids.DETAIL]): split like a
     * yard's fence ([splitEdge]) as a post-and-rail; the walk's map loses the run (out of the farms' set of shapes, or a
     * remnant's) and gets what is left either side back, still breakable; the broken stretch goes into [cuts] for the
     * farms' drawing to leave out. Runs are along x or z.
     */
    private fun breakRun(s: VehicleWorld.Streamed, m: WreckMaterial, b: FloatArray, o: Int, made: MutableList<Piece>, simT: Float): Boolean {
        if (s.circle) return false
        val c = col ?: return false
        val d = s.data
        val alongX = abs(d[2]) > .999f
        if (!alongX && abs(d[3]) <= .999f) return false
        val hx = if (alongX) d[4] else d[5]; val hz = if (alongX) d[5] else d[4]
        val rect = floatArrayOf(d[0] - hx, d[1] - hz, d[0] + hx, d[1] + hz)
        val run = TownYardItem("postRail", rect, if (alongX) 0f else 1f, if (alongX) 1f else 0f, 1, "edge", "back", null)
        val stretch = FloatArray(2)
        val rem = splitEdge(run, m, b, o, made, simT, stretch) ?: return false
        if (!c.removeExtraBox(d)) remnants.remove(s.key)?.let { c.setExtra(it, null) }
        for (r in rem) {
            val key = "broken:" + nextRemnant++
            val rr = r.rect
            val box = floatArrayOf((rr[0] + rr[2]) / 2f, (rr[1] + rr[3]) / 2f, if (alongX) 1f else 0f, if (alongX) 0f else 1f,
                (if (alongX) rr[2] - rr[0] else rr[3] - rr[1]) / 2f, (if (alongX) rr[3] - rr[1] else rr[2] - rr[0]) / 2f)
            c.setExtra(key, box, s.kind, s.owner, s.detail)
            remnants[VehicleWorld.keyOf(box, s.kind)] = key
        }
        cuts += if (alongX) floatArrayOf(stretch[0], d[1], stretch[1], d[1]) else floatArrayOf(d[0], stretch[0], d[0], stretch[1])
        cutsVersion++
        return true
    }

    /** Takes the shape out of the walk's map (a shape of the map's own, or a remnant added since). */
    private fun takeOut(s: VehicleWorld.Streamed) {
        val c = col ?: return
        if (s.index < c.size) c.remove(s.index)
        else remnants.remove(s.key)?.let { c.setExtra(it, null) }
    }

    /**
     * A fence's or a hedge's stretch the car went through, panel by panel: the panels (snapped to the fence's own) within
     * the car's width of the contact (wider for a glancing hit) become loose pieces; returns what is left standing either
     * side as items of the same kind (none, one or two), or null if it cannot be split.
     */
    private fun splitEdge(it: TownYardItem, m: WreckMaterial, b: FloatArray, o: Int, made: MutableList<Piece>, simT: Float,
                          stretch: FloatArray? = null): List<TownYardItem>? {
        val r = it.rect
        val alongX = r[2] - r[0] >= r[3] - r[1]
        val a0 = if (alongX) r[0] else r[1]; val a1 = if (alongX) r[2] else r[3]
        val c0 = if (alongX) r[1] else r[0]; val c1 = if (alongX) r[3] else r[2]
        val len = a1 - a0
        if (len <= 0f) return null
        val px = b[o + Breaks.X] * U; val pz = b[o + Breaks.Z] * U
        val vx = b[o + Breaks.VX]; val vz = b[o + Breaks.VZ]
        val sp = hypot(vx, vz).coerceAtLeast(1e-3f)
        // the car's half width over the sine of the angle it meets the fence at (a glancing hit tears a longer stretch)
        val cross = abs(if (alongX) vz / sp else vx / sp)
        val car = carOf(o)
        val halfW = (car?.spec?.footprint?.get(1) ?: .9f) + .45f
        val hs = min(halfW / max(cross, .4f), 4.5f) * U
        // where the car's middle crosses the fence's line (the contact may be its corner: the gap is torn round the car,
        // not round the corner, and nothing is left standing across its way), no further from the contact than that
        val c = (c0 + c1) / 2f
        val tc = if (car == null) Float.NaN else {
            val mx = car.x + car.fx * car.spec.footprint[2] * U; val mz = car.z + car.fz * car.spec.footprint[2] * U
            if (alongX) (if (abs(vz) > .3f) mx + vx / vz * (c - mz) else mx) else (if (abs(vx) > .3f) mz + vz / vx * (c - mx) else mz)
        }
        val tp = if (alongX) px else pz
        val t = (if (tc.isNaN()) tp else tc.coerceIn(tp - hs, tp + hs)).coerceIn(a0, a1)
        val pan = m.panel * U
        var k0 = floor((t - hs - a0) / pan); var k1 = ceil((t + hs - a0) / pan)
        k0 = max(0f, k0); k1 = min(ceil(len / pan), k1)
        var s0 = a0 + k0 * pan; var s1 = min(a1, a0 + k1 * pan)
        // (a sliver left either side goes with the broken stretch)
        if (s0 - a0 < pan * .4f) s0 = a0
        if (a1 - s1 < pan * .4f) s1 = a1
        if (s1 <= s0) return null
        stretch?.let { it[0] = s0; it[1] = s1 }
        val n = max(1, Math.round((s1 - s0) / pan))
        val each = (s1 - s0) / n
        val rnd = java.util.Random(((t * 31f).toLong() xor simT.toBits().toLong()) * 1103515245L)
        for (k in 0 until n) {
            val m0 = s0 + k * each; val m1 = m0 + each
            val mid = (m0 + m1) / 2f
            val rect = if (alongX) floatArrayOf(m0, c0, m1, c1) else floatArrayOf(c0, m0, c1, m1)
            val cxm = (rect[0] + rect[2]) / 2f; val czm = (rect[1] + rect[3]) / 2f
            val yaw = if (alongX) 0f else (Math.PI / 2).toFloat()
            // (a panel in the car's path is flung on with it; one at the edge of the gap is knocked aside, slower)
            val inPath = abs(mid - t) < halfW * U + each / 2f
            val f = if (inPath) 1.06f + rnd.nextFloat() * .12f else .35f + rnd.nextFloat() * .25f
            val look = Look("yard", it.kind, it.variant, it.zone, it.faceX, it.faceZ,
                floatArrayOf(rect[0] - cxm, rect[1] - czm, rect[2] - cxm, rect[3] - czm), 0f, 0f, cxm, czm, quatYaw(yaw), m.height / 2f * U, it.layer)
            // (a fence's panel is its boards' thickness, not its posts': lying flat it is a few centimetres, under any
            // car's sills; a hedge's clump or a raised bed's stretch is as deep as it stood)
            val thick = if (it.kind in BOARDS) PANEL_HALF else max(.03f, (c1 - c0) / 2f / U)
            spawn(look, cxm / U, czm / U, each / 2f / U, m.height / 2f, thick, yaw, m.mass * each / U, vx * f, vz * f, if (inPath) 1.4f else .5f, rnd, made, simT)
        }
        val out = ArrayList<TownYardItem>(2)
        fun keep(x0: Float, x1: Float) {
            if (x1 - x0 < 2f) return
            val rr = if (alongX) floatArrayOf(x0, c0, x1, c1) else floatArrayOf(c0, x0, c1, x1)
            out += TownYardItem(it.kind, rr, it.faceX, it.faceZ, it.variant, it.layer, it.zone, it.on)
        }
        keep(a0, s0); keep(s1, a1)
        return out
    }

    /** A yard thing broken whole: one loose piece (a gate: its leaf as a panel). */
    private fun wholeItem(it: TownYardItem, m: WreckMaterial, b: FloatArray, o: Int, made: MutableList<Piece>, simT: Float, asPanel: Boolean) {
        val r = it.rect
        val cx = (r[0] + r[2]) / 2f; val cz = (r[1] + r[3]) / 2f
        val rnd = java.util.Random((cx * 7f + cz * 13f).toLong() xor simT.toBits().toLong())
        val look = Look("yard", it.kind, it.variant, it.zone, it.faceX, it.faceZ,
            floatArrayOf(r[0] - cx, r[1] - cz, r[2] - cx, r[3] - cz), 0f, 0f, cx, cz, quatYaw(0f), m.height / 2f * U, it.layer)
        val hx = (r[2] - r[0]) / 2f / U; val hz = (r[3] - r[1]) / 2f / U
        val vx = b[o + Breaks.VX]; val vz = b[o + Breaks.VZ]
        if (m.give == Give.BEND) { bend(look, cx / U, cz / U, max(.08f, min(hx, hz) * .5f), m, vx, vz, rnd, made, simT); return }
        val f = 1.05f + rnd.nextFloat() * .15f
        // (a gate's leaf is its boards' or rails' thickness across its short side)
        val thin = if (asPanel) PANEL_HALF else .05f
        spawn(look, cx / U, cz / U, if (asPanel && hx < hz) thin else max(.05f, hx), m.height / 2f, if (asPanel && hz <= hx) thin else max(.05f, hz),
            0f, m.mass, vx * f, vz * f, if (asPanel) .8f else 1.2f, rnd, made, simT)
    }

    /** A street thing (a lamp, a bench, a sign, a bin...) broken or snapped off. */
    private fun breakProp(s: VehicleWorld.Streamed, m: WreckMaterial, b: FloatArray, o: Int, made: MutableList<Piece>, simT: Float): Boolean {
        val detail = s.detail ?: return false
        val x = s.data[0]; val z = s.data[1]
        val rnd = java.util.Random((x * 7f + z * 13f).toLong() xor simT.toBits().toLong())
        val vx = b[o + Breaks.VX]; val vz = b[o + Breaks.VZ]
        val hx: Float; val hz: Float; val yaw: Float
        if (s.circle) { hx = s.data[2] / U; hz = hx; yaw = 0f } else { hx = s.data[4] / U; hz = s.data[5] / U; yaw = atan2(-s.data[3], s.data[2]) }
        val look = Look("prop", detail, 0, "", 0f, 1f, null, x, z, x, z, quatYaw(yaw), m.height / 2f * U)
        if (m.give == Give.BEND) bend(look, x / U, z / U, max(.06f, min(hx, hz)), m, vx, vz, rnd, made, simT)
        else { val f = 1.05f + rnd.nextFloat() * .15f; spawn(look, x / U, z / U, hx, m.height / 2f, hz, yaw, m.mass, vx * f, vz * f, 1f, rnd, made, simT) }
        takeOut(s)
        hidden += Hidden(x, z, detail)
        yardVersion++
        return true
    }

    /** Something tall snapped at its foot: one piece, its foot pushed on and its top tipping away from the car. */
    private fun bend(look: Look, xm: Float, zm: Float, half: Float, m: WreckMaterial, vx: Float, vz: Float, rnd: java.util.Random, made: MutableList<Piece>, simT: Float) {
        val sp = hypot(vx, vz).coerceAtLeast(1e-3f)
        val dx = vx / sp; val dz = vz / sp
        val hy = m.height / 2f
        // (about the horizontal axis up x d: the top goes the car's way, the foot barely moves)
        val w = .55f * sp / hy
        val ax = dz * w; val az = -dx * w
        spawnRaw(look, xm, hy, zm, half, hy, half, look.q0, m.mass, vx * .5f, .4f, vz * .5f, ax, (rnd.nextFloat() - .5f) * .6f, az, made, simT)
    }

    private fun spawn(look: Look, xm: Float, zm: Float, hx: Float, hy: Float, hz: Float, yaw: Float, mass: Float, vx: Float, vz: Float, up: Float,
                      rnd: java.util.Random, made: MutableList<Piece>, simT: Float) {
        // a tumble: mostly over the way it flies (a panel hinging over), a little yaw
        val sp = hypot(vx, vz).coerceAtLeast(1e-3f)
        val tip = (2f + rnd.nextFloat() * 3f) * min(1f, sp / 8f)
        val ax = vz / sp * tip; val az = -vx / sp * tip
        spawnRaw(look, xm, hy + .02f, zm, hx, hy, hz, quatYaw(yaw), mass, vx, up * min(1f, sp / 6f) + rnd.nextFloat() * .3f, vz, ax, (rnd.nextFloat() - .5f) * 2f, az, made, simT)
    }

    private fun spawnRaw(look: Look, x: Float, y: Float, z: Float, hx: Float, hy: Float, hz: Float, q: FloatArray, mass: Float,
                         vx: Float, vy: Float, vz: Float, ax: Float, ay: Float, az: Float, made: MutableList<Piece>, simT: Float) {
        if (slotCount >= liveCap.coerceIn(1, LIVE)) freezeOldest()
        val d = dd
        // (never inside the car that broke it: moved out of its body the shortest way, if that is short)
        val yaw = 2f * atan2(q[1], q[3])
        val out = clearOfCar(x, z, hx, hz, yaw)
        d[0] = x + out[0]; d[1] = y; d[2] = z + out[1]; d[3] = hx; d[4] = hy; d[5] = hz
        d[6] = q[0]; d[7] = q[1]; d[8] = q[2]; d[9] = q[3]
        d[10] = mass; d[11] = vx; d[12] = vy; d[13] = vz; d[14] = ax; d[15] = ay; d[16] = az; d[17] = .7f
        val h = physics.addDebris(d)
        if (h < 0) return
        val p = Piece(nextId++, if (out[0] == 0f && out[1] == 0f) look else look.movedBy(out[0] * U, out[1] * U), hx, hy, hz, mass, simT)
        p.handle = h
        p.slot = slotCount
        slots[slotCount] = p; handles[slotCount] = h
        val o = slotCount * Physics.POSE
        now[o] = d[0]; now[o + 1] = y; now[o + 2] = d[2]; now[o + 3] = q[0]; now[o + 4] = q[1]; now[o + 5] = q[2]; now[o + 6] = q[3]; now[o + 7] = 1f
        System.arraycopy(now, o, before, o, Physics.POSE)
        synchronized(p) { p.drawn[0] = d[0] * U; p.drawn[1] = y * U; p.drawn[2] = d[2] * U; for (k in 0..3) p.drawn[3 + k] = q[k] }
        slotCount++
        liveCount++
        pendingV[p.id] = floatArrayOf(vx, vz)
        made += p
        pieces += p
        // (past what is kept drawn, the oldest frozen ones go)
        if (pieces.size > KEPT) pieces.firstOrNull { it.frozen }?.let { gone -> gone.settledKey?.let { col?.setExtra(it, null) }; pieces.remove(gone) }
        piecesVersion++
    }

    private val clear = FloatArray(2)

    /**
     * How far (m: x, z) to move a piece of half extents [hx], [hz] (m) turned [yaw] at ([x], [z]) (m) on the ground so it
     * is clear of the breaking car's body (where it will be after this step, with a margin): the shortest way out (the
     * separating axis's), or nothing when it does not overlap or the way out is longer than [MAX_SHIFT_M] (a piece across
     * the whole car: the physics lets it through, it never wedges).
     */
    private fun clearOfCar(x: Float, z: Float, hx: Float, hz: Float, yaw: Float): FloatArray {
        clear[0] = 0f; clear[1] = 0f
        val car = breaker ?: return clear
        val fp = car.spec.footprint
        val fx = car.fx; val fz = car.fz
        val l = hypot(fx, fz).takeIf { it > 1e-4f } ?: return clear
        val ax = fx / l; val az = fz / l
        val st = car.now
        val cx = car.x / U + ax * fp[2] + st[com.lifemax.rpg.feature.habits.physics.VehicleState.VX] * VehicleWorld.STEP
        val cz = car.z / U + az * fp[2] + st[com.lifemax.rpg.feature.habits.physics.VehicleState.VZ] * VehicleWorld.STEP
        val cl = fp[0] + CAR_MARGIN_M; val cw = fp[1] + CAR_MARGIN_M
        // the piece's axes on the ground (its yaw about +y: its x is (cos, -sin))
        val px = cos(yaw); val pz = -sin(yaw)
        val dx = x - cx; val dz = z - cz
        var best = Float.MAX_VALUE; var bx = 0f; var bz = 0f
        for ((ux, uz) in arrayOf(ax to az, -az to ax, px to pz, -pz to px)) {
            // the two boxes' half widths along (ux, uz) and the gap between their middles
            val rc = cl * abs(ax * ux + az * uz) + cw * abs(-az * ux + ax * uz)
            val rp = hx * abs(px * ux + pz * uz) + hz * abs(-pz * ux + px * uz)
            val dist = dx * ux + dz * uz
            val over = rc + rp - abs(dist)
            if (over <= 0f) return clear
            if (over < best) { best = over; val sg = if (dist >= 0f) 1f else -1f; bx = ux * sg * over; bz = uz * sg * over }
        }
        if (best > MAX_SHIFT_M) return clear
        clear[0] = bx; clear[1] = bz
        return clear
    }

    private fun freezeOldest() {
        var oldest: Piece? = null
        for (i in 0 until slotCount) { val p = slots[i] ?: continue; if (oldest == null || p.bornS < oldest.bornS) oldest = p }
        oldest?.let { freeze(it) }
    }

    /** Takes [p] out of the physics, lying where it is (still drawn; in the walk's map if it stands up). */
    private fun freeze(p: Piece) {
        if (p.frozen || p.slot < 0) return
        val i = p.slot
        if (p.settledKey == null) settle(p, i * Physics.POSE)
        physics.remove(p.handle)
        p.handle = -1; p.frozen = true
        // the last slot moves into its place
        val last = slotCount - 1
        if (i != last) {
            val q = slots[last]!!
            slots[i] = q; handles[i] = handles[last]; q.slot = i
            System.arraycopy(now, last * Physics.POSE, now, i * Physics.POSE, Physics.POSE)
            System.arraycopy(before, last * Physics.POSE, before, i * Physics.POSE, Physics.POSE)
        }
        slots[last] = null
        p.slot = -1
        slotCount--
        liveCount--
    }

    /** A sleeping piece that stands up (its up still up, taller than a step) blocks the walker; one lying flat does not. */
    private fun settle(p: Piece, o: Int) {
        val c = col ?: return
        val qx = now[o + 3]; val qy = now[o + 4]; val qz = now[o + 5]; val qw = now[o + 6]
        val upY = 1f - 2f * (qx * qx + qz * qz)
        val key = "debris:${p.id}"
        p.settledKey = key
        if (upY < .8f || p.hy * 2f < .5f || max(p.hx, p.hz) < .12f) return
        // its local x on the ground
        val ux = 1f - 2f * (qy * qy + qz * qz); val uz = 2f * (qx * qz - qw * qy)
        val l = hypot(ux, uz).coerceAtLeast(1e-4f)
        c.setExtra(key, floatArrayOf(now[o] * U, now[o + 2] * U, ux / l, uz / l, p.hx * U, p.hz * U), SolidKind.PROP, key)
    }

    private fun unsettle(p: Piece) { p.settledKey?.let { col?.setExtra(it, null) }; p.settledKey = null }

    /** Forgets every piece and edit (a new park: a new town). */
    internal fun clear() {
        for (i in 0 until slotCount) slots[i]?.let { physics.remove(it.handle) }
        for (p in pieces) p.settledKey?.let { col?.setExtra(it, null) }
        slots.fill(null); slotCount = 0; liveCount = 0
        pieces.clear(); piecesVersion++
        hidden.clear(); yardEdits.clear(); yardVersion++
        remnants.clear()
    }

    companion object {
        private const val U = VehicleWorld.U
        /** At most this many pieces in the physics; past it the oldest are frozen. */
        const val LIVE = 200
        /** At most this many drawn (frozen ones past it go, oldest first). */
        const val KEPT = 600
        /** Half a fence panel's or a gate leaf's thickness (m): its boards'. */
        const val PANEL_HALF = .035f
        /** The edges whose broken stretches are thin panels. */
        private val BOARDS = setOf("frontFence", "sideFence", "backFence", "postRail", "gate", "sideGate")
        /** A piece is put down at least this clear of the breaking car's body (m), moved at most [MAX_SHIFT_M] to be. */
        const val CAR_MARGIN_M = .06f
        const val MAX_SHIFT_M = 1.2f
        /** A piece asleep this long (s) settles: it may stand in the walk's way. */
        const val SETTLE_S = .5f

        fun quatYaw(yaw: Float): FloatArray = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
    }
}
