package com.timerpg.street.audio

import com.lifemax.rpg.feature.habits.presentation.scene.GameState
import com.lifemax.rpg.feature.habits.presentation.scene.OrbitCamera
import com.lifemax.rpg.feature.habits.presentation.scene.TownPlay
import com.lifemax.rpg.feature.habits.vehicle.VehicleWorld
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * What the sound reads from the game each frame (docs/SOUND.md "Where the sound comes from"): the listener (the player,
 * or the car they drive, and the camera's heading), the place round them (how green, how built up, the people near, the
 * water, the bell tower, the police out looking), every car's state for [CarAudio], the footsteps. Called from the game's
 * frame loop (GameActivity); the slower looks round (trees, water, people) only a few times a second.
 */
class SoundFeed(private val sound: SoundScape) {
    private var feet = GameState.footfalls
    private var lookAt = 0L
    private var place = SoundRules.Place()
    private var townSeen: Any? = null
    private var trees = FloatArray(0)
    private var shops = FloatArray(0)
    private var fountain: FloatArray? = null
    private val lastPhase = HashMap<VehicleWorld.Car, VehicleWorld.Phase>()
    private var lastSpeed = HashMap<VehicleWorld.Car, Float>()
    private var lastFrame = 0L
    private var stepSet = SoundSettings.footsteps

