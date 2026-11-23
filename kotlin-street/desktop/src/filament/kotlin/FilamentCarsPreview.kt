import com.lifemax.rpg.feature.habits.domain.Town
import com.lifemax.rpg.feature.habits.domain.TownCollision
import com.lifemax.rpg.feature.habits.domain.TownYards
import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OpenWalk
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.SpriteSheets
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.presentation.scene3d.FilamentTown
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownCars
import com.lifemax.rpg.feature.habits.presentation.scene3d.TownRun
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import java.io.File

/**
 * The cars in the capital (-Dmode=filament-open -Dcars=1; docs/VEHICLES.md V1): the town as the open world draws it, the
 * cars parked for the year (-DcarYear=YEAR, else the town's) and drawn by [TownCars], then: each car where it is parked,
 * by day, from three-quarters above; the 1950s sedan got into (its door open) and driven down Main Street through the
 * game's own loop ([TownPlay.step]: the stick's up is the gas), seen from the follow camera; the same at night, its
 * headlamps lit and lighting the road. Writes build/preview/filament-cars/ and cars.txt (positions, the physics' step).
 * Desktop renders (Mesa's software GL), not S25 captures.
 */
internal fun renderCarShots(out: File, ft: FilamentTown, town: Town, yards: TownYards, collision: TownCollision, walk: OpenWalk, run: TownRun, shooter: Shooter, w: Int, h: Int) {
    val dir = File(out, "filament-cars").also { it.mkdirs() }
    val assets = File("../app/src/main/assets/vehicles")
    val year = System.getProperty("carYear")?.toIntOrNull() ?: town.year
    val cars = VehicleWorld.start(town, collision, yards, { p -> File(assets, p).takeIf { it.exists() }?.readText() }, year)
        ?: error("no cars: the physics library did not load (gradle :desktop:physicsHost)")
    TownPlay.cars = cars
    TownCars.attach(ft, { TownPlay.cars }) { p -> File(assets, p).takeIf { it.exists() }?.readBytes() }
    while (ft.pending > 0) { if (ft.buildNearest(walk.wx, walk.wz, 8f) == 0) Thread.sleep(2) }
    // (-Dcars=smash: driving through a picket fence and into a street lamp instead: FilamentSmashPreview.kt)
    if (System.getProperty("cars") == "smash") { renderSmashShots(out, ft, town, yards, collision, walk, run, shooter, w, h, cars); return }
    val report = StringBuilder()
    report.appendLine("cars for $year: " + cars.cars.joinToString("; ") { c -> "${c.spec.name} (${c.spec.key}) at ${c.spot} (%.0f, %.0f)%s".format(c.x, c.z, if (c.classic) ", classic" else "") })
    val sheets = SpriteSheets.sheets
    var clock = 900f
    fun shoot(name: String, hour: Float, what: String) {
        // a few frames, so the camera's easing and the cars' poses settle into the picture
        for (k in 0..2) { ft.prepare(run.orbit(walk, w.toFloat(), h.toFloat(), clock)); run.draw(walk, w.toFloat(), h.toFloat(), hour, clock, sheets); clock += 1f / 60f }
        shooter.save(File(dir, "$name.png"), w, h, hour, skyRow = skyRow(hour, h))
        report.appendLine("%-22s %s; player (%.0f, %.0f) %s".format(name, what, walk.wx, walk.wz, OrbitCamera.describe()))
        println("  $name")
    }
    // each car where it is parked, the camera three-quarters from its front left (the street side of a kerbside car),
    // centred on it (the walk stood in it unseen, as while driving)
    for ((i, c) in cars.cars.withIndex()) {
        walk.carry(c.x, c.z, c.fx, c.fz, 0f)
        // (looking from the car's front left towards its rear right: the left is +x of the model, (fz, -fx) on the ground)
        val lx = c.fz; val lz = -c.fx
        // (mostly along the street, which is too narrow to stand far out to the side: the camera would be in the houses)
        OrbitCamera.set(OrbitCamera.headingOf(-lx * .42f - c.fx * .9f, -lz * .42f - c.fz * .9f), 20f, 210f)
        OrbitCamera.follow(walk.wx, walk.wz)
        shoot("parked_${i}_${c.spec.key}", 13f, "${c.spec.name} parked at ${c.spot}")
        walk.release(c.x + lx * 3f * VehicleWorld.U, c.z + lz * 3f * VehicleWorld.U, c.fx, c.fz)
    }
    // the sedan: got into (its door swinging), then driven down Main Street, the follow camera behind it
    val sedan = cars.cars.firstOrNull { it.spec.key.startsWith("northcairn") } ?: cars.cars.first()
    val ex = sedan.spec.node("exit_left")!!
    val p = FloatArray(2)
    sedan.world(ex.x, ex.z, p)
    walk.placeAt(p[0], p[1], sedan.fx, sedan.fz)
    GameState.moveX = 0f; GameState.moveLane = 0f
    TownPlay.step(1f / 60f)
    check(cars.enterable === sedan) { "the sedan's door is not in reach at its exit" }
    cars.enter(sedan, walk)
    val heading = OrbitCamera.headingOf(sedan.fx, sedan.fz)
    // (from its front left: the driver's door swinging out towards the camera)
    OrbitCamera.set(OrbitCamera.headingOf(-sedan.fz * .5f - sedan.fx * .86f, sedan.fx * .5f - sedan.fz * .86f), 18f, 150f)
    repeat(20) { TownPlay.step(1f / 60f) }
    shoot("enter_door_open", 13f, "getting into the ${sedan.spec.name}: its driver's door open")
    repeat(60) { TownPlay.step(1f / 60f) }
    check(cars.driving)
    // the drive: the gas (the stick's up) for 5 s down Main Street; the camera follows behind (the bike's follow)
    OrbitCamera.set(heading, 14f, 260f)
    val x0 = sedan.x; val z0 = sedan.z
    GameState.moveLane = -.8f
    var t = 0f
    while (t < 5f) { TownPlay.step(1f / 60f); run.orbit(walk, w.toFloat(), h.toFloat(), clock); clock += 1f / 60f; t += 1f / 60f }
    report.appendLine("driven 5 s: %.0f m at %.0f km/h; physics step %.3f ms (this server), %d statics streamed, %d bodies".format(
        kotlin.math.hypot(sedan.x - x0, sedan.z - z0) / VehicleWorld.U, sedan.forwardSpeed * 3.6f, cars.stepMs, cars.staticCount, cars.physics.bodies))
    shoot("drive_main_street", 13f, "the ${sedan.spec.name} driving down Main Street, the follow camera behind")
    // night: on along the street, the headlamps lit
    var t2 = 0f
    while (t2 < 1.5f) { TownPlay.step(1f / 60f); run.orbit(walk, w.toFloat(), h.toFloat(), clock); clock += 1f / 60f; t2 += 1f / 60f }
    shoot("drive_night_headlights", 22.5f, "the ${sedan.spec.name} at night, its headlamps on")
    // braking at night: the brake lamps from behind and a little above
    GameState.moveLane = .8f
    repeat(20) { TownPlay.step(1f / 60f) }
    OrbitCamera.set(OrbitCamera.headingOf(sedan.fx, sedan.fz) + 14f, 14f, 230f)
    shoot("night_braking", 22.5f, "braking at night: the brake lamps")
    GameState.moveLane = 0f
    File(dir, "cars.txt").writeText(report.toString() + ft.extraReport.joinToString("\n") + "\n(desktop renders, Mesa software GL; not S25 captures)\n")
    print(report)
}
