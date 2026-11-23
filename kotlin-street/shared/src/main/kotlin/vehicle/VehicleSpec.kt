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
) {
    class Node(val name: String, val x: Float, val y: Float, val z: Float, val parent: String?, val axis: FloatArray?)

    fun node(n: String): Node? = nodes[n]
    val doors: List<String> get() = nodes.keys.filter { it.startsWith("door_") }.sorted()

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
            return VehicleSpec(
                v.getString("key"), v.getString("name"), years.getInt(0), years.getInt(years.length() - 1),
                v.getDouble("estimated_mass_kg").toFloat(), v.getDouble("estimated_top_speed_kmh").toFloat(),
                v.getDouble("wheel_radius_m").toFloat(), v.getDouble("wheelbase_m").toFloat(), v.getDouble("track_m").toFloat(),
                boxes.toFloatArray(), nodes, lamps, cols, paint?.optString("stock_secondary")?.takeIf { it.isNotBlank() }?.let { colour(it) } ?: colour("#d2c4a5"),
                nearLod.getString("file"), lodFile("mid"), lodFile("far"),
                lodM?.optDouble("mid", 38.0)?.toFloat() ?: 38f, lodM?.optDouble("far", 95.0)?.toFloat() ?: 95f, doorOpen,
            )
        }

        fun colour(hex: String): Int = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()

        /** The five cars V1 drives (docs/VEHICLES.md), by their asset folders. */
        val V1 = listOf("alderwick-1928", "fenmere-1938", "northcairn-1956", "rowanvale-1970", "tallmere-1972")
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
) {
    /** The physics description for [s]. */
    fun desc(s: VehicleSpec): FloatArray {
        val d = FloatArray(VehicleDesc.COUNT)
        d[VehicleDesc.MASS] = s.massKg
        d[VehicleDesc.WHEEL_RADIUS] = s.wheelRadius
        d[VehicleDesc.WHEEL_WIDTH] = (s.wheelRadius * .55f).coerceIn(.14f, .26f)
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
        d[VehicleDesc.FRICTION_LAT] = grip
        // (shifts up near the top of the rev range, down well below it: an automatic's gentle pattern)
        d[VehicleDesc.SHIFT_UP_RPM] = maxRpm * .82f
        d[VehicleDesc.SHIFT_DOWN_RPM] = maxRpm * .36f
        return d
    }

    /** The final drive that makes [maxRpm] in top gear the delivery's top speed. */
    fun finalDrive(s: VehicleSpec): Float {
        val wheelRadS = s.topSpeedKmh / 3.6f / s.wheelRadius
        val engineRadS = maxRpm * 2f * PI.toFloat() / 60f
        return engineRadS / (gears.last() * wheelRadS)
    }

    companion object {
        fun of(s: VehicleSpec): Drivetrain = when {
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
            else -> Drivetrain(torqueNm = 560f, minRpm = 700f, maxRpm = 5600f, gears = floatArrayOf(2.52f, 1.88f, 1.46f, 1f), reverse = -2.3f,
                brakeNm = 2100f, handbrakeNm = 2800f, lockDeg = 36f, travelMin = .05f, travelMax = .26f, springHz = 1.6f, damping = .5f,
                grip = 1f, drag = .02f, comY = .5f)
        }
    }
}
