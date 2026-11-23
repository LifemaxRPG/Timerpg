package com.lifemax.rpg.feature.habits.loop

import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One of the people the game draws (TownPlay's crowd): where (street units), the way they face, standing or walking, who. */
class LoopFigure(val x: Float, val z: Float, val dx: Float, val dz: Float, val still: Boolean, val who: Int, val keeper: String?)

/** Someone who could see what the player does: a facade SceneBystander (src/loop/types.ts), positions in street units. */
class LoopBystander(val id: String, val x: Float, val z: Float, val fx: Float, val fz: Float, val activity: String, val los: Boolean, val distance: Float)

/**
 * The scene at an action: where the player is (street units), who is near and whether they can see, the light. [inside]:
 * in a building's room (only the people in the room count, and its lights). Sent with `act` as the facade's SceneSnapshot.
 */
class LoopScene(val x: Float, val z: Float, val bystanders: List<LoopBystander>, val light: Double?, val inside: Boolean, val appearance: JSONObject? = null) {
    fun put(o: JSONObject): JSONObject {
        o.put("position", JSONObject().put("x", r2(x / U)).put("y", r2(z / U)))
        o.put("bystanders", JSONArray().also { a ->
            for (b in bystanders) a.put(JSONObject().put("id", b.id).put("position", JSONObject().put("x", r2(b.x / U)).put("y", r2(b.z / U)))
                .put("facing", JSONObject().put("x", r2(b.fx.toDouble())).put("y", r2(b.fz.toDouble()))).put("activity", b.activity).put("hasLineOfSight", b.los))
        })
        light?.let { o.put("light", r2(it)) }
        appearance?.let { o.put("appearance", it) }
        return o
    }

    companion object {
        private const val U = VehicleWorld.U.toDouble()
        private fun r2(d: Double) = Math.round(d * 100.0) / 100.0
    }
}

/**
 * Line of sight and the scene round the player (docs/LOOP.md: Kotlin owns what is physical). The sight test is a
 * simple ray against the walk's collision: buildings, walls and hedges block it; fences, props and trees do not (people
 * see over a fence and past a lamp post).
 */
object LoopSight {
    /** How far a bystander can see the player (street units: 40 m). */
    val RANGE: Float = 40f * VehicleWorld.U
    private const val STEP = 10f
    /** The ends of the ray are not tested this far (a person standing against their own front wall). */
    private const val END = 24f
    private val BLOCKS = setOf(SolidKind.BUILDING, SolidKind.WALL, SolidKind.HEDGE)

    /** True if nothing solid that blocks the view stands between ([ax], [az]) and ([bx], [bz]) (no collision: always). */
    fun clear(col: TownCollision?, ax: Float, az: Float, bx: Float, bz: Float): Boolean {
        if (col == null) return true
        val len = hypot(bx - ax, bz - az)
        if (len <= 2 * END) return true
        val tmp = FloatArray(8)
        var s = END
        while (s < len - END) {
            val x = ax + (bx - ax) * s / len; val z = az + (bz - az) * s / len
            var hit = false
            col.forEachNear(x - 1f, z - 1f, x + 1f, z + 1f, tmp) { i, kind, _, circle, d ->
                if (!hit && kind in BLOCKS && !col.isRemoved(i)) hit = if (circle) hypot(x - d[0], z - d[1]) < d[2] else {
                    val px = x - d[0]; val pz = z - d[1]
                    abs(px * d[2] + pz * d[3]) <= d[4] && abs(-px * d[3] + pz * d[2]) <= d[5]
                }
            }
            if (hit) return false
            s += STEP
        }
        return true
    }

    /** The light at hour [h] outdoors (0..1) before the lamps: day 1, a dusk and dawn ramp, night 0.12. */
    fun daylight(h: Double): Double {
        val x = ((h % 24.0) + 24.0) % 24.0
        return when {
            x < 5.0 || x >= 21.0 -> .12
            x < 7.0 -> .12 + (x - 5.0) / 2.0 * .88
            x < 19.0 -> 1.0
            else -> 1.0 - (x - 19.0) / 2.0 * .88
        }
    }

    /** The light at ([x], [z]) outdoors at [hour]: daylight, raised by the nearest lamps after dark. */
    fun lightAt(town: LoopTown, x: Float, z: Float, hour: Double): Double {
        var l = daylight(hour)
        if (l >= 1.0) return l
        val r = 16f * VehicleWorld.U
        for (lp in town.lamps) {
            val d = hypot(lp[0] - x, lp[1] - z)
            if (d < r) l = max(l, .75 * (1.0 - d / r) + .12)
        }
        return l.coerceIn(0.0, 1.0)
    }

    /**
     * The scene outdoors: the player at ([px], [pz]); [crowd] the people drawn (the keepers at their doors carry their
     * building, the passers a seed [LoopTown.passerId] turns into one of the town's people); each one within [RANGE]
     * with whether the walk's collision lets them see the player. The light is left to the facade (the hour and the
     * lamps given at init) unless [light] is given.
     */
    fun outdoors(town: LoopTown, col: TownCollision?, crowd: List<LoopFigure>, px: Float, pz: Float, light: Double? = null): LoopScene {
        val out = ArrayList<LoopBystander>()
        val seen = HashSet<String>()
        for (f in crowd) {
            val d = hypot(f.x - px, f.z - pz)
            if (d > RANGE) continue
            val id = if (f.keeper != null) town.atBuilding[f.keeper]?.firstOrNull() ?: continue else town.passerId(f.who)
            if (!seen.add(id)) continue
            val l = hypot(f.dx, f.dz).coerceAtLeast(1e-4f)
            out += LoopBystander(id, f.x, f.z, f.dx / l, f.dz / l, if (f.keeper != null) "idle" else if (f.still) "idle" else "walking",
                clear(col, f.x, f.z, px, pz), d)
        }
        return LoopScene(px, pz, out.sortedBy { it.distance }, light, inside = false)
    }

    /**
     * The scene inside a shop's room: the keeper at their spot ([kx], [ky], room pixels) facing the way in ([ex], [ey]),
     * the player at ([wx], [wy]); the room is placed at the shop's door, 32 pixels a metre. [others]: more of the staff,
     * standing beside the keeper. The room's lights: [light].
     */
    fun inside(place: LoopPlace, kx: Float, ky: Float, ex: Float, ey: Float, wx: Float, wy: Float, light: Double, others: List<String> = emptyList()): LoopScene {
        val k = VehicleWorld.U / 32f
        fun wX(x: Float) = place.doorX + (x - ex) * k
        fun wZ(y: Float) = place.doorZ + (y - ey) * k
        val fx = ex - kx; val fy = ey - ky
        val l = hypot(fx, fy).coerceAtLeast(1e-4f)
        val out = ArrayList<LoopBystander>()
        place.keeperId?.let { out += LoopBystander(it, wX(kx), wZ(ky), fx / l, fy / l, "working", true, hypot(wx - kx, wy - ky) * k) }
        others.forEachIndexed { i, id -> if (id != place.keeperId) out += LoopBystander(id, wX(kx + 60f * (i + 1)), wZ(ky), fx / l, fy / l, "working", true, hypot(wx - kx - 60f * (i + 1), wy - ky) * k) }
        return LoopScene(wX(wx), wZ(wy), out, light, inside = true)
    }
}
