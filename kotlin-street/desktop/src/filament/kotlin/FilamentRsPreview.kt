import com.lifemax.rpg.feature.habits.domain.Town
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

/**
 * The race Torrel's review sheet (C046, -Dmode=filament-open -Dcars=rs): in the capital, the race Torrel (the Halvane
 * stripes) parked at the kerb nose to tail with the road Torrel, then each shot the brief asks for: the two side by
 * side in profile (the player standing by them for scale), a low front three-quarter hero shot, the wing from behind,
 * the cabin through the open driver's door (the cage, the bucket seat), from above, the other livery (Ostrand) from its
 * rear three-quarter, and at night with its lamps lit. Writes build/preview/filament-rs/ and rs.txt. Desktop renders
 * (Mesa's software GL), not S25 captures.
 */
internal fun renderRsShots(out: File, ft: FilamentTown, town: Town, yards: TownYards, walk: OpenWalk, run: TownRun, shooter: Shooter, w: Int, h: Int, cars: VehicleWorld) {
    val dir = File(out, "filament-rs").also { it.mkdirs() }
    val report = StringBuilder()
    val rs = cars.specs.first { it.key == "torrel-1988-rs" }
    val road = cars.specs.first { it.key == "torrel-1988" }
    val U = VehicleWorld.U
    // (the town's parked cars towed away first: nothing in front of the two)
    for (c in cars.cars.toList()) cars.retire(c)
    repeat(2) { TownPlay.step(1f / 60f) }
    // the spots: the clear kerb nearest the player for the race car, the next clear one for the road car
    val sp1 = cars.spawnSpot(town, rs, walk.wx, walk.wz, yards) ?: error("no clear kerb for the race Torrel")
    val race = cars.add(rs, rs.liveries.first().colour, false, VehicleSpots.Spot("rs:" + sp1.name, sp1.x, sp1.z, sp1.fx, sp1.fz, sp1.length))
    // (the road car right behind it along the same kerb: both in profile together)
    val back = 5.2f * U
    val roadCar = cars.add(road, road.colours[0], false, VehicleSpots.Spot("road:" + sp1.name, sp1.x - sp1.fx * back, sp1.z - sp1.fz * back, sp1.fx, sp1.fz, sp1.length))
    repeat(90) { TownPlay.step(1f / 60f) }
    while (ft.pending > 0) { if (ft.buildNearest(race.x, race.z, 8f) == 0) Thread.sleep(2) }
    report.appendLine("race Torrel at (%.0f, %.0f), road Torrel at (%.0f, %.0f)".format(race.x, race.z, roadCar.x, roadCar.z))
    val sheets = SpriteSheets.sheets
    var clock = 900f
    fun shoot(name: String, hour: Float, what: String) {
        for (k in 0..2) { ft.prepare(run.orbit(walk, w.toFloat(), h.toFloat(), clock)); run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets); clock += 1f / 60f }
        shooter.save(File(dir, "$name.png"), w, h, hour, skyRow = skyRow(hour, h))
        report.appendLine("%-18s %s; %s".format(name, what, OrbitCamera.describe()))
        println("  $name")
    }
    fun look(c: VehicleWorld.Car, side: Float, fwd: Float): Float {
        // a look direction from the car's [side] (+1 its left) and [fwd] (+1 its front), towards it
        val lx = c.fz; val lz = -c.fx
        return OrbitCamera.headingOf(-lx * side - c.fx * fwd, -lz * side - c.fz * fwd)
    }
    fun focus(x: Float, z: Float, c: VehicleWorld.Car) { walk.carry(x, z, c.fx, c.fz, 0f); OrbitCamera.follow(x, z) }
    // 1. the two side by side in profile, from their left (the street side); the player stands by the race car's nose
    val mx = (race.x + roadCar.x) / 2f; val mz = (race.z + roadCar.z) / 2f
    val lx = race.fz; val lz = -race.fx
    walk.placeAt(race.x + race.fx * 2.9f * U + lx * .4f * U, race.z + race.fz * 2.9f * U + lz * .4f * U, -race.fx, -race.fz)
    OrbitCamera.set(look(race, 1f, 0f), 10f, 280f); OrbitCamera.follow(mx, mz)
    repeat(3) { TownPlay.step(1f / 60f) }
    shoot("rs_vs_road_side", 13f, "the race Torrel (front) and the road Torrel in profile, the player for scale")
    // 2. the hero: low, front three-quarter
    focus(race.x, race.z, race)
    OrbitCamera.set(look(race, .55f, .85f), 10f, 140f)
    shoot("rs_hero", 15.5f, "low front three-quarter")
    // 3. the wing from behind, a little above
    OrbitCamera.set(look(race, .12f, -1f), 20f, 150f)
    shoot("rs_wing_behind", 13f, "the wing from behind")
    // 4. from above (the stripes over the car, the wing, the arches)
    OrbitCamera.set(look(race, .5f, .3f), 60f, 170f)
    shoot("rs_top", 13f, "from above")
    // 5. the cabin through the open driver's door: the cage, the bucket seat and its harness
    val ex = race.spec.node("exit_left")!!
    val p = FloatArray(2)
    race.world(ex.x, ex.z, p)
    walk.placeAt(p[0], p[1], race.fx, race.fz)
    GameState.moveX = 0f; GameState.moveLane = 0f
    TownPlay.step(1f / 60f)
    check(cars.enterable === race) { "the race Torrel's door is not in reach at its exit" }
    cars.enter(race, walk)
    repeat(24) { TownPlay.step(1f / 60f) }
    OrbitCamera.set(look(race, 1f, .15f), 42f, 140f); OrbitCamera.follow(race.x + lx * .9f * U, race.z + lz * .9f * U)
    shoot("rs_cabin_door", 13f, "the cabin through the open driver's door: cage, bucket seat, harness")
    repeat(60) { TownPlay.step(1f / 60f) }
    // 6. at night, its lamps lit, from its front three-quarter (in it, the engine running)
    cars.lights(1f)
    OrbitCamera.set(look(race, .6f, .8f), 14f, 170f); OrbitCamera.follow(race.x, race.z)
    repeat(4) { TownPlay.step(1f / 60f) }
    shoot("rs_night", 22.5f, "at night, its lamps lit")
    cars.exit()
    repeat(120) { TownPlay.step(1f / 60f) }
    // 7. the other livery: an Ostrand race Torrel ahead of the first, from its rear three-quarter
    // (at the next clear kerb near the first)
    val sp2 = cars.spawnSpot(town, rs, race.x, race.z, yards) ?: error("no second clear kerb")
    val b = cars.add(rs, rs.liveries[1].colour, false, VehicleSpots.Spot("rsb:" + sp2.name, sp2.x, sp2.z, sp2.fx, sp2.fz, sp2.length))
    report.appendLine("cars now: " + cars.cars.joinToString { it.spec.key + "/" + Integer.toHexString(it.colour) + " (%.0f, %.0f)".format(it.x, it.z) })
    // (its model built on the renderer's pool before the shot)
    repeat(30) { TownPlay.step(1f / 60f) }
    while (ft.pending > 0) { if (ft.buildNearest(b.x, b.z, 8f) == 0) Thread.sleep(2) }
    report.appendLine("Ostrand car settled at (%.0f, %.0f)".format(b.x, b.z))
    focus(b.x, b.z, b)
    OrbitCamera.set(look(b, .75f, -.7f), 14f, 160f)
    shoot("rs_ostrand_rear", 13f, "the Ostrand livery from its rear three-quarter")
    OrbitCamera.set(look(b, .6f, .8f), 11f, 150f)
    shoot("rs_ostrand_front", 15.5f, "the Ostrand livery from its front left")
    File(dir, "rs.txt").writeText(report.toString() + "(desktop renders, Mesa software GL; not S25 captures)\n")
    print(report)
}
