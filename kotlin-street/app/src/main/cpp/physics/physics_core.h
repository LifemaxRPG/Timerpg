// Timerpg's physics: a small wrapper round Jolt Physics (docs/VEHICLES.md). One World holds static colliders (boxes,
// cylinders, heightfields: the town's solid shapes and the ground near the player) and wheeled vehicles (Jolt's
// WheeledVehicleController), stepped at a fixed rate. No JNI here: physics_jni.cpp puts it on the JVM (the phone and the
// desktop preview), physics_host_test.cpp drives it natively for tests.
//
// Units and axes: metres, seconds, kilograms; y up; a vehicle's own frame is its model's (glTF): +z forward, +x the
// vehicle's left, the origin on the ground midway between the axles.
#pragma once

#include <cstdint>

namespace timerpg {

// The vehicle's description: a float array (the JNI passes one), index by these.
enum VehicleDesc {
    VD_MASS = 0,            // kg
    VD_WHEEL_RADIUS,        // m
    VD_WHEEL_WIDTH,         // m
    VD_WHEEL_X,             // m: half the track (wheel centres at +-x)
    VD_WHEEL_Y,             // m: the wheel centres' height at rest (the axle height)
    VD_FRONT_Z,             // m: the front axle (+z forward)
    VD_REAR_Z,              // m: the rear axle (negative)
    VD_SUSP_MIN,            // m: suspension travel, shortest
    VD_SUSP_MAX,            // m: and longest
    VD_SUSP_FREQ,           // Hz: the spring's natural frequency
    VD_SUSP_DAMP,           // 0..1: its damping ratio
    VD_MAX_STEER,           // rad: the front wheels' full lock
    VD_ENGINE_TORQUE,       // Nm
    VD_ENGINE_MIN_RPM,
    VD_ENGINE_MAX_RPM,
    VD_DIFF_RATIO,          // the final drive
    VD_BRAKE_TORQUE,        // Nm a wheel
    VD_HANDBRAKE_TORQUE,    // Nm a rear wheel
    VD_DRIVE,               // 0 rear wheels, 1 front, 2 all
    VD_GEARS,               // how many forward gears (1..6), their ratios follow
    VD_GEAR1, VD_GEAR2, VD_GEAR3, VD_GEAR4, VD_GEAR5, VD_GEAR6,
    VD_REVERSE,             // the reverse gear's ratio (negative)
    VD_LINEAR_DAMPING,      // the body's (air drag's stand-in), 1/s
    VD_COM_Y,               // m: the centre of mass's height over the ground
    VD_FRICTION_LONG,       // the tyres' grip multipliers (1: Jolt's curves as they are)
    VD_FRICTION_LAT,
    VD_SHIFT_UP_RPM,
    VD_SHIFT_DOWN_RPM,
    VD_DOWNFORCE,           // N per (m/s)^2 of forward speed: a wing's and splitter's push down (0: none, the road cars)
    VD_DOWNFORCE_FRONT,     // 0..1: the share of it on the front axle (the rest on the rear)
    VD_FRICTION_LAT_REAR,   // the rear tyres' lateral grip multiplier (0: the same as VD_FRICTION_LAT)
    VD_FRONT_LAT_TAIL,      // the front tyres' lateral grip past the peak slip angle, as a share of the peak (0: Jolt's 1.0/1.2)
    VD_COUNT
};

// What vehicleState writes: a float array of VS_COUNT.
enum VehicleStateIndex {
    VS_X = 0, VS_Y, VS_Z,           // the body's origin (m)
    VS_QX, VS_QY, VS_QZ, VS_QW,     // its rotation
    VS_VX, VS_VY, VS_VZ,            // linear velocity (m/s)
    VS_FORWARD_SPEED,               // along its own +z (m/s; negative reversing)
    VS_RPM, VS_GEAR,                // the engine's and the gear (-1 reverse, 0 neutral, 1..)
    VS_ACTIVE,                      // 1 awake, 0 asleep
    VS_WHEELS,                      // then 4 wheels (fl, fr, rl, rr) of VW_COUNT each
};
enum VehicleWheelIndex {
    VW_ANGLE = 0,       // its rotation about its axle (rad, accumulated)
    VW_STEER,           // its steer angle (rad, positive left)
    VW_SUSPENSION,      // suspension length now (m)
    VW_CONTACT,         // 1 touching ground
    VW_COUNT
};
constexpr int VS_COUNT = VS_WHEELS + 4 * VW_COUNT;

class World;

// The physics world. Not thread safe: one thread calls it at a time.
World *worldCreate(int maxBodies);
void worldDestroy(World *w);

// Static colliders; each returns a handle (>= 0) or -1. yaw: rotation about +y (rad, counter-clockwise seen from above).
int addBox(World *w, float cx, float cy, float cz, float hx, float hy, float hz, float yaw, float friction);
int addCylinder(World *w, float cx, float cy, float cz, float radius, float halfHeight, float friction);
// A heightfield of n x n samples (row-major, z rows of x), the first at (x0, z0), `cell` metres apart, heights absolute.
// n must be a multiple of 4 plus 1 is not needed: any n >= 4 works (Jolt pads it to its block size).
int addHeightfield(World *w, float x0, float z0, int n, float cell, const float *heights, float friction);
void removeBody(World *w, int handle);
int bodyCount(World *w);

// Vehicles: desc of VD_COUNT floats; boxes the chassis's collision boxes (6 floats each: centre x, y, z, size x, y, z in
// the vehicle's frame); placed at (x, y, z) heading yaw (its +z turned yaw about +y). Returns a handle or -1.
int addVehicle(World *w, const float *desc, const float *boxes, int boxCount, float x, float y, float z, float yaw);
void removeVehicle(World *w, int vehicle);
// forward -1..1 (negative: reverse), right -1..1 (positive steers right), brake 0..1, handbrake 0..1.
void setVehicleInput(World *w, int vehicle, float forward, float right, float brake, float handbrake);
// Teleports it (velocities zeroed), heading yaw.
void setVehiclePose(World *w, int vehicle, float x, float y, float z, float yaw);
void vehicleState(World *w, int vehicle, float *out);

// One fixed step of dt seconds. Returns how many breaks (see below) wait to be taken.
int step(World *w, float dt, int collisionSteps);

// ---- destruction (docs/VEHICLES.md, "Destruction")
// A static body's give: `impulse` (N s, > 0) breaks it: a dynamic body hitting it with its mass times its approach speed
// along the contact's normal at least this much goes through it (the contact made a sensor, no response) and a break is
// recorded for the caller to replace the static with debris; `soft` (> 0) lets every dynamic body through it, slowed by
// this drag (1/s, on its horizontal velocity) while inside (a bush). Both 0: solid (the default).
void setGive(World *w, int handle, float impulse, float soft);
// What a break records: BR_COUNT floats.
enum BreakIndex {
    BR_HANDLE = 0,          // the static's handle
    BR_X, BR_Y, BR_Z,       // the contact point (m)
    BR_VX, BR_VY, BR_VZ,    // the hitting body's velocity (m/s)
    BR_SPEED,               // its approach speed along the normal (m/s)
    BR_MASS,                // its mass (kg)
    BR_VEHICLE,             // the vehicle's handle, or -1 (debris, something else)
    BR_COUNT
};
constexpr int MAX_BREAKS = 64;
// The breaks since the last call into out (at most max of BR_COUNT floats each); returns how many.
int takeBreaks(World *w, float *out, int max);
// A loose piece (debris): a dynamic box, half extents (hx, hy, hz), at (cx, cy, cz) turned by the quaternion q (x, y, z,
// w), of `mass` kg, moving at v (m/s) and spinning at a (rad/s, world). It collides with the statics, the cars and other
// debris, not with the cars' wheels' ground casts (they roll through it, the chassis pushes it). Returns a handle or -1.
int addDebris(World *w, float cx, float cy, float cz, float hx, float hy, float hz, float qx, float qy, float qz, float qw,
              float mass, float vx, float vy, float vz, float ax, float ay, float az, float friction);
// Bodies' poses: for each of the n handles, POSE_COUNT floats (x, y, z, qx, qy, qz, qw, 1 awake or 0 asleep).
constexpr int POSE_COUNT = 8;
void bodyPoses(World *w, const int *handles, int n, float *out);
// Adds (dvx, dvy, dvz) m/s to a vehicle's velocity (the momentum it gave the debris).
void addVehicleVelocity(World *w, int vehicle, float dvx, float dvy, float dvz);

}  // namespace timerpg
