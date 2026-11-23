package com.lifemax.rpg.feature.habits.physics

/**
 * The game's rigid-body physics (docs/VEHICLES.md): static colliders (the town's solid shapes and the ground near the
 * player) and wheeled vehicles, stepped at a fixed rate. Metres, seconds, kilograms, y up; a vehicle's own frame is its
 * model's (glTF: +z forward, +x its left, the origin on the ground midway between the axles). Handles are small ints.
 *
 * [JoltPhysics] is the one implementation: Jolt Physics through JNI (app/src/main/cpp/physics), the same library on
 * the phone (arm64) and the desktop (Linux x86-64). Nothing a frame calls allocates. One thread at a time.
 */
interface Physics : AutoCloseable {
    /** A static box: centre, half extents, turned [yaw] about +y (its +z towards (sin yaw, cos yaw)). Returns a handle, or -1. */
    fun addBox(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, yaw: Float = 0f, friction: Float = 1f): Int
    /** A static upright cylinder (a tree trunk, a post). */
    fun addCylinder(cx: Float, cy: Float, cz: Float, radius: Float, halfHeight: Float, friction: Float = 1f): Int
    /** A static heightfield: [n] x [n] samples (rows of x, z by z), the first at ([x0], [z0]), [cell] metres apart, heights absolute. */
    fun addHeightfield(x0: Float, z0: Float, n: Int, cell: Float, heights: FloatArray, friction: Float = 1f): Int
    fun remove(handle: Int)
    /** How many bodies the world holds now (statics and vehicles). */
    val bodies: Int

    /** A vehicle from [desc] ([VehicleDesc]) and its chassis [boxes] (6 floats each: centre, size), at ([x], [y], [z]) heading [yaw]. */
    fun addVehicle(desc: FloatArray, boxes: FloatArray, x: Float, y: Float, z: Float, yaw: Float): Int
    fun removeVehicle(v: Int)
    /** [forward] -1..1 (negative reverses), [right] -1..1 (positive steers right), [brake] and [handbrake] 0..1. */
    fun input(v: Int, forward: Float, right: Float, brake: Float, handbrake: Float)
    /** Teleports it there, still. */
    fun place(v: Int, x: Float, y: Float, z: Float, yaw: Float)
    /** Its state into [out] ([VehicleState.COUNT] floats). */
    fun state(v: Int, out: FloatArray)

    /** One fixed step; returns how many breaks wait in [takeBreaks]. */
    fun step(dt: Float, collisionSteps: Int = 1): Int

    // ---- destruction (docs/VEHICLES.md, "Destruction")
    /**
     * Static [handle]'s give: [impulse] (N s, > 0): something dynamic hitting it with mass times approach speed at least
     * this goes through it and a break is recorded ([takeBreaks]); [soft] (1/s, > 0): everything passes through it, slowed
     * by this drag while inside (a bush). Both 0: solid.
     */
    fun setGive(handle: Int, impulse: Float, soft: Float = 0f)
    /** The breaks since the last call into [out] ([Breaks.COUNT] floats each); returns how many. */
    fun takeBreaks(out: FloatArray): Int
    /**
     * A loose piece from [d] (18 floats: centre x, y, z; half extents x, y, z; rotation qx, qy, qz, qw; mass kg; velocity
     * x, y, z; spin x, y, z rad/s; friction), dynamic. Returns a handle, or -1.
     */
    fun addDebris(d: FloatArray): Int
    /** Bodies' poses: for each of the first [n] [handles], [POSE] floats (x, y, z, qx, qy, qz, qw, 1 awake) into [out]. */
    fun poses(handles: IntArray, n: Int, out: FloatArray)
    /** Adds a velocity (m/s) to vehicle [v] (the momentum it gave away). */
    fun push(v: Int, dvx: Float, dvy: Float, dvz: Float)

    companion object {
        /** Floats a pose ([poses]) takes. */
        const val POSE = 8
        /** A physics world, or null where the native library cannot load (a unit test on a JVM without it). */
        fun create(maxBodies: Int = 4096): Physics? = if (JoltNative.load()) JoltPhysics(maxBodies) else null
    }
}

