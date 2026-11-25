package com.lifemax.rpg.feature.habits.domain

import kotlin.math.floor
import kotlin.math.hypot

/**
 * A position on the planet (WORLD_GEN.md 1, "Units and precision"): metres east ([x], in [0, [Planet.C]), wrapping) and
 * south ([z], from the north pole line), in doubles, which hold millimetres at 2,000 km. Nothing is ever placed in
 * Filament in these: [RenderOrigin] turns them into floats near the render origin.
 */
data class WorldPos(val x: Double, val z: Double) {
    /** The wrapped difference to [o] (m): east and south, x in [-C/2, C/2). */
    fun dx(o: WorldPos): Double = Planet.wrapDx(o.x - x)
    fun dz(o: WorldPos): Double = o.z - z
    fun distance(o: WorldPos): Double = hypot(dx(o), dz(o))
    fun plus(ex: Double, ez: Double): WorldPos = WorldPos(Planet.wrapX(x + ex), z + ez)
    /** In atlas cell units (the skeleton's own). */
    val cellX: Double get() = x / Planet.CELL_M
    val cellZ: Double get() = z / Planet.CELL_M

    companion object {
        /** A position from atlas cell units (the skeleton's places, pins and paths). */
        fun ofCells(x: Double, z: Double): WorldPos = WorldPos(Planet.wrapX(x * Planet.CELL_M), z * Planet.CELL_M)
    }
}

/** The planet's measures (WORLD_GEN.md 1): a cylinder, x wraps east-west, z runs pole to pole. */
object Planet {
    const val TILE_M = 2.1
    const val CHUNK_M = 256 * TILE_M
    const val CELL_M = WorldSkeleton.CELL_M
    /** The circumference (m): 1,856 atlas cells. */
    const val C = WorldSkeleton.W * CELL_M
    const val POLE = WorldSkeleton.H * CELL_M

    fun wrapX(x: Double): Double { val r = x % C; return if (r < 0) r + C else r }
    /** A wrapped difference in [-C/2, C/2). */
    fun wrapDx(d: Double): Double { var r = d % C; if (r < -C / 2) r += C else if (r >= C / 2) r -= C; return r }
}

/**
 * A hub's own frame (WORLD_GEN.md 1, "The towns keep their frames"): the town's plan is laid in tiles from its own origin
 * (tile 0, 0: the plan's north-west corner) in street units ([unitsPerTile] a tile: street.json's `scale`, 44.5); the
 * origin is a [WorldPos], the plan's centre on the hub's site. [elevM] is the town's ground height (its pad is flattened
 * to it), [ground] the town's ground rect in street units (x0, z0, x1, z1: the plan and its farm ring).
 */
class HubFrame(val hubId: String, val origin: WorldPos, val unitsPerTile: Double, val tileM: Double, val elevM: Double, val ground: FloatArray) {
    val unitsPerMetre: Double get() = unitsPerTile / tileM
    /** Street units (the town's frame) of world position [p], as doubles. */
    fun streetX(p: WorldPos): Double = origin.dx(p) * unitsPerMetre
    fun streetZ(p: WorldPos): Double = origin.dz(p) * unitsPerMetre
    /** The world position of street position ([sx], [sz]). */
    fun world(sx: Double, sz: Double): WorldPos = origin.plus(sx / unitsPerMetre, sz / unitsPerMetre)
    /** The plan's tile of street position. */
    fun tileX(sx: Double): Double = sx / unitsPerTile
    fun tileZ(sz: Double): Double = sz / unitsPerTile
    /** Whether [p] is within [m] metres of the town's ground. */
    fun near(p: WorldPos, m: Double): Boolean {
        val x = streetX(p); val z = streetZ(p); val u = m * unitsPerMetre
        return x > ground[0] - u && x < ground[2] + u && z > ground[1] - u && z < ground[3] + u
    }

