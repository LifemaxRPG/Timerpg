package com.lifemax.rpg.feature.habits.vehicle

import com.lifemax.rpg.feature.habits.physics.VehicleDesc
import kotlin.math.PI

/**
 * One car model as its delivery's model-info.json describes it (assets/vehicles/<key>/model-info.json: ChatGPT's C011 and
 * C012 deliveries, copied, never merged): glTF metres, +y up, +z forward, +x the car's left, the origin on the ground
 * midway between the axles. Its named nodes (wheels, steering pivots, doors, seats, exits, lamps) with their places and
 * hinge axes, its chassis boxes, its paint palette, its measurements and the delivery's estimated mass and top speed;
 * plus the drivetrain the game gives it ([Drivetrain]: the deliveries carry no engine).
 */
class VehicleSpec(
    val key: String,
    val name: String,
    /** The years the model was built (the design's). */
    val from: Int, val to: Int,
    val massKg: Float,
    val topSpeedKmh: Float,
    val wheelRadius: Float,
    val wheelbase: Float,
    val track: Float,
    /** Chassis boxes: centre x, y, z, size x, y, z (metres, the model's frame), six floats each. */
    val boxes: FloatArray,
    /** Named nodes (their centre in the model's frame, their parent, their hinge or spin axis). */
    val nodes: Map<String, Node>,
    /** Lamp nodes by role (head, tail, brake, turn, reverse). */
    val lamps: Map<String, List<String>>,
    /** The body colours the delivery offers (ARGB) and its stock trim colour. */
    val colours: IntArray,
    val secondary: Int,
    /** The near, mid and far model files (relative to its folder). */
    val near: String, val mid: String?, val far: String?,
    /** Draw the mid model past this (m), the far past [farM]. */
    val midM: Float, val farM: Float,
    /** The doors' opening angle the delivery previewed (degrees, signed: the way each swings open), by node. */
    val doorOpen: Map<String, Float>,
    /** Its folder under assets/vehicles. */
    val folder: String = key,
    /** A race build's own setup (model-info.json `race`: engine, gears, suspension, tyres, downforce); null: a road car. */
    val race: Race? = null,
    /** Its liveries (paint.liveries): decals drawn when the car's colour is the livery's body colour. */
    val liveries: List<Livery> = emptyList(),
    /** The rear tyres' width (m; wheel_axles.rear.width_m), for a race build's physics; 0 where not given. */
    val tyreWidth: Float = 0f,
) {
    /**
     * A race build's setup (estimates for the build, not measured on anything): the engine's peak torque and its revs,
     * the gears, the brakes, the steering lock, the suspension (travel, spring frequency, damping), the tyres' grip (a
     * share of Jolt's modern radial), the body's drag, the centre of mass's height and the downforce (N at 200 km/h,
     * growing with the square of the speed; its front share).
     */
    class Race(
        val torqueNm: Float, val minRpm: Float, val maxRpm: Float, val gears: FloatArray, val reverse: Float,
        val brakeNm: Float, val handbrakeNm: Float, val lockDeg: Float,
        val travelMin: Float, val travelMax: Float, val springHz: Float, val damping: Float,
        val grip: Float, val drag: Float, val comY: Float,
        val downforceAt200: Float, val downforceFront: Float,
        /** The tyres' lateral grip per axle (`lateral_grip` [front, rear]; else [grip] both), the fronts' grip past their peak slip as a share of the peak (`front_grip_tail`; 0: Jolt's), the speed (m/s) the steering is halved at (`steer_fade_kmh`; 0: the road cars'). */
        val latFront: Float = grip, val latRear: Float = grip, val frontTail: Float = 0f, val steerFade: Float = 0f,
    ) {
        /** Downforce (N) per (m/s)^2 of speed: the physics' VehicleDesc.DOWNFORCE. */
        val downforceK: Float get() = downforceAt200 / ((200f / 3.6f) * (200f / 3.6f))
    }

    /** A livery: its [id] (its decals' nodes are `livery_<id>_*`), name, and the body colour (ARGB) that shows it. */
    class Livery(val id: String, val name: String, val colour: Int)

    /** The livery a car painted [colour] wears (null: plain paint, none drawn). */
    fun liveryFor(colour: Int): Livery? = liveries.firstOrNull { it.colour == colour }

    /** Its short name in a list: the model and year from its key ("Torrel 1988 RS" for torrel-1988-rs). */
    val label: String get() = labelOf(key)

    class Node(val name: String, val x: Float, val y: Float, val z: Float, val parent: String?, val axis: FloatArray?)

    fun node(n: String): Node? = nodes[n]
    val doors: List<String> get() = nodes.keys.filter { it.startsWith("door_") }.sorted()
    /** The driver's door (left-hand drive): a four-door's `door_front_l`, a two-seater's `door_l` (the Torrels). */
    val driverDoor: String? get() = listOf("door_front_l", "door_l").firstOrNull { it in nodes } ?: doors.firstOrNull()

    /** The front axle's z (+), the rear's (-), the axle height and half the track, from the wheel nodes (else the measurements). */
    val frontZ: Float get() = nodes["steer_fl"]?.z ?: nodes["wheel_fl"]?.z ?: (wheelbase / 2f)
    val rearZ: Float get() = nodes["wheel_rl"]?.z ?: -(wheelbase / 2f)
    val axleY: Float get() = nodes["wheel_rl"]?.y ?: wheelRadius
    val halfTrack: Float get() = nodes["wheel_rl"]?.x?.let { kotlin.math.abs(it) } ?: (track / 2f)

    /** The car's footprint on the ground (half length, half width, centre z), from its boxes. */
    val footprint: FloatArray by lazy {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        var i = 0
        while (i + 5 < boxes.size) {
            x0 = minOf(x0, boxes[i] - boxes[i + 3] / 2f); x1 = maxOf(x1, boxes[i] + boxes[i + 3] / 2f)
            z0 = minOf(z0, boxes[i + 2] - boxes[i + 5] / 2f); z1 = maxOf(z1, boxes[i + 2] + boxes[i + 5] / 2f)
            i += 6
        }
        if (x0 > x1) floatArrayOf(2.4f, .9f, 0f) else floatArrayOf((z1 - z0) / 2f, maxOf(-x0, x1), (z0 + z1) / 2f)
    }

    /** Whether the car can be about in [year] ([VehicleEra]). */
    fun era(year: Int): VehicleEra = VehicleEra.of(this, year)

    /** The game's drivetrain for it. */
    val drivetrain: Drivetrain get() = Drivetrain.of(this)

    /** Its physics description ([VehicleDesc]). */
    fun desc(): FloatArray = drivetrain.desc(this)

    companion object {
        /** A model-info.json: either one car (`vehicle`, as our assets keep it) or a delivery's list (`vehicles`, the first or [key]). */
        fun parse(json: String, key: String? = null): VehicleSpec {
            val root = org.json.JSONObject(json)
            val v = root.optJSONObject("vehicle") ?: root.getJSONArray("vehicles").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { key == null || it.optString("key") == key } ?: error("no vehicle $key")
            }
            val years = v.getJSONArray("years")
            val boxes = ArrayList<Float>()
            v.optJSONArray("collision_boxes")?.let { a ->
                for (i in 0 until a.length()) {
                    val b = a.getJSONObject(i)
                    val c = b.getJSONArray("center_gltf_m"); val s = b.getJSONArray("size_gltf_m")
                    for (k in 0..2) boxes += c.getDouble(k).toFloat()
                    for (k in 0..2) boxes += s.getDouble(k).toFloat()
                }
            }
            val lods = v.getJSONArray("lods")
            val nearLod = (0 until lods.length()).map { lods.getJSONObject(it) }.first { it.optString("lod") == "near" }
            fun lodFile(l: String) = (0 until lods.length()).map { lods.getJSONObject(it) }.firstOrNull { it.optString("lod") == l }?.optString("file")
            val nodes = LinkedHashMap<String, Node>()
            val nj = nearLod.getJSONObject("nodes")
            for (n in nj.keys()) {
                val o = nj.getJSONObject(n)
                val c = o.getJSONArray("center_gltf_m")
                val ax = o.optJSONArray("axis_local_gltf")?.let { a -> FloatArray(3) { a.getDouble(it).toFloat() } }
                nodes[n] = Node(n, c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), o.optString("parent").ifBlank { null }, ax)
            }
            val lamps = LinkedHashMap<String, MutableList<String>>()
            nearLod.optJSONObject("lamps")?.let { l -> for (n in l.keys()) lamps.getOrPut(l.getJSONObject(n).optString("role")) { ArrayList() } += n }
            val paint = v.optJSONObject("paint")
            val cols = paint?.optJSONArray("preset_body_colours")?.let { a -> IntArray(a.length()) { colour(a.getString(it)) } } ?: intArrayOf(colour("#707276"))
            val doorOpen = HashMap<String, Float>()
            for (k in listOf("door_preview_degrees", "cargo_preview_degrees")) nearLod.optJSONObject(k)?.let { d -> for (n in d.keys()) doorOpen[n] = d.getDouble(n).toFloat() }
            val lodM = v.optJSONObject("suggested_lod_m")
            val race = v.optJSONObject("race")?.let { r ->
                fun pair(k: String, i: Int) = r.getJSONArray(k).getDouble(i).toFloat()
                val g = r.getJSONArray("gears")
                Race(
                    r.getDouble("engine_torque_nm").toFloat(), pair("engine_rpm", 0), pair("engine_rpm", 1), FloatArray(g.length()) { g.getDouble(it).toFloat() },
                    r.optDouble("reverse", -3.3).toFloat(), r.getDouble("brake_torque_nm").toFloat(), r.getDouble("handbrake_torque_nm").toFloat(),
                    r.getDouble("lock_deg").toFloat(), pair("suspension_travel_m", 0), pair("suspension_travel_m", 1),
                    r.getDouble("spring_hz").toFloat(), r.getDouble("damping").toFloat(), r.getDouble("tyre_grip").toFloat(),
                    r.getDouble("drag").toFloat(), r.getDouble("com_y_m").toFloat(),
                    r.optDouble("downforce_n_at_200_kmh", 0.0).toFloat(), r.optDouble("downforce_front", .5).toFloat(),
                    r.optJSONArray("lateral_grip")?.getDouble(0)?.toFloat() ?: r.getDouble("tyre_grip").toFloat(),
                    r.optJSONArray("lateral_grip")?.getDouble(1)?.toFloat() ?: r.getDouble("tyre_grip").toFloat(),
                    r.optDouble("front_grip_tail", 0.0).toFloat(), (r.optDouble("steer_fade_kmh", 0.0) / 3.6).toFloat(),
                )
            }
            val liveries = paint?.optJSONArray("liveries")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { Livery(it.getString("id"), it.optString("name"), colour(it.getString("body"))) }
            } ?: emptyList()
            val tyreW = v.optJSONObject("wheel_axles")?.optJSONObject("rear")?.optDouble("width_m", 0.0)?.toFloat() ?: 0f
            return VehicleSpec(
                v.getString("key"), v.getString("name"), years.getInt(0), years.getInt(years.length() - 1),
                v.getDouble("estimated_mass_kg").toFloat(), v.getDouble("estimated_top_speed_kmh").toFloat(),
                v.getDouble("wheel_radius_m").toFloat(), v.getDouble("wheelbase_m").toFloat(), v.getDouble("track_m").toFloat(),
                boxes.toFloatArray(), nodes, lamps, cols, paint?.optString("stock_secondary")?.takeIf { it.isNotBlank() }?.let { colour(it) } ?: colour("#d2c4a5"),
                nearLod.getString("file"), lodFile("mid"), lodFile("far"),
                lodM?.optDouble("mid", 38.0)?.toFloat() ?: 38f, lodM?.optDouble("far", 95.0)?.toFloat() ?: 95f, doorOpen,
                race = race, liveries = liveries, tyreWidth = tyreW,
            )
        }

        fun colour(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

        /** The five cars V1 drives (docs/VEHICLES.md), by their asset folders: the ones parked in the towns. */
        val V1 = listOf("alderwick-1928", "fenmere-1938", "northcairn-1956", "rowanvale-1970", "tallmere-1972", "torrel-1968", "torrel-1988")

        /** Every car the player can spawn (the car button's list, docs/CARS.md): V1 and the race Torrel (C046), not parked. */
        val CATALOGUE = V1 + listOf("torrel-1988-rs")

        /** A key's short name: the model capitalised, the year, a build's letters ("torrel-1988-rs": "Torrel 1988 RS"). */
        fun labelOf(key: String): String = key.split('-').joinToString(" ") { w ->
            when {
                w.isEmpty() || w[0].isDigit() -> w
                w.length <= 3 -> w.uppercase()
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }
    }
}