/** Indices into [Physics.addVehicle]'s description (physics_core.h VehicleDesc: change them together). */
object VehicleDesc {
    const val MASS = 0
    const val WHEEL_RADIUS = 1
    const val WHEEL_WIDTH = 2
    const val WHEEL_X = 3
    const val WHEEL_Y = 4
    const val FRONT_Z = 5
    const val REAR_Z = 6
    const val SUSP_MIN = 7
    const val SUSP_MAX = 8
    const val SUSP_FREQ = 9
    const val SUSP_DAMP = 10
    const val MAX_STEER = 11
    const val ENGINE_TORQUE = 12
    const val ENGINE_MIN_RPM = 13
    const val ENGINE_MAX_RPM = 14
    const val DIFF_RATIO = 15
    const val BRAKE_TORQUE = 16
    const val HANDBRAKE_TORQUE = 17
    const val DRIVE = 18
    const val GEARS = 19
    const val GEAR1 = 20
    const val REVERSE = 26
    const val LINEAR_DAMPING = 27
    const val COM_Y = 28
    const val FRICTION_LONG = 29
    const val FRICTION_LAT = 30
    const val SHIFT_UP_RPM = 31
    const val SHIFT_DOWN_RPM = 32
    const val COUNT = 33
}

/** Indices into [Physics.state]'s output (physics_core.h VehicleStateIndex). */
object VehicleState {
    const val X = 0; const val Y = 1; const val Z = 2
    const val QX = 3; const val QY = 4; const val QZ = 5; const val QW = 6
    const val VX = 7; const val VY = 8; const val VZ = 9
    const val FORWARD_SPEED = 10
    const val RPM = 11
    const val GEAR = 12
    const val ACTIVE = 13
    const val WHEELS = 14
    /** Per wheel (fl, fr, rl, rr), from [WHEELS]: its turn about its axle (rad), its steer (rad, + left), suspension length (m), contact. */
    const val W_ANGLE = 0; const val W_STEER = 1; const val W_SUSPENSION = 2; const val W_CONTACT = 3
    const val W_COUNT = 4
    const val COUNT = WHEELS + 4 * W_COUNT
}

/** Indices into a break ([Physics.takeBreaks]; physics_core.h BreakIndex). */
object Breaks {
    const val HANDLE = 0
    const val X = 1; const val Y = 2; const val Z = 3
    const val VX = 4; const val VY = 5; const val VZ = 6
    const val SPEED = 7
    const val MASS = 8
    const val VEHICLE = 9
    const val COUNT = 10
    /** At most this many a step (MAX_BREAKS). */
    const val MAX = 64
}

/** The JNI entry points (physics_jni.cpp). */
object JoltNative {
    @Volatile private var loaded: Boolean? = null

    /**
     * Loads libtimerpg_physics: from the file `-Dtimerpg.physics.lib` names (the desktop's build), else by name (the
     * phone's APK, or java.library.path). False if it cannot be loaded.
     */
    fun load(): Boolean {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val ok = runCatching {
                val file = System.getProperty("timerpg.physics.lib")
                if (file != null && java.io.File(file).isFile) System.load(java.io.File(file).absolutePath) else System.loadLibrary("timerpg_physics")
                check(descCount() == VehicleDesc.COUNT && stateCount() == VehicleState.COUNT && breakCount() == Breaks.COUNT) { "physics library's layout differs from Physics.kt" }
            }.isSuccess
            loaded = ok
            return ok
        }
    }

    @JvmStatic external fun create(maxBodies: Int): Long
    @JvmStatic external fun destroy(w: Long)
    @JvmStatic external fun addBox(w: Long, cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, yaw: Float, friction: Float): Int
    @JvmStatic external fun addCylinder(w: Long, cx: Float, cy: Float, cz: Float, r: Float, hh: Float, friction: Float): Int
    @JvmStatic external fun addHeightfield(w: Long, x0: Float, z0: Float, n: Int, cell: Float, heights: FloatArray, friction: Float): Int
    @JvmStatic external fun removeBody(w: Long, h: Int)
    @JvmStatic external fun bodyCount(w: Long): Int
    @JvmStatic external fun addVehicle(w: Long, desc: FloatArray, boxes: FloatArray, x: Float, y: Float, z: Float, yaw: Float): Int
    @JvmStatic external fun removeVehicle(w: Long, v: Int)
    @JvmStatic external fun setVehicleInput(w: Long, v: Int, forward: Float, right: Float, brake: Float, handbrake: Float)
    @JvmStatic external fun setVehiclePose(w: Long, v: Int, x: Float, y: Float, z: Float, yaw: Float)
    @JvmStatic external fun vehicleState(w: Long, v: Int, out: FloatArray)
    @JvmStatic external fun step(w: Long, dt: Float, collisionSteps: Int): Int
    @JvmStatic external fun setGive(w: Long, h: Int, impulse: Float, soft: Float)
    @JvmStatic external fun takeBreaks(w: Long, out: FloatArray): Int
    @JvmStatic external fun addDebris(w: Long, d: FloatArray): Int
    @JvmStatic external fun bodyPoses(w: Long, handles: IntArray, n: Int, out: FloatArray)
    @JvmStatic external fun addVehicleVelocity(w: Long, v: Int, dx: Float, dy: Float, dz: Float)
    @JvmStatic external fun breakCount(): Int
    @JvmStatic external fun descCount(): Int
    @JvmStatic external fun stateCount(): Int
}