    /** One frame: [hour] 0..24, the sky's [rain] / [cloud] / [mist], the [season], the town's [kind] (its music), the [year]. */
    fun frame(hour: Float, rain: Float, cloud: Float, mist: Float, season: Int, kind: String?, year: Int) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceIn(1e-3f, .25f)
        lastFrame = now
        sound.apply(SoundSettings)
        if (SoundSettings.footsteps != stepSet) { stepSet = SoundSettings.footsteps; sound.prepareSteps(stepSet) }
        val walk = TownPlay.open
        val world = TownPlay.cars
        val driven = world?.driven
        // the listener: the player, the driven car (heard from it), the camera's heading
        val lx = driven?.x ?: walk?.wx ?: GameState.x
        val lz = driven?.z ?: walk?.wz ?: 0f
        sound.listener(lx, lz, OrbitCamera.yaw, driven?.vx ?: 0f, driven?.vz ?: 0f)
        // the place round them, a few times a second
        if (now - lookAt > 400_000_000L) { lookAt = now; place = look(lx, lz, year, world) }
        sound.set(SoundRules.at(hour, rain, cloud, mist, season, kind, place), hour)
        // the cars
        if (world != null) cars(world, dt)
        // the bicycle's tyres and freewheel follow the ride
        if (walk != null) {
            sound.bike.on = walk.riding && TownPlay.walk === walk
            sound.bike.speed = walk.bike.speed; sound.bike.pedalling = walk.bike.pedalling; sound.bike.wheelRadius = walk.bike.wheelRadius
            sound.bike.surface = if (season == 3 && GameState.surface != com.lifemax.rpg.feature.habits.presentation.scene.Surface.WOOD) com.lifemax.rpg.feature.habits.presentation.scene.Surface.GRASS else GameState.surface
        } else sound.bike.on = false
        // a footstep each time a foot is put down (the drawn legs' own footfalls), on what is underfoot
        if (GameState.footfalls != feet) {
            val fresh = GameState.footfalls - feet
            feet = GameState.footfalls
            if (fresh > 0 && TownPlay.cars?.driving != true) {
                sound.footstep(Footsteps.surfaceName(GameState.surface, GameState.indoors, GameState.carpet, season == 3), GameState.running)
            }
        }
    }

    /** Each car's feed; doors as they open and shut; a crash's thump. */
    private fun cars(w: VehicleWorld, dt: Float) {
        val driven = w.driven
        for (c in w.cars) {
            val f = sound.cars.feed(c) { EngineProfile.forSpec(c.spec) }
            f.x = c.x; f.z = c.z; f.vx = c.vx; f.vz = c.vz; f.speed = c.forwardSpeed
            f.rpm = c.rpm; f.gear = c.gear; f.throttle = c.throttle
            val mine = c === driven
            f.wantRun = mine && (w.phase == VehicleWorld.Phase.DRIVING)
            f.canRun = !w.engineOff
            // sliding: the velocity across the car's own forward; locking: braking hard at speed
            val lat = c.vx * (-c.fz) + c.vz * c.fx
            val was = lastSpeed[c] ?: c.forwardSpeed
            val decel = if (dt > 0f) (kotlin.math.abs(was) - kotlin.math.abs(c.forwardSpeed)) / dt else 0f
            lastSpeed[c] = c.forwardSpeed
            f.slip = CarAudio.slip(lat, c.forwardSpeed, c.braking, decel, c.throttle, c.gear, if (f.profile.redline > 0f) c.rpm / f.profile.redline else 0f)
            f.surface = if (mine) Footsteps.surfaceName(GameState.surface, false, false, false) else "asphalt"
            f.seenNs = System.nanoTime()
        }
        // the doors: getting in (open, then shut as they sit) and out
        val ph = w.phase
        val car = driven ?: lastPhase.keys.firstOrNull()
        if (car != null) {
            val before = lastPhase[car]
            if (before != ph) {
                if (ph == VehicleWorld.Phase.ENTERING || ph == VehicleWorld.Phase.EXITING) sound.carDoor(car.x, car.z, true)
                if ((before == VehicleWorld.Phase.ENTERING && ph == VehicleWorld.Phase.DRIVING) || (before == VehicleWorld.Phase.EXITING && ph == VehicleWorld.Phase.ON_FOOT)) sound.carDoor(car.x, car.z, false)
                lastPhase.clear(); lastPhase[car] = ph
            }
        }
        sound.cars.sweep(5_000_000_000L)
        if (lastSpeed.size > 64) lastSpeed.keys.retainAll(w.cars.toSet())
    }

    /** A hard stop of the driven car (VehicleWorld.onImpact): its crash heard where it is. */
    fun impact(drop: Float) { TownPlay.cars?.driven?.let { sound.impact(it.x, it.z, drop) } }

    /** The place round ([x], [z]): its green and its shops, the people, the water, the bell, the police. */
    private fun look(x: Float, z: Float, year: Int, w: VehicleWorld?): SoundRules.Place {
        val town = TownPlay.town
        if (town != null && town !== townSeen) {
            townSeen = town
            trees = town.props.filter { it.kind == "tree" || it.kind == "bush" || it.kind == "hedge" }.flatMap { listOf(it.x, it.z) }.toFloatArray()
            shops = town.landmarks.flatMap { listOf((it.rect[0] + it.rect[2]) / 2f, (it.rect[1] + it.rect[3]) / 2f) }.toFloatArray()
            val bell = town.landmarks.firstOrNull { it.kind == "clocktower" } ?: town.landmarks.firstOrNull { it.kind.contains("church") }
            sound.bellAt = bell?.let { floatArrayOf((it.rect[0] + it.rect[2]) / 2f, (it.rect[1] + it.rect[3]) / 2f) }
            fountain = (town.plazaProps + town.props).firstOrNull { it.kind.startsWith("fountain") }?.let { floatArrayOf(it.x, it.z) }
        }
        val u = Mixer.U
        // green: the trees and bushes within 40 m (some 30 make a leafy street)
        var nTrees = 0
        val r2 = (40f * u) * (40f * u)
        var i = 0
        while (i < trees.size) { val dx = trees[i] - x; val dz = trees[i + 1] - z; if (dx * dx + dz * dz < r2) nTrees++; i += 2 }
        val onLawn = GameState.surface == com.lifemax.rpg.feature.habits.presentation.scene.Surface.GRASS
        val green = (nTrees / 30f + (if (onLawn) .25f else 0f)).coerceIn(0f, 1f)
        // urban: the landmarks and big stores within 150 m
        var nShops = 0
        val s2 = (150f * u) * (150f * u)
        i = 0
        while (i < shops.size) { val dx = shops[i] - x; val dz = shops[i + 1] - z; if (dx * dx + dz * dz < s2) nShops++; i += 2 }
        val urban = (nShops / 5f).coerceIn(0f, 1f)
        // the people within 25 m, and where they gather
        var nPeople = 0; var cx = 0f; var cz = 0f
        runCatching {
            val n = TownPlay.figureCount.coerceAtMost(TownPlay.figures.size)
            for (k in 0 until n) {
                val f = TownPlay.figures[k]
                if (f.me) continue
                if (hypot(f.x - x, f.z - z) < 25f * u) { nPeople++; cx += f.x; cz += f.z }
            }
        }
        if (nPeople > 0) sound.crowdAt(cx / nPeople, cz / nPeople)
        // the nearest water (the town's river)
        var waterM = 1e9f
        if (town != null) {
            var bx = 0f; var bz = 0f; var best = Float.MAX_VALUE
            for (poly in town.water) {
                for (j in poly.indices) {
                    val a = poly[j]; val b = poly[(j + 1) % poly.size]
                    val dx = b.x - a.x; val dz = b.z - a.z
                    val l2 = dx * dx + dz * dz
                    val t = if (l2 > 0f) (((x - a.x) * dx + (z - a.z) * dz) / l2).coerceIn(0f, 1f) else 0f
                    val px = a.x + dx * t; val pz = a.z + dz * t
                    val d = (px - x) * (px - x) + (pz - z) * (pz - z)
                    if (d < best) { best = d; bx = px; bz = pz }
                }
            }
            if (best < Float.MAX_VALUE) { waterM = sqrt(best) / u; sound.waterAt(bx, bz, "river") }
        }
        fountain?.let { sound.fountainAt(it[0], it[1]) }
        // the police: only officers on a call (chasing, responding) within earshot sound the siren, from their way, at
        // least a street off (a patrol is silent: SoundRules.sirenFor)
        val range = SoundRules.SIREN_RANGE_M * u
        val calls = runCatching { com.timerpg.street.LoopSession.sirenOfficers(System.nanoTime()) }.getOrNull().orEmpty()
            .filter { hypot(it.second - x, it.third - z) < range }
        calls.minByOrNull { hypot(it.second - x, it.third - z) }?.let { o ->
            val dx = o.second - x; val dz = o.third - z; val d = hypot(dx, dz).coerceAtLeast(1f)
            val far = d.coerceAtLeast(60f * u)
            sound.sirenAt(x + dx / d * far, z + dz / d * far)
        }
        val moving = w?.cars?.count { kotlin.math.abs(it.forwardSpeed) > 1f && it !== w.driven } ?: 0
        return SoundRules.Place(year, green, urban, nPeople, waterM, GameState.indoors, TownPlay.inCar, calls.isNotEmpty(), 0f, moving,
            (System.nanoTime() % 3_600_000_000_000L) / 1e9f)
    }
}