/**
 * Whether a model is about in a year: [NONE] before it was built, [COMMON] while it was built and for [LIFE] years after
 * (a working car), [CLASSIC] after that (kept by a collector: in a driveway, now and then on the street).
 */
enum class VehicleEra {
    NONE, COMMON, CLASSIC;

    companion object {
        /** A car's working life on the road (years after its model's last year). */
        const val LIFE = 20
        fun of(s: VehicleSpec, year: Int): VehicleEra = when {
            year < s.from -> NONE
            year <= s.to + LIFE -> COMMON
            else -> CLASSIC
        }
    }
}

/**
 * The engine, gearbox and running gear the game gives a car (the deliveries' manifests carry only mass, top speed and
 * measurements). Estimates by era and kind, not measured on anything real: a 1920s six of ~60 hp on a three-speed, a
 * late-30s six of ~90 hp, a 1950s V8 of ~170 hp on an automatic, a 1970 sports coupe's big V8, a 1972 pickup's V8. The
 * final drive is worked out so the engine's governed speed in top gear is the delivery's top speed: the car really
 * runs up to its era's top speed and no further.
 */
class Drivetrain(
    val torqueNm: Float,
    val minRpm: Float,
    val maxRpm: Float,
    val gears: FloatArray,
    val reverse: Float,
    /** Brake torque a wheel (Nm), the handbrake's a rear wheel. */
    val brakeNm: Float,
    val handbrakeNm: Float,
    /** The front wheels' full lock (degrees). */
    val lockDeg: Float,
    /** Suspension: travel (m), spring frequency (Hz), damping ratio. */
    val travelMin: Float, val travelMax: Float, val springHz: Float, val damping: Float,
    /** Tyre grip, as shares of Jolt's modern radial (narrow bias-ply tyres of the twenties grip less). */
    val grip: Float,
    /** The body's linear damping (air drag's stand-in). */
    val drag: Float,
    /** The centre of mass's height (m). */
    val comY: Float,
    /** 0 rear-wheel drive, 1 front, 2 all. */
    val drive: Int = 0,
    /** Downforce: N per (m/s)^2 (0: none, every road car) and its front share. */
    val downforceK: Float = 0f,
    val downforceFront: Float = .5f,
    /** The tyres' lateral grip per axle (shares of Jolt's radial; road cars: [grip] both) and the fronts' grip past their peak slip (0: Jolt's curve). */
    val latFront: Float = grip,
    val latRear: Float = grip,
    val frontTail: Float = 0f,
) {
    /** The physics description for [s]. */
    fun desc(s: VehicleSpec): FloatArray {
        val d = FloatArray(VehicleDesc.COUNT)
        d[VehicleDesc.MASS] = s.massKg
        d[VehicleDesc.WHEEL_RADIUS] = s.wheelRadius
        // (a race build's real tyre width: wider than the road cars' estimate from the radius)
        d[VehicleDesc.WHEEL_WIDTH] = if (s.race != null && s.tyreWidth > 0f) s.tyreWidth else (s.wheelRadius * .55f).coerceIn(.14f, .26f)
        d[VehicleDesc.WHEEL_X] = s.halfTrack
        d[VehicleDesc.WHEEL_Y] = s.axleY
        d[VehicleDesc.FRONT_Z] = s.frontZ
        d[VehicleDesc.REAR_Z] = s.rearZ
        d[VehicleDesc.SUSP_MIN] = travelMin
        d[VehicleDesc.SUSP_MAX] = travelMax
        d[VehicleDesc.SUSP_FREQ] = springHz
        d[VehicleDesc.SUSP_DAMP] = damping
        d[VehicleDesc.MAX_STEER] = (lockDeg * PI / 180.0).toFloat()
        d[VehicleDesc.ENGINE_TORQUE] = torqueNm
        d[VehicleDesc.ENGINE_MIN_RPM] = minRpm
        d[VehicleDesc.ENGINE_MAX_RPM] = maxRpm
        d[VehicleDesc.DIFF_RATIO] = finalDrive(s)
        d[VehicleDesc.BRAKE_TORQUE] = brakeNm
        d[VehicleDesc.HANDBRAKE_TORQUE] = handbrakeNm
        d[VehicleDesc.DRIVE] = drive.toFloat()
        d[VehicleDesc.GEARS] = gears.size.toFloat()
        for ((i, g) in gears.withIndex()) d[VehicleDesc.GEAR1 + i] = g
        d[VehicleDesc.REVERSE] = reverse
        d[VehicleDesc.LINEAR_DAMPING] = drag
        d[VehicleDesc.COM_Y] = comY
        d[VehicleDesc.FRICTION_LONG] = grip
        d[VehicleDesc.FRICTION_LAT] = latFront
        d[VehicleDesc.FRICTION_LAT_REAR] = latRear
        d[VehicleDesc.FRONT_LAT_TAIL] = frontTail
        // (an automatic's pattern: up early when cruising, near the top flat out but before the limiter (97%), down well
        // below; the upshifts are the physics' own, not held back by wheelspin, with a short torque cut: quicker in a
        // newer or a race car. 2026-11-25: flat out, Jolt's box sat on the limiter up to 2 s while the tyres spun)
        d[VehicleDesc.SHIFT_UP_RPM] = maxRpm * .72f
        d[VehicleDesc.SHIFT_UP_FULL_RPM] = maxRpm * .92f
        d[VehicleDesc.SHIFT_DOWN_RPM] = maxRpm * .36f
        d[VehicleDesc.SHIFT_TIME] = when { s.race != null -> .15f; s.from >= 1980 -> .2f; s.from >= 1950 -> .3f; else -> .4f }
        d[VehicleDesc.DOWNFORCE] = downforceK
        d[VehicleDesc.DOWNFORCE_FRONT] = downforceFront
        return d
    }

    /** The final drive that makes [maxRpm] in top gear the delivery's top speed. */
    fun finalDrive(s: VehicleSpec): Float {
        val wheelRadS = s.topSpeedKmh / 3.6f / s.wheelRadius
        val engineRadS = maxRpm * 2f * PI.toFloat() / 60f
        return engineRadS / (gears.last() * wheelRadS)
    }

    companion object {
        fun of(s: VehicleSpec): Drivetrain = s.race?.let { r ->
            // a race build's own setup (model-info.json `race`): its numbers as they are
            Drivetrain(torqueNm = r.torqueNm, minRpm = r.minRpm, maxRpm = r.maxRpm, gears = r.gears, reverse = r.reverse,
                brakeNm = r.brakeNm, handbrakeNm = r.handbrakeNm, lockDeg = r.lockDeg, travelMin = r.travelMin, travelMax = r.travelMax,
                springHz = r.springHz, damping = r.damping, grip = r.grip, drag = r.drag, comY = r.comY,
                downforceK = r.downforceK, downforceFront = r.downforceFront, latFront = r.latFront, latRear = r.latRear, frontTail = r.frontTail)
        } ?: when {
            s.from < 1932 -> Drivetrain(torqueNm = 230f, minRpm = 500f, maxRpm = 3000f, gears = floatArrayOf(3.0f, 1.65f, 1f), reverse = -3.5f,
                brakeNm = 900f, handbrakeNm = 1400f, lockDeg = 34f, travelMin = .06f, travelMax = .26f, springHz = 1.6f, damping = .35f,
                grip = .75f, drag = .04f, comY = .72f)
            s.from < 1950 -> Drivetrain(torqueNm = 290f, minRpm = 550f, maxRpm = 3600f, gears = floatArrayOf(2.6f, 1.55f, 1f), reverse = -3.2f,
                brakeNm = 1300f, handbrakeNm = 1800f, lockDeg = 36f, travelMin = .06f, travelMax = .28f, springHz = 1.45f, damping = .38f,
                grip = .85f, drag = .03f, comY = .62f)
            s.from < 1965 -> Drivetrain(torqueNm = 420f, minRpm = 600f, maxRpm = 4400f, gears = floatArrayOf(2.45f, 1.45f, 1f), reverse = -2.2f,
                brakeNm = 1700f, handbrakeNm = 2400f, lockDeg = 36f, travelMin = .05f, travelMax = .30f, springHz = 1.25f, damping = .42f,
                grip = .92f, drag = .025f, comY = .56f)
            s.key.startsWith("tallmere") -> Drivetrain(torqueNm = 480f, minRpm = 600f, maxRpm = 4400f, gears = floatArrayOf(2.5f, 1.5f, 1f), reverse = -2.1f,
                brakeNm = 2000f, handbrakeNm = 2600f, lockDeg = 35f, travelMin = .06f, travelMax = .32f, springHz = 1.4f, damping = .45f,
                grip = .95f, drag = .025f, comY = .66f)
            // the Torrels: light two-seat sports cars (976 and 1,088 kg) with a small high-revving engine, not the big
            // coupe's 560 Nm V8 (that let a one-tonne car spin up to 46 km/h on a quarter throttle and slide wide at full
            // lock: a 23 m circle); quick steering, a low centre of mass
            s.key.startsWith("torrel") && s.from < 1980 -> Drivetrain(torqueNm = 200f, minRpm = 800f, maxRpm = 6800f, gears = floatArrayOf(3.2f, 2.0f, 1.4f, 1f), reverse = -3.3f,
                brakeNm = 1500f, handbrakeNm = 2000f, lockDeg = 31f, travelMin = .05f, travelMax = .22f, springHz = 1.9f, damping = .5f,
                grip = 1f, drag = .02f, comY = .42f)
            s.key.startsWith("torrel") -> Drivetrain(torqueNm = 260f, minRpm = 800f, maxRpm = 7000f, gears = floatArrayOf(3.3f, 2.1f, 1.5f, 1.2f, 1f), reverse = -3.3f,
                brakeNm = 1700f, handbrakeNm = 2200f, lockDeg = 31f, travelMin = .05f, travelMax = .22f, springHz = 2f, damping = .52f,
                grip = 1.05f, drag = .018f, comY = .42f)
            else -> Drivetrain(torqueNm = 560f, minRpm = 700f, maxRpm = 5600f, gears = floatArrayOf(2.52f, 1.88f, 1.46f, 1f), reverse = -2.3f,
                brakeNm = 2100f, handbrakeNm = 2800f, lockDeg = 36f, travelMin = .05f, travelMax = .26f, springHz = 1.6f, damping = .5f,
                grip = 1f, drag = .02f, comY = .5f)
        }
    }
}
