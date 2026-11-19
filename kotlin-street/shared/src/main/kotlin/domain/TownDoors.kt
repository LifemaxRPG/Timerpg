package com.lifemax.rpg.feature.habits.domain

import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * The town's front doors and the spots its people to talk to stand at, in the world, for an open-world walk: who is near
 * enough to talk to from any direction, and which door the player stands at, facing it (docs/TOWN.md "Open world").
 */

/**
 * One building's front door: [sprite] on [row], the point on its front wall ([wallX], [wallZ]), where a walker stands at it
 * ([x], [z]: just clear of the wall, out towards the street) and the way out of it ([outX], [outZ], unit).
 */
class TownDoor(
    val sprite: String,
    val row: String,
    val kind: PlacementKind,
    val wallX: Float, val wallZ: Float,
    val x: Float, val z: Float,
    val outX: Float, val outZ: Float,
)

/**
 * Every building's front door in [town]: on the front wall at its front path (the yard's `frontPath`, which the export runs
 * to the door), else the front's middle (the cinema's: its entrance under the canopy), then walked out of whatever
 * [collision] has there until a walker of [radius] stands clear. The people to talk to stand where [keeperSpot] puts
 * them (as TownPlay stands them on the pavement).
 */
class TownDoors(val town: Town, yards: TownYards, private val collision: TownCollision?, cinema: CinemaSiteShape? = null, radius: Float = 6f) {
    val doors: List<TownDoor>
    private val bySprite: Map<String, TownDoor>

    /** Each building's person-to-talk-to spot (world x, z), in [keeperSprites]' order. */
    val keeperSprites: Array<String>
    val keeperX: FloatArray
    val keeperZ: FloatArray

    init {
        val out = ArrayList<TownDoor>()
        // (a store's doors are its entrances as built: [TownCollision.extraDoors], added once its model is up)
        val stores = collision?.stores ?: emptySet()
        for (row in town.rows) for (b in row.buildings) if (b.sprite !in stores && b.kind != PlacementKind.EXTRA) out += door(row, b, yards, collision, cinema, radius)
        doors = out
        bySprite = out.associateBy { it.sprite }
        val all = town.rows.flatMap { r -> r.buildings.filter { it.kind != PlacementKind.EXTRA }.map { r to it } }
        keeperSprites = Array(all.size) { all[it].second.sprite }
        keeperX = FloatArray(all.size); keeperZ = FloatArray(all.size)
        for ((i, rb) in all.withIndex()) { val p = keeperSpot(rb.first, rb.second); keeperX[i] = p.x; keeperZ[i] = p.z }
    }

    private val all: Array<TownDoor> = doors.toTypedArray()

    fun of(sprite: String): TownDoor? = bySprite[sprite] ?: collision?.extraDoors?.firstOrNull { it.sprite == sprite }

    /**
     * The door a walker at ([px], [pz]) heading ([hx], [hz]) is at: within [range] of where one stands at it, on its
     * outside (not behind the wall) and, unless right at it ([AT]), roughly facing it (within about 70 degrees). Null if none.
     */
    fun at(px: Float, pz: Float, hx: Float, hz: Float, range: Float = RANGE): TownDoor? {
        var best: TownDoor? = null
        var bestD = Float.MAX_VALUE
        for (d in all) { val dist = reach(d, px, pz, hx, hz, range); if (dist < bestD) { best = d; bestD = dist } }
        val extra = collision?.extraDoors
        if (extra != null) for (d in extra) { val dist = reach(d, px, pz, hx, hz, range); if (dist < bestD) { best = d; bestD = dist } }
        return best
    }

    /** How far a walker at ([px], [pz]) heading ([hx], [hz]) is from standing at door [d], or MAX_VALUE if not at it ([at]'s test). */
    private fun reach(d: TownDoor, px: Float, pz: Float, hx: Float, hz: Float, range: Float): Float {
        val dx = d.x - px; val dz = d.z - pz
        val dist = sqrt(dx * dx + dz * dz)
        if (dist > range) return Float.MAX_VALUE
        // on its outside: in front of its wall
        if ((px - d.wallX) * d.outX + (pz - d.wallZ) * d.outZ < -1f) return Float.MAX_VALUE
        if (dist > AT) {
            val wx = d.wallX - px; val wz = d.wallZ - pz
            val wl = hypot(wx, wz).coerceAtLeast(1e-4f)
            if ((wx * hx + wz * hz) / wl < FACING) return Float.MAX_VALUE
        }
        return dist
    }

