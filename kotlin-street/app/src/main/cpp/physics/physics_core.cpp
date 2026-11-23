// Timerpg's physics on Jolt (see physics_core.h and docs/VEHICLES.md).
#include "physics_core.h"

#include <Jolt/Jolt.h>
#include <Jolt/RegisterTypes.h>
#include <Jolt/Core/Factory.h>
#include <Jolt/Core/TempAllocator.h>
#include <Jolt/Core/JobSystemSingleThreaded.h>
#include <Jolt/Physics/PhysicsSettings.h>
#include <Jolt/Physics/PhysicsSystem.h>
#include <Jolt/Physics/Body/BodyCreationSettings.h>
#include <Jolt/Physics/Collision/Shape/BoxShape.h>
#include <Jolt/Physics/Collision/Shape/CylinderShape.h>
#include <Jolt/Physics/Collision/Shape/HeightFieldShape.h>
#include <Jolt/Physics/Collision/Shape/StaticCompoundShape.h>
#include <Jolt/Physics/Collision/Shape/OffsetCenterOfMassShape.h>
#include <Jolt/Physics/Vehicle/VehicleConstraint.h>
#include <Jolt/Physics/Vehicle/VehicleCollisionTester.h>
#include <Jolt/Physics/Vehicle/WheeledVehicleController.h>

#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

JPH_SUPPRESS_WARNINGS

using namespace JPH;

namespace timerpg {

namespace {

namespace Layers {
constexpr ObjectLayer STATIC = 0;
constexpr ObjectLayer MOVING = 1;
constexpr ObjectLayer COUNT = 2;
}

namespace BPLayers {
constexpr BroadPhaseLayer STATIC(0);
constexpr BroadPhaseLayer MOVING(1);
constexpr uint COUNT = 2;
}

class ObjectPairs final : public ObjectLayerPairFilter {
public:
    bool ShouldCollide(ObjectLayer a, ObjectLayer b) const override { return a == Layers::MOVING || b == Layers::MOVING; }
};

class BroadPhases final : public BroadPhaseLayerInterface {
public:
    uint GetNumBroadPhaseLayers() const override { return BPLayers::COUNT; }
    BroadPhaseLayer GetBroadPhaseLayer(ObjectLayer l) const override { return l == Layers::STATIC ? BPLayers::STATIC : BPLayers::MOVING; }
#if defined(JPH_EXTERNAL_PROFILE) || defined(JPH_PROFILE_ENABLED)
    const char *GetBroadPhaseLayerName(BroadPhaseLayer l) const override { return l == BPLayers::STATIC ? "STATIC" : "MOVING"; }
#endif
};

class ObjectVsBroadPhase final : public ObjectVsBroadPhaseLayerFilter {
public:
    bool ShouldCollide(ObjectLayer a, BroadPhaseLayer b) const override { return a == Layers::MOVING || b == BPLayers::MOVING; }
};

std::once_flag joltOnce;

void initJolt() {
    std::call_once(joltOnce, [] {
        RegisterDefaultAllocator();
        Factory::sInstance = new Factory();
        RegisterTypes();
    });
}

struct Vehicle {
    BodyID body;
    Ref<VehicleConstraint> constraint;
    Ref<VehicleCollisionTester> tester;
    float wheelY = 0.f;     // the axle height at rest
    float previousForward = 1.f;
    bool live = false;
};

}  // namespace

class World {
public:
    explicit World(int maxBodies)
        : temp(4 * 1024 * 1024), jobs(cMaxPhysicsJobs) {
        const uint n = (uint) std::max(64, maxBodies);
        system.Init(n, 0, n, n, broadPhases, objectVsBroadPhase, objectPairs);
        system.SetGravity(Vec3(0.f, -9.81f, 0.f));
    }

    ~World() {
        BodyInterface &bi = system.GetBodyInterface();
        for (auto &v : vehicles) if (v.live) dropVehicle(v);
        for (auto &b : bodies) if (!b.IsInvalid()) { bi.RemoveBody(b); bi.DestroyBody(b); }
    }

