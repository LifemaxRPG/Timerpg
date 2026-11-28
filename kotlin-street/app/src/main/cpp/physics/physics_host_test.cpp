// physics_core on a computer, without the JVM (tools/physics-host-test.sh): a 1956 sedan on flat ground runs up to
// speed, brakes, turns and hits a wall, and the step time is printed. The game's own tests (desktop VehiclePhysicsTest)
// drive the same library through JNI with the cars' real numbers.
#include <chrono>
#include <cmath>
#include <cstdio>

#include "physics_core.h"

using namespace timerpg;

static void sedan(float *d) {
    for (int i = 0; i < VD_COUNT; ++i) d[i] = 0.f;
    d[VD_MASS] = 1685.f; d[VD_WHEEL_RADIUS] = .361f; d[VD_WHEEL_WIDTH] = .2f;
    d[VD_WHEEL_X] = .795f; d[VD_WHEEL_Y] = .361f; d[VD_FRONT_Z] = 1.53f; d[VD_REAR_Z] = -1.53f;
    d[VD_SUSP_MIN] = .05f; d[VD_SUSP_MAX] = .3f; d[VD_SUSP_FREQ] = 1.4f; d[VD_SUSP_DAMP] = .5f;
    d[VD_MAX_STEER] = .6f; d[VD_ENGINE_TORQUE] = 380.f; d[VD_ENGINE_MIN_RPM] = 700.f; d[VD_ENGINE_MAX_RPM] = 4600.f;
    d[VD_DIFF_RATIO] = 3.36f; d[VD_BRAKE_TORQUE] = 1800.f; d[VD_HANDBRAKE_TORQUE] = 3000.f; d[VD_DRIVE] = 0.f;
    d[VD_GEARS] = 3.f; d[VD_GEAR1] = 2.45f; d[VD_GEAR2] = 1.45f; d[VD_GEAR3] = 1.f; d[VD_REVERSE] = -2.2f;
    d[VD_LINEAR_DAMPING] = .02f; d[VD_COM_Y] = .55f; d[VD_FRICTION_LONG] = 1.f; d[VD_FRICTION_LAT] = 1.f;
    d[VD_SHIFT_UP_RPM] = 4000.f; d[VD_SHIFT_DOWN_RPM] = 1600.f;
}

int main() {
    World *w = worldCreate(1024);
    addBox(w, 0.f, -.5f, 0.f, 2000.f, .5f, 2000.f, 0.f, 1.f);
    float d[VD_COUNT];
    sedan(d);
    const float boxes[] = {0.f, .7f, 0.f, 1.9f, .7f, 5.f, 0.f, 1.3f, -.3f, 1.6f, .5f, 2.4f};
    int car = addVehicle(w, d, boxes, 2, 0.f, .1f, 0.f, 0.f);
    float s[VS_COUNT];
    const float dt = 1.f / 60.f;
    double ns = 0; int steps = 0;
    auto tick = [&](float fwd, float right, float brake) {
        setVehicleInput(w, car, fwd, right, brake, 0.f);
        auto t0 = std::chrono::steady_clock::now();
        step(w, dt, 1);
        ns += std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - t0).count();
        steps++;
        vehicleState(w, car, s);
    };
    // Front brakes should hold while the rear tires spin, then all brakes should return.
    for (int i = 0; i < 45; ++i) tick(0.f, 0.f, 1.f);
    const int front = VS_WHEELS + VW_ANGLE;
    const int rear = VS_WHEELS + 2 * VW_COUNT + VW_ANGLE;
    float previousFront = s[front], previousRear = s[rear];
    float frontTravel = 0.f, rearTravel = 0.f;
    for (int i = 0; i < 90; ++i) {
        tick(1.f, 0.f, 1.f);
        frontTravel += std::abs(std::remainder(s[front] - previousFront, 6.2831853f));
        rearTravel += std::abs(std::remainder(s[rear] - previousRear, 6.2831853f));
        previousFront = s[front]; previousRear = s[rear];
    }
    const float burnoutSpeed = std::abs(s[VS_FORWARD_SPEED]);
    // Let the brakes take up the inertia of the spinning wheels, then check that they hold.
    for (int i = 0; i < 15; ++i) tick(0.f, 0.f, 1.f);
    float stoppedSpin = 0.f;
    previousRear = s[rear];
    for (int i = 0; i < 30; ++i) {
        tick(0.f, 0.f, 1.f);
        stoppedSpin += std::abs(std::remainder(s[rear] - previousRear, 6.2831853f));
        previousRear = s[rear];
    }
    std::printf("burnout: front %.2f rad, rear %.2f rad, body %.2f m/s, released %.2f rad\n",
        frontTravel, rearTravel, burnoutSpeed, stoppedSpin);
    const bool burnoutOk = rearTravel > frontTravel + 6.f && burnoutSpeed < 2.f && stoppedSpin < .1f;
    float top = 0.f;
    for (int i = 0; i < 60 * 40; ++i) { tick(1.f, 0.f, 0.f); top = std::fmax(top, s[VS_FORWARD_SPEED]); }
    std::printf("top speed after 40 s: %.1f km/h (gear %d, %.0f rpm) at z %.0f m\n", top * 3.6f, (int) s[VS_GEAR], s[VS_RPM], s[VS_Z]);
    float z0 = s[VS_Z]; int t = 0;
    while (s[VS_FORWARD_SPEED] > .05f && t < 60 * 20) { tick(0.f, 0.f, 1.f); t++; }
    std::printf("stopped from top in %.1f s, %.0f m\n", t * dt, s[VS_Z] - z0);
    for (int i = 0; i < 60 * 8; ++i) tick(.3f, 1.f, 0.f);
    std::printf("turning right at %.1f km/h: at (%.1f, %.1f)\n", s[VS_FORWARD_SPEED] * 3.6f, s[VS_X], s[VS_Z]);
    std::printf("step: %.3f ms average over %d steps (1 car, 1 box)\n", ns / steps / 1e6, steps);
    worldDestroy(w);
    return burnoutOk && top * 3.6f > 100.f ? 0 : 1;
}