    /**
     * Of the buildings whose sprite is in [keepers] (the people to talk to), the one whose person stands nearest ([px],
     * [pz]) and within [range] (ground distance, any direction); null if nobody is that near.
     */
    fun nearestKeeper(px: Float, pz: Float, keepers: Map<String, Int>, range: Float): String? {
        var best: String? = null
        var bestD = range
        for (i in keeperSprites.indices) {
            val d = hypot(keeperX[i] - px, keeperZ[i] - pz)
            if (d <= bestD && keeperSprites[i] in keepers) { best = keeperSprites[i]; bestD = d }
        }
        return best
    }

    /** How far ([px], [pz]) is from the person at [sprite] (Float.MAX_VALUE if unknown). */
    fun fromKeeper(px: Float, pz: Float, sprite: String): Float {
        for (i in keeperSprites.indices) if (keeperSprites[i] == sprite) return hypot(keeperX[i] - px, keeperZ[i] - pz)
        return Float.MAX_VALUE
    }

    companion object {
        /** How near where one stands at a door a walker must be for "Go inside" (street units). */
        const val RANGE = 44f
        /** Right at the door: any facing will do. */
        const val AT = 14f
        /** The cosine of how far off facing the door a walker may look (about 70 degrees). */
        const val FACING = .34f
        /** Where the person to talk to at a building stands: along from its middle, and out across the pavement in lanes (TalkRange's). */
        const val KEEPER_DX = 30f
        const val KEEPER_LANE = 3.6f

        fun keeperSpot(r: Row, b: Placement): TownPoint = r.pointAt(b.mid + KEEPER_DX, KEEPER_LANE)

        /**
         * A door on a wall at ([wallX], [wallZ]) facing out along ([outX], [outZ]) (unit), for [sprite] on [row]: where a
         * walker of [radius] stands at it, walked out from the wall until [collision] has them clear (a store's entrance
         * as its builder puts it; the shapes in front, a vestibule's glass or a porch's steps, already in the map).
         */
        fun standing(sprite: String, row: String, kind: PlacementKind, wallX: Float, wallZ: Float, outX: Float, outZ: Float,
                     collision: TownCollision, radius: Float = 6f): TownDoor {
            var t = 0f
            while (t < 160f) {
                val x = wallX + outX * t; val z = wallZ + outZ * t
                if (collision.clearance(x, z, radius * 4f) >= radius + 1f) return TownDoor(sprite, row, kind, wallX, wallZ, x, z, outX, outZ)
                t += 2f
            }
            return TownDoor(sprite, row, kind, wallX, wallZ, wallX + outX * (radius + 2f), wallZ + outZ * (radius + 2f), outX, outZ)
        }

        private fun door(row: Row, b: Placement, yards: TownYards, collision: TownCollision?, cinema: CinemaSiteShape?, radius: Float): TownDoor {
            val place = TownPlace.of(row, b)
            // along the front (local x: (nz, -nx) in the world)
            var lx = 0f
            val path = yards.of(b.sprite)?.items?.firstOrNull { it.kind == "frontPath" }
            if (path != null) {
                val cx = (path.rect[0] + path.rect[2]) / 2f; val cz = (path.rect[1] + path.rect[3]) / 2f
                lx = ((cx - place.ox) * place.nz - (cz - place.oz) * place.nx).coerceIn(-b.width / 2f + 8f, b.width / 2f - 8f)
            } else if (cinema != null && cinema.sprite == b.sprite) lx = -cinema.body().cx
            val wall = place.world(lx, b.setback)
            val ox = -place.nx; val oz = -place.nz
            // walked out from inside the building (its middle's depth) until a walker stands clear in front of its real front
            var sx = wall.x; var sz = wall.z
            if (collision != null) {
                var lz = b.setback + b.depth / 2f
                var wasIn = false
                var found = false
                while (lz > -200f) {
                    val p = place.world(lx, lz)
                    val inside = collision.clearance(p.x, p.z, radius * 4f) < radius + 1f
                    if (inside) wasIn = true
                    else if (wasIn || lz <= b.setback) { sx = p.x; sz = p.z; found = true; break }
                    lz -= 2f
                }
                if (!found) { sx = wall.x + ox * (radius + 2f); sz = wall.z + oz * (radius + 2f) }
                // the wall point: where that line meets the building (the last point inside), else the slot's front
                if (wasIn) { val w = place.world(lx, lz + 2f); return TownDoor(b.sprite, row.id, b.kind, w.x, w.z, sx, sz, ox, oz) }
            }
            return TownDoor(b.sprite, row.id, b.kind, wall.x, wall.z, sx, sz, ox, oz)
        }
    }
}