/** [Physics] on Jolt (physics_core.cpp). */
class JoltPhysics(maxBodies: Int = 4096) : Physics {
    private var w: Long = JoltNative.create(maxBodies)
    private fun h(): Long { check(w != 0L) { "physics world closed" }; return w }

    override fun addBox(cx: Float, cy: Float, cz: Float, hx: Float, hy: Float, hz: Float, yaw: Float, friction: Float) = JoltNative.addBox(h(), cx, cy, cz, hx, hy, hz, yaw, friction)
    override fun addCylinder(cx: Float, cy: Float, cz: Float, radius: Float, halfHeight: Float, friction: Float) = JoltNative.addCylinder(h(), cx, cy, cz, radius, halfHeight, friction)
    override fun addHeightfield(x0: Float, z0: Float, n: Int, cell: Float, heights: FloatArray, friction: Float) = JoltNative.addHeightfield(h(), x0, z0, n, cell, heights, friction)
    override fun remove(handle: Int) { if (handle >= 0) JoltNative.removeBody(h(), handle) }
    override val bodies: Int get() = JoltNative.bodyCount(h())
    override fun addVehicle(desc: FloatArray, boxes: FloatArray, x: Float, y: Float, z: Float, yaw: Float) = JoltNative.addVehicle(h(), desc, boxes, x, y, z, yaw)
    override fun removeVehicle(v: Int) { if (v >= 0) JoltNative.removeVehicle(h(), v) }
    override fun input(v: Int, forward: Float, right: Float, brake: Float, handbrake: Float) = JoltNative.setVehicleInput(h(), v, forward, right, brake, handbrake)
    override fun place(v: Int, x: Float, y: Float, z: Float, yaw: Float) = JoltNative.setVehiclePose(h(), v, x, y, z, yaw)
    override fun state(v: Int, out: FloatArray) = JoltNative.vehicleState(h(), v, out)
    override fun step(dt: Float, collisionSteps: Int) = JoltNative.step(h(), dt, collisionSteps)
    override fun setGive(handle: Int, impulse: Float, soft: Float) { if (handle >= 0) JoltNative.setGive(h(), handle, impulse, soft) }
    override fun takeBreaks(out: FloatArray) = JoltNative.takeBreaks(h(), out)
    override fun addDebris(d: FloatArray) = JoltNative.addDebris(h(), d)
    override fun poses(handles: IntArray, n: Int, out: FloatArray) { if (n > 0) JoltNative.bodyPoses(h(), handles, n, out) }
    override fun push(v: Int, dvx: Float, dvy: Float, dvz: Float) { if (v >= 0) JoltNative.addVehicleVelocity(h(), v, dvx, dvy, dvz) }
    override fun close() { if (w != 0L) { JoltNative.destroy(w); w = 0L } }
}