    companion object {
        /**
         * Gamma's frame on the planet: the skeleton's hub [hub] (its centre is the plan's centre, [WorldSkeleton.PlanTiles]),
         * the town's street scale [unitsPerTile]; the town stands at the height of its site's cell.
         */
        fun of(sk: WorldSkeleton, hub: WorldSkeleton.Place, unitsPerTile: Double): HubFrame {
            val pt = hub.planTiles ?: error("hub ${hub.id} has no plan frame")
            val centre = WorldPos.ofCells(hub.x, hub.z)
            val origin = centre.plus(-pt.cx * pt.tileM, -pt.cy * pt.tileM)
            val ring = pt.ring * unitsPerTile
            val ground = floatArrayOf((-ring).toFloat(), (-ring).toFloat(), (pt.w * unitsPerTile + ring).toFloat(), (pt.h * unitsPerTile + ring).toFloat())
            return HubFrame(hub.id, origin, unitsPerTile, pt.tileM, sk.elev(floor(hub.x).toInt(), floor(hub.z).toInt()).toDouble(), ground)
        }
    }
}

/**
 * The render origin (WORLD_GEN.md 1, "Camera-relative rendering with a render origin"): the one [WorldPos] Filament's
 * world is measured from. Every chunk, region tile and town is an entity whose root transform is its own origin minus
 * this (computed in doubles, wrapped, then turned into floats and street units: [toRender]); vertices stay in the
 * mesh's own local frame.
 *
 * [update] with the player each frame: inside a town (within [TOWN_KEEP_M] of its centre) the origin is the town's own,
 * so the town's code runs unchanged in its street units; out in the country, once the player is more than [RECENTRE_M]
 * from the origin, the origin moves to the corner of the player's chunk and [generation] counts up (the layers reset
 * their root transforms between frames). Wrapping east-west is the same operation: there is no seam.
 */
class RenderOrigin(val unitsPerMetre: Double, start: WorldPos) {
    var origin: WorldPos = start; private set
    /** Counts the moves (a layer whose transforms were set for an older one sets them again). */
    var generation = 0; private set
    /** The hub whose frame the origin is now (null: out in the country). */
    var town: HubFrame? = null; private set
    private val towns = ArrayList<HubFrame>()
    private val centres = ArrayList<WorldPos>()

    /** A hub whose frame the origin takes while the player is in it. */
    fun addTown(f: HubFrame) {
        towns += f
        centres += f.origin.plus((f.ground[0] + f.ground[2]) / 2.0 / f.unitsPerMetre, (f.ground[1] + f.ground[3]) / 2.0 / f.unitsPerMetre)
    }

    /** Follows the player at [p]; true if the origin moved (every root transform must be set again). */
    fun update(p: WorldPos): Boolean {
        for (k in towns.indices) if (centres[k].distance(p) < TOWN_KEEP_M) {
            val f = towns[k]
            if (town === f) return false
            town = f
            return moveTo(f.origin)
        }
        if (town == null && origin.distance(p) <= RECENTRE_M) return false
        town = null
        return moveTo(WorldPos(floor(p.x / Planet.CHUNK_M) * Planet.CHUNK_M, floor(p.z / Planet.CHUNK_M) * Planet.CHUNK_M))
    }

    private fun moveTo(o: WorldPos): Boolean {
        if (o == origin) return false
        origin = o; generation++
        return true
    }

    /** [p] relative to the origin in street units (x east, z south; [out] gets x, z): an entity's root translation. */
    fun toRender(p: WorldPos, out: FloatArray = FloatArray(2)): FloatArray {
        out[0] = (origin.dx(p) * unitsPerMetre).toFloat()
        out[1] = (origin.dz(p) * unitsPerMetre).toFloat()
        return out
    }

    /** The world position of render position ([rx], [rz]) (street units from the origin). */
    fun toWorld(rx: Double, rz: Double): WorldPos = origin.plus(rx / unitsPerMetre, rz / unitsPerMetre)

    companion object {
        /** Out in the country the origin moves once the player is this far from it (m). */
        const val RECENTRE_M = 2000.0
        /** Within this of a town's centre (m) the origin is the town's own frame. */
        const val TOWN_KEEP_M = 3000.0
    }
}

