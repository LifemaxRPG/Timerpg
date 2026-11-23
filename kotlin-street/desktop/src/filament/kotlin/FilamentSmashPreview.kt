import com.lifemax.rpg.feature.habits.domain.SolidKind
import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun
import com.lifemax.rpg.feature.habits.vehicle.VehicleSpots
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The cars' destruction in the capital (-Dmode=filament-open -Dcars=smash; docs/VEHICLES.md "Destruction"): the 1956 sedan
 * set on the road square to a front yard's picket fence and driven through it at full throttle (before, at the break with
 * the panels flying, after with them lying in the yard), then along the pavement into a street lamp (as it snaps and
 * falls, and lying after). Writes build/preview/filament-smash/ and smash.txt (the breaks, speeds, pieces). Desktop
 * renders (Mesa's software GL), not S25 captures.
 */
internal fun renderSmashShots(out: File, ft: FilamentTown, town: Town, yards: TownYards, collision: TownCollision, walk: OpenWalk, run: TownRun, shooter: Shooter,
                              w: Int, h: Int, cars: VehicleWorld) {
    val dir = File(out, "filament-smash").also { it.mkdirs() }
    val u = VehicleWorld.U
    val report = StringBuilder()
    val events = ArrayList<String>()
    cars.onEvent = { events += it; println("  $it") }
    val sheets = SpriteSheets.sheets
    var clock = 900f
    /** The foot brake until it is still (the stick's down; held on it would reverse). */
    fun stop() { var g = 0; while ((cars.driven?.forwardSpeed ?: 0f) > .3f && g++ < 600) { GameState.moveLane = 1f; TownPlay.step(1f / 60f) }; GameState.moveLane = 0f }
    /** Where the newest piece lies (street units), and how upright it is. */
    fun newest(): FloatArray { val q = FloatArray(7); cars.wreck.pieces.lastOrNull()?.read(q); return q }
    fun describePieces(): String = cars.wreck.pieces.joinToString("; ") { p -> val q = FloatArray(7); p.read(q)
        "%s %s at (%.0f, %.1f m up, %.0f) up %.2f%s".format(p.look.source, p.look.kind, q[0], q[1] / u, q[2], 1f - 2f * (q[3] * q[3] + q[5] * q[5]), if (p.frozen) " frozen" else "") }
    fun settle() { while (ft.pending > 0) { if (ft.buildNearest(walk.wx, walk.wz, 8f) == 0) Thread.sleep(2) } }
    fun shoot(name: String, what: String, lookX: Float, lookZ: Float, yaw: Float, pitch: Float, dist: Float) {
        settle()
        val cx = walk.wx; val cz = walk.wz
        // (the camera on the place, not the car: the walk stood there for the picture, carried by the car again after)
        walk.carry(lookX, lookZ, 0f, 1f, 0f)
        OrbitCamera.set(yaw, pitch, dist)
        OrbitCamera.follow(lookX, lookZ)
        for (k in 0..2) { ft.prepare(run.orbit(walk, w.toFloat(), h.toFloat(), clock)); OrbitCamera.set(yaw, pitch, dist); OrbitCamera.follow(lookX, lookZ); run.draw(walk, w.toFloat(), h.toFloat(), 13f, clock, sheets); clock += 1f / 60f }
        shooter.save(File(dir, "$name.png"), w, h, 13f, skyRow = skyRow(13f, h))
        walk.carry(cx, cz, 0f, 1f, 0f)
        report.appendLine("%-18s %s; %d pieces (%d live)".format(name, what, cars.wreck.pieces.size, cars.wreck.live))
        println("  $name")
    }
    /** Whether a car-wide path from (x0, z0) to (x1, z1) (street units) is clear of everything but shape [except]. */
    fun clear(x0: Float, z0: Float, x1: Float, z1: Float, except: Int, strict: Boolean = false): Boolean {
        val n = (hypot(x1 - x0, z1 - z0) / 6f).toInt().coerceAtLeast(2)
        val dx = (x1 - x0) / hypot(x1 - x0, z1 - z0); val dz = (z1 - z0) / hypot(x1 - x0, z1 - z0)
        for (k in 0..n) {
            val x = x0 + (x1 - x0) * k / n; val z = z0 + (z1 - z0) * k / n
            for (s in floatArrayOf(-1f, 0f, 1f)) {
                val px = x - dz * s * 1.1f * u; val pz = z + dx * s * 1.1f * u
                val i = collision.nearest(px, pz, 1.2f * u)
                // (only what would stop it: a mailbox or a bin on the way may go too)
                if (i >= 0 && i != except && (strict || com.lifemax.rpg.feature.habits.vehicle.WreckMaterial.of(collision.kind(i), collision.detail(i)).give == com.lifemax.rpg.feature.habits.vehicle.Give.SOLID)) return false
            }
        }
        return true
    }
    val sedan = cars.cars.firstOrNull { it.spec.key.startsWith("northcairn") } ?: cars.cars.first()
    val spec = sedan.spec
    val tmp = FloatArray(6)

    // ---- the picket fence: a front yard's, square across the road from a kerbside spot, a clear run to it and a clear yard behind
    data class Run(val x: Float, val z: Float, val fx: Float, val fz: Float, val tx: Float, val tz: Float)
    var fenceRun: Run? = null
    val sp = town.row(town.spawn.row).pointAt(town.spawn.x, town.spawn.lane)
    val spots = VehicleSpots.street(town, sp.x, sp.z, collision)
    // (from each front fence out either way: the first point on the road (within 3 m of a kerbside spot), then on across
    // the road for a longer run; nothing at all in the way, and 7 m of yard behind clear of anything solid)
    val fences = ArrayList<Int>()
    for (i in 0 until collision.size) if (collision.detail(i)?.startsWith("frontFence") == true && !collision.isCircle(i)) fences += i
    fences.sortBy { collision.shape(it, tmp); hypot(tmp[0] - sp.x, tmp[1] - sp.z) }
    search@ for (i in fences) {
        collision.shape(i, tmp)
        val cx = tmp[0]; val cz = tmp[1]
        val alongU = tmp[4] >= tmp[5]
        val ax = if (alongU) -tmp[3] else tmp[2]; val az = if (alongU) tmp[2] else tmp[3]
        if (maxOf(tmp[4], tmp[5]) < 5f * u) continue
        for (side in floatArrayOf(1f, -1f)) {
            val nx = ax * side; val nz = az * side
            var road = -1f
            var d = 2f
            while (d < 12f) { val px = cx + nx * d * u; val pz = cz + nz * d * u; if (spots.any { hypot(it.x - px, it.z - pz) < 3f * u }) { road = d; break }; d += .5f }
            if (road < 0f) continue
            // (as far across the road as stays clear, up to 9 m past the kerb)
            var run = -1f
            for (extra in floatArrayOf(9f, 7f, 5f, 3f)) {
                val r = road + extra
                if (clear(cx + nx * r * u, cz + nz * r * u, cx + nx * 1f * u, cz + nz * 1f * u, i, strict = true)) { run = r; break }
            }
            if (run < 0f) continue
            if (!clear(cx - nx * 1f * u, cz - nz * 1f * u, cx - nx * 7f * u, cz - nz * 7f * u, i)) continue
            fenceRun = Run(cx + nx * run * u, cz + nz * run * u, -nx, -nz, cx, cz); break@search
        }
    }
    val fr = fenceRun ?: error("no front fence square across a road")
    report.appendLine("the fence at (%.0f, %.0f), the car from (%.0f, %.0f) heading (%.2f, %.2f)".format(fr.tx, fr.tz, fr.x, fr.z, fr.fx, fr.fz))
    val car = cars.add(spec, sedan.colour, sedan.classic, VehicleSpots.Spot("smash", fr.x, fr.z, fr.fx, fr.fz, 8f * u))
    walk.placeAt(car.x, car.z, car.fx, car.fz)
    GameState.moveX = 0f; GameState.moveLane = 0f
    check(cars.enter(car, walk))
    repeat(80) { TownPlay.step(1f / 60f) }
    check(cars.driving)
    // the camera behind the car and to its side, looking past it at the fence
    fun turned(dx: Float, dz: Float, deg: Float): Float { val a = Math.toRadians(deg.toDouble()).toFloat(); return OrbitCamera.headingOf(dx * cos(a) - dz * sin(a), dx * sin(a) + dz * cos(a)) }
    val side = turned(fr.fx, fr.fz, 35f)
    shoot("smash_1_before", "the ${spec.name} on the road, square to a picket fence %.0f m on".format(hypot(car.x - fr.tx, car.z - fr.tz) / u), (car.x + fr.tx) / 2f, (car.z + fr.tz) / 2f, side, 22f, 300f)
    GameState.moveLane = -1f
    var t = 0f
    val before = events.size
    while (events.size == before && t < 6f) { TownPlay.step(1f / 60f); t += 1f / 60f }
    val vHit = car.forwardSpeed
    repeat(12) { TownPlay.step(1f / 60f) }
    shoot("smash_2_through", "through the fence at %.1f m/s (%.0f km/h), the panels flying".format(vHit, vHit * 3.6f), fr.tx, fr.tz, turned(fr.fx, fr.fz, 70f), 16f, 230f)
    // (on into the yard a little, then the foot brake)
    repeat(20) { TownPlay.step(1f / 60f) }
    stop()
    repeat(120) { TownPlay.step(1f / 60f) }
    report.appendLine("pieces: " + describePieces())
    report.appendLine("through the fence at %.1f m/s; the car stopped %.1f m past it".format(vHit, hypot(car.x - fr.tx, car.z - fr.tz) / u))
    shoot("smash_3_after", "after: the gap, the panels lying in the yard, the car stopped", fr.tx, fr.tz, turned(fr.fx, fr.fz, 25f), 32f, 260f)
    shoot("smash_4_gap", "the gap from the road, close", fr.tx, fr.tz, turned(fr.fx, fr.fz, -30f), 38f, 150f)

    // ---- a street lamp: the car on the pavement 18 m back, driven into it
    var g = 0
    while (!cars.canExit && g++ < 600) TownPlay.step(1f / 60f)
    check(cars.exit())
    repeat(90) { TownPlay.step(1f / 60f) }
    var lampRun: Run? = null
    val lamps = ArrayList<Triple<Int, Float, Float>>()
    collision.forEachNearDetail(collision.minX, collision.minZ, collision.maxX, collision.maxZ, tmp) { i, _, _, detail, _, s -> if (detail == "lamp") lamps += Triple(i, s[0], s[1]) }
    lamps.sortBy { hypot(it.second - sp.x, it.third - sp.z) }
    // (along the street, from the road a little off the pavement's line: the run in straight at the post)
    outer@ for ((i, lx, lz) in lamps.take(60)) {
        val near = spots.minByOrNull { hypot(it.x - lx, it.z - lz) } ?: continue
        for (sgn in floatArrayOf(1f, -1f)) for (off in floatArrayOf(0f, 1.5f, 3f, -1.5f, 4.5f)) {
            val ax = near.fx * sgn; val az = near.fz * sgn
            // (the start 18 m back along the street, [off] m out across it)
            val sx = lx - ax * 18f * u + az * off * u; val sz = lz - az * 18f * u - ax * off * u
            val l = hypot(lx - sx, lz - sz); val dx = (lx - sx) / l; val dz = (lz - sz) / l
            if (clear(sx, sz, lx - dx * 1.2f * u, lz - dz * 1.2f * u, i) && clear(lx + dx * 1.2f * u, lz + dz * 1.2f * u, lx + dx * 6f * u, lz + dz * 6f * u, i)) {
                lampRun = Run(sx, sz, dx, dz, lx, lz); break@outer
            }
        }
    }
    val lr = lampRun ?: error("no street lamp with a clear run to it")
    report.appendLine("the lamp at (%.0f, %.0f), the car from (%.0f, %.0f) heading (%.2f, %.2f)".format(lr.tx, lr.tz, lr.x, lr.z, lr.fx, lr.fz))
    val car2 = cars.add(spec, spec.colours.last(), sedan.classic, VehicleSpots.Spot("smash2", lr.x, lr.z, lr.fx, lr.fz, 8f * u))
    walk.placeAt(car2.x, car2.z, car2.fx, car2.fz)
    check(cars.enter(car2, walk))
    repeat(80) { TownPlay.step(1f / 60f) }
    // (looking along the street from behind the car: a side view would be into the shops)
    val side2 = turned(lr.fx, lr.fz, 20f)
    shoot("lamp_1_before", "a street lamp 18 m on", (car2.x + lr.tx) / 2f, (car2.z + lr.tz) / 2f, side2, 24f, 260f)
    GameState.moveLane = -1f
    val before2 = events.size
    t = 0f
    while (events.size == before2 && t < 6f) {
        TownPlay.step(1f / 60f); t += 1f / 60f
    }
    val vLamp = car2.forwardSpeed
    repeat(15) { TownPlay.step(1f / 60f) }
    GameState.moveLane = 0f
    val mid = newest()
    shoot("lamp_2_falling", "the lamp hit at %.1f m/s, snapped and falling".format(vLamp), mid[0], mid[2], turned(lr.fx, lr.fz, 15f), 30f, 220f)
    stop()
    repeat(150) { TownPlay.step(1f / 60f) }
    val lay = newest()
    report.appendLine("pieces: " + describePieces())
    shoot("lamp_3_after", "after: the lamp post lying where it fell", lay[0], lay[2], turned(lr.fx, lr.fz, 130f), 34f, 200f)
    report.appendLine("events:\n  " + events.joinToString("\n  "))
    File(dir, "smash.txt").writeText(report.toString() + ft.extraReport.joinToString("\n") + "\n(desktop renders, Mesa software GL; not S25 captures)\n")
    print(report)
}