    int keep(BodyID id) {
        for (size_t i = 0; i < bodies.size(); ++i) if (bodies[i].IsInvalid()) { bodies[i] = id; return (int) i; }
        bodies.push_back(id);
        return (int) bodies.size() - 1;
    }

    int addStatic(const Shape *shape, RVec3 at, Quat rot, float friction) {
        BodyCreationSettings s(shape, at, rot, EMotionType::Static, Layers::STATIC);
        s.mFriction = friction;
        BodyInterface &bi = system.GetBodyInterface();
        Body *b = bi.CreateBody(s);
        if (b == nullptr) return -1;
        bi.AddBody(b->GetID(), EActivation::DontActivate);
        statics++;
        return keep(b->GetID());
    }

    void remove(int h) {
        if (h < 0 || h >= (int) bodies.size() || bodies[h].IsInvalid()) return;
        BodyInterface &bi = system.GetBodyInterface();
        bi.RemoveBody(bodies[h]);
        bi.DestroyBody(bodies[h]);
        bodies[h] = BodyID();
        statics--;
    }

    void dropVehicle(Vehicle &v) {
        system.RemoveStepListener(v.constraint);
        system.RemoveConstraint(v.constraint);
        BodyInterface &bi = system.GetBodyInterface();
        bi.RemoveBody(v.body);
        bi.DestroyBody(v.body);
        v.constraint = nullptr;
        v.tester = nullptr;
        v.live = false;
    }