/**
 * A saved position (WORLD_GEN.md 1, "Saved positions"): the [WorldPos] in doubles plus the hub the player is in (if any)
 * and the hub-local tile position, so a save never depends on the render origin. [encode]/[decode] as one line of text
 * for the app's preferences.
 */
data class WorldSave(val pos: WorldPos, val hub: String?, val tileX: Double, val tileZ: Double) {
    fun encode(): String = "w1;${pos.x};${pos.z};${hub ?: ""};$tileX;$tileZ"

    companion object {
        fun decode(s: String?): WorldSave? {
            val p = s?.split(';') ?: return null
            if (p.size != 6 || p[0] != "w1") return null
            return runCatching { WorldSave(WorldPos(p[1].toDouble(), p[2].toDouble()), p[3].ifEmpty { null }, p[4].toDouble(), p[5].toDouble()) }.getOrNull()
        }

        /** The save of street position ([sx], [sz]) in [frame]'s town. */
        fun inTown(frame: HubFrame, sx: Double, sz: Double): WorldSave = WorldSave(frame.world(sx, sz), frame.hubId, frame.tileX(sx), frame.tileZ(sz))
    }
}

/**
 * A town placed on the planet (WORLD_GEN.md phase W4): the baked [skeleton], the town's hub and its [frame] there (gamma
 * is the capital, `h:capital`, whose plan is "gamma"), and the app's [origin] (the town's own frame while the player is
 * in it). The town's code keeps its street units; this answers where on the planet a street position is, saves
 * positions in world terms, and plans the far view round the town.
 */
class WorldSite(val skeleton: WorldSkeleton, val hub: WorldSkeleton.Place, val frame: HubFrame) {
    val origin = RenderOrigin(frame.unitsPerMetre, frame.origin).also { it.addTown(frame); it.update(frame.world((frame.ground[0] + frame.ground[2]) / 2.0, (frame.ground[1] + frame.ground[3]) / 2.0)) }

    /** The planet position of the town's street position ([sx], [sz]). */
    fun world(sx: Float, sz: Float): WorldPos = frame.world(sx.toDouble(), sz.toDouble())
    fun save(sx: Float, sz: Float): WorldSave = WorldSave.inTown(frame, sx.toDouble(), sz.toDouble())
    /** A save's street position in this town (null: it was made elsewhere on the planet). */
    fun restore(s: WorldSave): FloatArray? {
        if (s.hub != null && s.hub != frame.hubId) return null
        if (!frame.near(s.pos, 3000.0)) return null
        return floatArrayOf(frame.streetX(s.pos).toFloat(), frame.streetZ(s.pos).toFloat())
    }
    fun plan(year: Int, centre: WorldPos? = null): WorldFarPlan = WorldFarPlan(skeleton, frame.origin, frame, year, centre)
    /** One line for the breadcrumbs. */
    val report: String get() = "world ${skeleton.seed} v${skeleton.version}: ${hub.name} (${hub.id}) at %.3f, %.3f km, %.0f m up; %d places, %d roads, %d rail lines, %d rivers".format(
        java.util.Locale.US, frame.origin.x / 1000, frame.origin.z / 1000, frame.elevM, skeleton.places.size, skeleton.roads.size, skeleton.railLines.size, skeleton.rivers.size)

    companion object {
        /** The asset path of a world's skeleton (assets/world/<seed>/; the repository's content/world/<seed>/). */
        fun asset(seed: String) = "world/$seed/skeleton.twsk.gz"

        /** Town [townSeed] (a hub's plan) placed on [sk], at street scale [unitsPerTile]; null if no hub has that plan. */
        fun of(sk: WorldSkeleton, townSeed: String, unitsPerTile: Double): WorldSite? {
            val hub = sk.places.firstOrNull { it.hub && it.plan == townSeed && it.planTiles != null } ?: return null
            WorldMemory.skeleton = sk.gridBytes; WorldMemory.facts = sk.factsBytes
            return WorldSite(sk, hub, HubFrame.of(sk, hub, unitsPerTile))
        }
    }
}
