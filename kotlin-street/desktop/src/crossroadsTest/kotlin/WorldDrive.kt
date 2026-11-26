import com.lifemax.rpg.feature.habits.domain.Planet
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.WorldPos
import com.lifemax.rpg.feature.habits.domain.WorldRoute
import com.lifemax.rpg.feature.habits.domain.WorldSite
import com.lifemax.rpg.feature.habits.domain.WorldSkeleton
import java.io.File
import kotlin.math.hypot

/**
 * The world streamer's drive (docs/WORLD_STREAM.md): from the capital (gamma, on alpha) out along the roads to the next
 * town on its continent, the fastest way in the capital's year: the route the drive tests follow (WorldStreamTest's
 * physics, GpuWorldDriveTest's frames). The next town is the nearest hub or major town reachable by road at least
 * [MIN_KM] off; failing one, the nearest town.
 */
object WorldDrive {
    val sk: WorldSkeleton by lazy { File("../../content/world/alpha/skeleton.twsk.gz").inputStream().use { WorldSkeleton.read(it) } }
    val town: Town by lazy { Town.parse(File("../app/src/main/assets/town/gamma/street.json").readText())!! }
    val site: WorldSite by lazy { WorldSite.of(sk, "gamma", town.scale.toDouble())!! }

    class Drive(val to: WorldSkeleton.Place, val path: WorldRoute.Path, val start: WorldPos) {
        /** The points in the capital's street units (x, z pairs). */
        fun street(site: WorldSite): FloatArray {
            val f = site.frame
            val out = FloatArray(path.pts.size)
            for (k in 0 until path.pts.size / 2) {
                val p = WorldPos(Planet.wrapX(path.pts[k * 2]), path.pts[k * 2 + 1])
                out[k * 2] = f.streetX(p).toFloat(); out[k * 2 + 1] = f.streetZ(p).toFloat()
            }
            return out
        }
        val km: Double get() = path.lengthM / 1000
        override fun toString(): String = "to %s (%s %s) %.1f km by road (%.0f min at the roads' speeds; %s), %.1f km straight".format(java.util.Locale.US,
            to.name, to.kind, to.rank, km, path.seconds / 60, path.classes.groupingBy { it }.eachCount(), start.distance(WorldPos.ofCells(to.x, to.z)) / 1000)
    }

    val drive: Drive by lazy {
        val r = WorldRoute(sk, town.year)
        val cap = site.hub
        val pins = cap.pins.filter { it.kind == "highway" }.map { WorldPos.ofCells(it.x, it.z) }
        val here = WorldPos.ofCells(cap.x, cap.z)
        // (candidates by straight distance; the first reachable from a highway pin far enough by road)
        // (the next town: of the 15 towns, major towns and hubs nearest in a straight line, the quickest to reach by road)
        var best: Drive? = null
        for (p in sk.places.filter { it.id != cap.id && (it.kind == "hub" || it.rank == "major" || it.rank == "town") }.sortedBy { here.distance(WorldPos.ofCells(it.x, it.z)) }.take(15)) {
            val to = r.nearest(WorldPos.ofCells(p.x, p.z))
            for (pin in pins) {
                val path = r.fastest(r.nearest(pin), to) ?: continue
                if (path.lengthM < MIN_KM * 1000) continue
                if (best == null || path.seconds < best.path.seconds) best = Drive(p, path, pin)
            }
        }
        best ?: error("no town reachable from the capital by road")
    }

    const val MIN_KM = 15.0

    /**
     * The drive on from the first town (planet step 2, tools/towns-route.ts): to [TOUR]'s places in turn, each leg the
     * fastest by road in the capital's year (Evanvale to the village Roserdale, 20 km, then the town Chaley, 27 km).
     */
    val tour: List<Drive> by lazy {
        val r = WorldRoute(sk, town.year)
        var from = drive.to
        TOUR.map { n ->
            val p = sk.places.first { it.name == n }
            val a = WorldPos.ofCells(from.x, from.z)
            val path = r.fastest(r.nearest(a), r.nearest(WorldPos.ofCells(p.x, p.z))) ?: error("no road from ${from.name} to $n")
            from = p
            Drive(p, path, a)
        }
    }
    val TOUR = listOf("Roserdale", "Chaley")

    /** The whole drive (the capital to the first town, then [tour]) in the capital's street units (x, z pairs), as doubles. */
    fun whole(site: WorldSite): DoubleArray {
        val out = ArrayList<Double>()
        for (d in listOf(drive) + tour) { val s = d.street(site); for (v in s) out += v.toDouble() }
        return out.toDoubleArray()
    }

    /** The point [s] metres along [pts] (x, z pairs) and its heading (dx, dz unit): x, z, dx, dz. */
    fun along(pts: DoubleArray, s: Double, out: DoubleArray = DoubleArray(4)): DoubleArray {
        // (each polyline's running lengths kept, the stretch found by halving)
        val cum = synchronized(cums) { cums.getOrPut(pts) {
            val c = DoubleArray(pts.size / 2); for (k in 1 until c.size) c[k] = c[k - 1] + hypot(pts[k * 2] - pts[k * 2 - 2], pts[k * 2 + 1] - pts[k * 2 - 1]); c } }
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= s) lo = m else hi = m }
        // (a zero-length stretch: the next one with a length)
        var k = lo
        while (k < cum.size - 2 && cum[k + 1] - cum[k] < 1e-9) k++
        val dx = pts[k * 2 + 2] - pts[k * 2]; val dz = pts[k * 2 + 3] - pts[k * 2 + 1]
        val l = hypot(dx, dz).coerceAtLeast(1e-9)
        val t = ((s - cum[k]) / l).coerceIn(0.0, 1.0)
        out[0] = pts[k * 2] + dx * t; out[1] = pts[k * 2 + 1] + dz * t; out[2] = dx / l; out[3] = dz / l
        return out
    }
    private val cums = java.util.IdentityHashMap<DoubleArray, DoubleArray>()
}