    PhysicsSystem system;
    TempAllocatorImpl temp;
    JobSystemSingleThreaded jobs;
    BroadPhases broadPhases;
    ObjectVsBroadPhase objectVsBroadPhase;
    ObjectPairs objectPairs;
    std::vector<BodyID> bodies;
    std::vector<Vehicle> vehicles;
    int statics = 0;
    // a heightfield's samples are copied by Jolt: no need to keep them
};

World *worldCreate(int maxBodies) {
    initJolt();
    return new World(maxBodies);
}

void worldDestroy(World *w) { delete w; }

static Quat yawQuat(float yaw) { return Quat::sRotation(Vec3::sAxisY(), yaw); }

int addBox(World *w, float cx, float cy, float cz, float hx, float hy, float hz, float yaw, float friction) {
    if (hx <= 0.f || hy <= 0.f || hz <= 0.f) return -1;
    // (a convex radius no bigger than a third of the thinnest side: a 6 cm fence board stays a box)
    float r = std::min(cDefaultConvexRadius, std::min(hx, std::min(hy, hz)) * .3f);
    BoxShapeSettings s(Vec3(hx, hy, hz), r);
    auto res = s.Create();
    if (res.HasError()) return -1;
    return w->addStatic(res.Get(), RVec3(cx, cy, cz), yawQuat(yaw), friction);
}

int addCylinder(World *w, float cx, float cy, float cz, float radius, float halfHeight, float friction) {
    if (radius <= 0.f || halfHeight <= 0.f) return -1;
    float r = std::min(cDefaultConvexRadius, std::min(radius, halfHeight) * .3f);
    CylinderShapeSettings s(halfHeight, radius, r);
    auto res = s.Create();
    if (res.HasError()) return -1;
    return w->addStatic(res.Get(), RVec3(cx, cy, cz), Quat::sIdentity(), friction);
}

int addHeightfield(World *w, float x0, float z0, int n, float cell, const float *heights, float friction) {
    if (n < 4 || cell <= 0.f) return -1;
    HeightFieldShapeSettings s(heights, Vec3(0.f, 0.f, 0.f), Vec3(cell, 1.f, cell), (uint32) n);
    s.mBlockSize = 4;
    // (Jolt wants the sample count a multiple of the block size: pad by repeating the far edge)
    std::vector<float> padded;
    uint32 need = ((uint32) n + s.mBlockSize - 1) / s.mBlockSize * s.mBlockSize;
    if (need != (uint32) n) {
        padded.resize((size_t) need * need);
        for (uint32 z = 0; z < need; ++z)
            for (uint32 x = 0; x < need; ++x)
                padded[z * need + x] = heights[std::min<uint32>(z, n - 1) * n + std::min<uint32>(x, n - 1)];
        s.mHeightSamples.assign(padded.begin(), padded.end());
        s.mSampleCount = need;
    }
    auto res = s.Create();
    if (res.HasError()) return -1;
    return w->addStatic(res.Get(), RVec3(x0, 0.f, z0), Quat::sIdentity(), friction);
}

void removeBody(World *w, int handle) { w->remove(handle); }

int bodyCount(World *w) { return (int) w->system.GetNumBodies(); }

int addVehicle(World *w, const float *d, const float *boxes, int boxCount, float x, float y, float z, float yaw) {
    // the chassis: its collision boxes in one compound, the centre of mass lowered to VD_COM_Y
    StaticCompoundShapeSettings compound;
    float minY = 1e9f, maxY = -1e9f;
    for (int i = 0; i < boxCount; ++i) {
        const float *b = boxes + i * 6;
        Vec3 half(std::max(.05f, b[3] * .5f), std::max(.05f, b[4] * .5f), std::max(.05f, b[5] * .5f));
        compound.AddShape(Vec3(b[0], b[1], b[2]), Quat::sIdentity(), new BoxShape(half, std::min(cDefaultConvexRadius, half.ReduceMin() * .3f)));
        minY = std::min(minY, b[1] - half.GetY());
        maxY = std::max(maxY, b[1] + half.GetY());
    }
    if (boxCount == 0) {
        compound.AddShape(Vec3(0.f, .8f, 0.f), Quat::sIdentity(), new BoxShape(Vec3(.9f, .4f, 2.2f)));
        minY = .4f; maxY = 1.2f;
    }
    auto cres = compound.Create();
    if (cres.HasError()) return -1;
    RefConst<Shape> chassis = cres.Get();
    Vec3 com = chassis->GetCenterOfMass();
    auto ores = OffsetCenterOfMassShapeSettings(Vec3(0.f, d[VD_COM_Y] - com.GetY(), 0.f), chassis).Create();
    if (ores.HasError()) return -1;

    BodyCreationSettings bs(ores.Get(), RVec3(x, y, z), yawQuat(yaw), EMotionType::Dynamic, Layers::MOVING);
    bs.mOverrideMassProperties = EOverrideMassProperties::CalculateInertia;
    bs.mMassPropertiesOverride.mMass = d[VD_MASS];
    bs.mLinearDamping = d[VD_LINEAR_DAMPING];
    bs.mAngularDamping = .05f;
    bs.mFriction = .4f;
    bs.mRestitution = .05f;
    // fast cars against thin fences: no tunnelling
    bs.mMotionQuality = EMotionQuality::LinearCast;
    BodyInterface &bi = w->system.GetBodyInterface();
    Body *body = bi.CreateBody(bs);
    if (body == nullptr) return -1;
    bi.AddBody(body->GetID(), EActivation::Activate);

    VehicleConstraintSettings vs;
    vs.mUp = Vec3::sAxisY();
    vs.mForward = Vec3::sAxisZ();
    vs.mMaxPitchRollAngle = DegreesToRadians(60.f);
    const float susMin = d[VD_SUSP_MIN], susMax = d[VD_SUSP_MAX];
    // the suspension's top: where the wheel's centre would be at full stretch is the axle at rest plus half the travel up
    const float rest = .5f * (susMin + susMax);
    const float attachY = d[VD_WHEEL_Y] + rest;
    const float wx = d[VD_WHEEL_X];
    const float zs[4] = {d[VD_FRONT_Z], d[VD_FRONT_Z], d[VD_REAR_Z], d[VD_REAR_Z]};
    const float xs[4] = {wx, -wx, wx, -wx};
    for (int i = 0; i < 4; ++i) {
        auto *wh = new WheelSettingsWV;
        wh->mPosition = Vec3(xs[i], attachY, zs[i]);
        wh->mSuspensionDirection = Vec3(0.f, -1.f, 0.f);
        wh->mSteeringAxis = Vec3(0.f, 1.f, 0.f);
        wh->mWheelUp = Vec3(0.f, 1.f, 0.f);
        wh->mWheelForward = Vec3(0.f, 0.f, 1.f);
        wh->mSuspensionMinLength = susMin;
        wh->mSuspensionMaxLength = susMax;
        wh->mSuspensionSpring.mFrequency = d[VD_SUSP_FREQ];
        wh->mSuspensionSpring.mDamping = d[VD_SUSP_DAMP];
        wh->mRadius = d[VD_WHEEL_RADIUS];
        wh->mWidth = d[VD_WHEEL_WIDTH];
        bool front = i < 2;
        wh->mMaxSteerAngle = front ? d[VD_MAX_STEER] : 0.f;
        wh->mMaxBrakeTorque = d[VD_BRAKE_TORQUE];
        wh->mMaxHandBrakeTorque = front ? 0.f : d[VD_HANDBRAKE_TORQUE];
        // the tyres' grip curves scaled (Jolt's defaults are a modern radial tyre)
        for (auto &p : wh->mLongitudinalFriction.mPoints) p.mY *= d[VD_FRICTION_LONG];
        for (auto &p : wh->mLateralFriction.mPoints) p.mY *= d[VD_FRICTION_LAT];
        vs.mWheels.push_back(wh);
    }
    vs.mAntiRollBars.resize(2);
    vs.mAntiRollBars[0].mLeftWheel = 0; vs.mAntiRollBars[0].mRightWheel = 1;
    vs.mAntiRollBars[1].mLeftWheel = 2; vs.mAntiRollBars[1].mRightWheel = 3;

    auto *c = new WheeledVehicleControllerSettings;
    vs.mController = c;
    c->mEngine.mMaxTorque = d[VD_ENGINE_TORQUE];
    c->mEngine.mMinRPM = d[VD_ENGINE_MIN_RPM];
    c->mEngine.mMaxRPM = d[VD_ENGINE_MAX_RPM];
    c->mTransmission.mMode = ETransmissionMode::Auto;
    c->mTransmission.mGearRatios.clear();
    int gears = std::clamp((int) d[VD_GEARS], 1, 6);
    for (int g = 0; g < gears; ++g) c->mTransmission.mGearRatios.push_back(d[VD_GEAR1 + g]);
    c->mTransmission.mReverseGearRatios = {d[VD_REVERSE]};
    c->mTransmission.mShiftUpRPM = d[VD_SHIFT_UP_RPM];
    c->mTransmission.mShiftDownRPM = d[VD_SHIFT_DOWN_RPM];
    int drive = (int) d[VD_DRIVE];
    if (drive == 2) {
        c->mDifferentials.resize(2);
        c->mDifferentials[0].mLeftWheel = 0; c->mDifferentials[0].mRightWheel = 1;
        c->mDifferentials[1].mLeftWheel = 2; c->mDifferentials[1].mRightWheel = 3;
        c->mDifferentials[0].mEngineTorqueRatio = c->mDifferentials[1].mEngineTorqueRatio = .5f;
        c->mDifferentials[0].mDifferentialRatio = c->mDifferentials[1].mDifferentialRatio = d[VD_DIFF_RATIO];
    } else {
        c->mDifferentials.resize(1);
        c->mDifferentials[0].mLeftWheel = drive == 1 ? 0 : 2;
        c->mDifferentials[0].mRightWheel = drive == 1 ? 1 : 3;
        c->mDifferentials[0].mDifferentialRatio = d[VD_DIFF_RATIO];
    }

    Vehicle v;
    v.body = body->GetID();
    v.constraint = new VehicleConstraint(*body, vs);
    // a cylinder cast: the tyre rolls up a kerb's edge instead of a ray snagging on it
    v.tester = new VehicleCollisionTesterCastCylinder(Layers::MOVING);
    v.constraint->SetVehicleCollisionTester(v.tester);
    v.wheelY = d[VD_WHEEL_Y];
    v.live = true;
    w->system.AddConstraint(v.constraint);
    w->system.AddStepListener(v.constraint);
    for (size_t i = 0; i < w->vehicles.size(); ++i)
        if (!w->vehicles[i].live) { w->vehicles[i] = v; return (int) i; }
    w->vehicles.push_back(v);
    return (int) w->vehicles.size() - 1;
}

static Vehicle *vehicleOf(World *w, int h) {
    if (h < 0 || h >= (int) w->vehicles.size() || !w->vehicles[h].live) return nullptr;
    return &w->vehicles[h];
}

void removeVehicle(World *w, int vehicle) {
    if (Vehicle *v = vehicleOf(w, vehicle)) w->dropVehicle(*v);
}

void setVehicleInput(World *w, int vehicle, float forward, float right, float brake, float handbrake) {
    Vehicle *v = vehicleOf(w, vehicle);
    if (v == nullptr) return;
    auto *c = static_cast<WheeledVehicleController *>(v->constraint->GetController());
    forward = std::clamp(forward, -1.f, 1.f);
    right = std::clamp(right, -1.f, 1.f);
    brake = std::clamp(brake, 0.f, 1.f);
    handbrake = std::clamp(handbrake, 0.f, 1.f);
    if (forward != 0.f || right != 0.f || brake != 0.f || handbrake != 0.f)
        w->system.GetBodyInterface().ActivateBody(v->body);
    c->SetDriverInput(forward, right, brake, handbrake);
}

void setVehiclePose(World *w, int vehicle, float x, float y, float z, float yaw) {
    Vehicle *v = vehicleOf(w, vehicle);
    if (v == nullptr) return;
    BodyInterface &bi = w->system.GetBodyInterface();
    bi.SetPositionAndRotation(v->body, RVec3(x, y, z), yawQuat(yaw), EActivation::Activate);
    bi.SetLinearAndAngularVelocity(v->body, Vec3::sZero(), Vec3::sZero());
}

void vehicleState(World *w, int vehicle, float *o) {
    for (int i = 0; i < VS_COUNT; ++i) o[i] = 0.f;
    Vehicle *v = vehicleOf(w, vehicle);
    if (v == nullptr) return;
    BodyInterface &bi = w->system.GetBodyInterfaceNoLock();
    RVec3 p; Quat q;
    bi.GetPositionAndRotation(v->body, p, q);
    Vec3 lv = bi.GetLinearVelocity(v->body);
    o[VS_X] = (float) p.GetX(); o[VS_Y] = (float) p.GetY(); o[VS_Z] = (float) p.GetZ();
    o[VS_QX] = q.GetX(); o[VS_QY] = q.GetY(); o[VS_QZ] = q.GetZ(); o[VS_QW] = q.GetW();
    o[VS_VX] = lv.GetX(); o[VS_VY] = lv.GetY(); o[VS_VZ] = lv.GetZ();
    o[VS_FORWARD_SPEED] = (q.Conjugated() * lv).GetZ();
    auto *c = static_cast<WheeledVehicleController *>(v->constraint->GetController());
    o[VS_RPM] = c->GetEngine().GetCurrentRPM();
    o[VS_GEAR] = (float) c->GetTransmission().GetCurrentGear();
    o[VS_ACTIVE] = bi.IsActive(v->body) ? 1.f : 0.f;
    const auto &wheels = v->constraint->GetWheels();
    for (int i = 0; i < 4 && i < (int) wheels.size(); ++i) {
        const Wheel *wh = wheels[i];
        float *k = o + VS_WHEELS + i * VW_COUNT;
        k[VW_ANGLE] = wh->GetRotationAngle();
        k[VW_STEER] = wh->GetSteerAngle();
        k[VW_SUSPENSION] = wh->GetSuspensionLength();
        k[VW_CONTACT] = wh->HasContact() ? 1.f : 0.f;
    }
}

void step(World *w, float dt, int collisionSteps) {
    w->system.Update(dt, std::max(1, collisionSteps), &w->temp, &w->jobs);
}

}  // namespace timerpg
