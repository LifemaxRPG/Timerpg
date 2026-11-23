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

// One fixed step of dt seconds.
void step(World *w, float dt, int collisionSteps);

}  // namespace timerpg
