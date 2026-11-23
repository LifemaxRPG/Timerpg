package com.lifemax.rpg.feature.habits.vehicle

import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Where a car can be parked (street units): at the kerb of a street, heading the way the traffic on that side goes
 * (on the right, as in the country the cars were made for), and in a yard's driveway or parking pad, nose to the house.
 */
object VehicleSpots {
    /** A spot: its middle, the way a car stands in it (unit), how long it is, and a name (stable: the collision's key). */
    class Spot(val name: String, val x: Float, val z: Float, val fx: Float, val fz: Float, val length: Float)

    /** The road's own width a side, kerb to its middle (street units): the road picture's asphalt, inside its pavements and kerbs. */
    const val HALF_ROADWAY = 50f
    /** A kerbside car's middle stands this far (m) in from the kerb. */
    const val KERB_GAP_M = 1.15f
    /** Kerbside spots are this long (m), and none nearer a junction's middle than [CLEAR_OF_JUNCTION_M]. */
    const val STREET_SPOT_M = 6.5f
    const val CLEAR_OF_JUNCTION_M = 16f

    /**
     * Kerbside spots on every road, nearest ([x], [z]) first: each road's segments cut into [STREET_SPOT_M] spots on
     * both sides, those near junctions left out and those [collision] finds something in (a prop, a bridge's parapet).
     */
    fun street(town: Town, x: Float, z: Float, collision: TownCollision?): List<Spot> {
        val u = VehicleWorld.U
        val out = ArrayList<Spot>()
        val len = STREET_SPOT_M * u
        val off = HALF_ROADWAY - KERB_GAP_M * u
        for (road in town.roads) for (s in 0 until road.pts.size - 1) {
            val a = road.pts[s]; val b = road.pts[s + 1]
            val dx = b.x - a.x; val dz = b.z - a.z
            val l = hypot(dx, dz)
            if (l < 2f * CLEAR_OF_JUNCTION_M * u + len) continue
            val ux = dx / l; val uz = dz / l
            // the right of the way a to b (y up, z south: (ux, uz) turned a quarter clockwise seen from above)
            val rx = -uz; val rz = ux
            var t = CLEAR_OF_JUNCTION_M * u + len / 2f
            var k = 0
            while (t < l - CLEAR_OF_JUNCTION_M * u - len / 2f) {
                for (side in intArrayOf(1, -1)) {
                    // on the right of the traffic: a to b on the right side, b to a on the left
                    val px = a.x + ux * t + rx * off * side; val pz = a.z + uz * t + rz * off * side
                    val fx = ux * side; val fz = uz * side
                    if (collision != null && blocked(collision, px, pz, fx, fz, len)) continue
                    out += Spot("street:${road.id}:$s:$k:$side", px, pz, fx, fz, len)
                }
                t += len; k++
            }
        }
        return out.sortedBy { hypot(it.x - x, it.z - z) }
    }

    /** Driveways and parking pads big enough for a car, nearest ([x], [z]) first, the car's nose to the house. */
    fun driveways(yards: TownYards, x: Float, z: Float, collision: TownCollision?): List<Spot> {
        val out = ArrayList<Spot>()
        for (y in yards.yards) for (it in y.items) {
            if (it.kind != "driveway" && it.kind != "parkingPad") continue
            val r = it.rect
            val w = r[2] - r[0]; val d = r[3] - r[1]
            // the long side is the way in; the face is towards the street, so the nose goes the other way (to the house)
            val alongX = w > d
            val length = max(w, d)
            val width = if (alongX) d else w
            if (width < 1.9f * VehicleWorld.U) continue
            var fx = if (alongX) 1f else 0f; var fz = if (alongX) 0f else 1f
            if (fx * it.faceX + fz * it.faceZ > 0f) { fx = -fx; fz = -fz }
            // (a long drive: the car stands near its house end, a car's length and a little in from it)
            val cx = (r[0] + r[2]) / 2f; val cz = (r[1] + r[3]) / 2f
            val reach = (length / 2f - 3.3f * VehicleWorld.U).coerceAtLeast(0f)
            val px = cx + fx * reach; val pz = cz + fz * reach
            if (collision != null && blocked(collision, px, pz, fx, fz, 5f * VehicleWorld.U)) continue
            out += Spot("drive:${y.sprite}:${it.kind}", px, pz, fx, fz, length)
        }
        return out.sortedBy { hypot(it.x - x, it.z - z) }
    }

    /** Whether something solid stands within a car's footprint at the spot (its middle, its ends, its corners). */
    private fun blocked(c: TownCollision, x: Float, z: Float, fx: Float, fz: Float, len: Float): Boolean {
        val u = VehicleWorld.U
        val hl = abs(len / 2f - .2f * u); val hw = .85f * u
        val lx = fz; val lz = -fx
        for (a in floatArrayOf(-hl, 0f, hl)) for (b in floatArrayOf(-hw, hw)) if (c.blocked(x + fx * a + lx * b, z + fz * a + lz * b, .3f * u)) return true
        return false
    }
}
