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
#include <cstring>
#include <functional>
#include <unordered_map>

JPH_SUPPRESS_WARNINGS

using namespace JPH;

namespace timerpg {

namespace {

namespace Layers {
constexpr ObjectLayer STATIC = 0;
constexpr ObjectLayer MOVING = 1;
// loose pieces (a fence's panels, a fallen lamp post): they meet everything but the wheels' ground casts
constexpr ObjectLayer DEBRIS = 2;
// the wheels' ground casts (a query layer: no body is in it): the ground, the statics and the other cars
constexpr ObjectLayer WHEEL = 3;
constexpr ObjectLayer COUNT = 4;
}

namespace BPLayers {
constexpr BroadPhaseLayer STATIC(0);
constexpr BroadPhaseLayer MOVING(1);
constexpr uint COUNT = 2;
}

class ObjectPairs final : public ObjectLayerPairFilter {
public:
    bool ShouldCollide(ObjectLayer a, ObjectLayer b) const override {
        if (a == Layers::STATIC && b == Layers::STATIC) return false;
        if (a == Layers::WHEEL || b == Layers::WHEEL) { ObjectLayer o = a == Layers::WHEEL ? b : a; return o == Layers::STATIC || o == Layers::MOVING; }
        return true;
    }
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
    bool ShouldCollide(ObjectLayer a, BroadPhaseLayer b) const override { return a != Layers::STATIC || b == BPLayers::MOVING; }
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
    float downforce = 0.f;  // N per (m/s)^2 (VD_DOWNFORCE)
    float downFront = .5f;  // its front share
    float frontZ = 0.f, rearZ = 0.f;
    float previousForward = 1.f;
    // the upshifts, ours (stepGearbox): the rpm at no and at full throttle, the torque cut's length and what is left of it,
    // the driver's input as given (the throttle is eased back in over the cut), the driven wheels
    float shiftUp = 0.f, shiftUpFull = 0.f, shiftTime = .25f, cutLeft = 0.f;
    float inForward = 0.f, inRight = 0.f, inBrake = 0.f, inHand = 0.f;
    int drive = 0;
    bool live = false;
};

/** A static body's give (setGive), by handle. */
struct Give {
    float impulse = 0.f;    // N s to break it (0: never)
    float soft = 0.f;       // drag (1/s) on what passes through it (0: solid)
    bool broken = false;    // broken this step or since: every contact with it is a sensor until it is removed
};

struct Break { float f[BR_COUNT]; };

/**
 * A loose piece's touch with a car (by the piece's handle): the steps it began and was last seen at, and whether it now
 * passes through the car (no response) until they part.
 */
struct Touch {
    uint32 first = 0;
    uint32 last = 0;
    bool pass = false;
};

// A piece meeting a car: passes through it (a sensor) when it lies lower than this over the car's ground (m: the car
// rides over a board, as a real one's tyres and bumper would), when the two overlap deeper than DEBRIS_DEEP (m: a piece
// put down inside the body, which pushing out would wedge), or once they have touched for DEBRIS_LONG steps on end (it
// is being pushed along or dragged); until they have been apart DEBRIS_APART steps. Their friction: a car's paint.
constexpr float DEBRIS_LOW = .3f;
constexpr float DEBRIS_DEEP = .1f;
constexpr uint32 DEBRIS_LONG = 40;
constexpr uint32 DEBRIS_APART = 3;
constexpr float DEBRIS_ON_CAR_FRICTION = .15f;

}  // namespace

class World;

/** The contacts with breakable and soft statics (one thread: JobSystemSingleThreaded). */
class Breaker final : public ContactListener {
public:
    explicit Breaker(World *w) : w(w) {}
    void OnContactAdded(const Body &a, const Body &b, const ContactManifold &m, ContactSettings &s) override { check(a, b, m, s); }
    void OnContactPersisted(const Body &a, const Body &b, const ContactManifold &m, ContactSettings &s) override { check(a, b, m, s); }
    void check(const Body &a, const Body &b, const ContactManifold &m, ContactSettings &s);
    void debrisOnCar(const Body &d, const Body &car, const ContactManifold &m, ContactSettings &s);
    World *w;
};

class World {
public:
    explicit World(int maxBodies)
        : temp(4 * 1024 * 1024), jobs(cMaxPhysicsJobs) {
        const uint n = (uint) std::max(64, maxBodies);
        // (the body pairs and contacts are the cars' and the debris's with what they touch: a few thousand at most however
        // many statics stand round them; their buffers come from the 4 MB step allocator, which 32,768 of each overran)
        const uint pairs = std::min(n, 4096u);
        system.Init(n, 0, pairs, pairs, broadPhases, objectVsBroadPhase, objectPairs);
        system.SetGravity(Vec3(0.f, -9.81f, 0.f));
        system.SetContactListener(&breaker);
    }

    ~World() {
        BodyInterface &bi = system.GetBodyInterface();
        for (auto &v : vehicles) if (v.live) dropVehicle(v);
        for (auto &b : bodies) if (!b.IsInvalid()) { bi.RemoveBody(b); bi.DestroyBody(b); }
    }

    int keep(BodyID id, bool isStatic) {
        int h = -1;
        // (the lowest free slot, as the scan over every slot it replaces found: a min-heap of the freed ones, so a streamed
        // cell's thousand statics are not a thousand scans of every body)
        if (!freeSlots.empty()) {
            std::pop_heap(freeSlots.begin(), freeSlots.end(), std::greater<int>());
            h = freeSlots.back(); freeSlots.pop_back();
            bodies[h] = id;
        }
        if (h < 0) { bodies.push_back(id); gives.emplace_back(); staticFlags.push_back(0); touches.emplace_back(); h = (int) bodies.size() - 1; }
        gives[h] = Give();
        touches[h] = Touch();
        staticFlags[h] = isStatic ? 1 : 0;
        // (the handle + 1 in its user data: the contact listener finds its give by it)
        system.GetBodyInterface().SetUserData(id, (uint64) h + 1);
        return h;
    }

    int addStatic(const Shape *shape, RVec3 at, Quat rot, float friction) {
        BodyCreationSettings s(shape, at, rot, EMotionType::Static, Layers::STATIC);
        s.mFriction = friction;
        BodyInterface &bi = system.GetBodyInterface();
        Body *b = bi.CreateBody(s);
        if (b == nullptr) return -1;
        bi.AddBody(b->GetID(), EActivation::DontActivate);
        statics++;
        return keep(b->GetID(), true);
    }

    void remove(int h) {
        if (h < 0 || h >= (int) bodies.size() || bodies[h].IsInvalid()) return;
        BodyInterface &bi = system.GetBodyInterface();
        bi.RemoveBody(bodies[h]);
        bi.DestroyBody(bodies[h]);
        forget(h);
    }

    /** Slot [h] is free again (its body already removed and destroyed). */
    void forget(int h) {
        bodies[h] = BodyID();
        if (staticFlags[h]) statics--;
        gives[h] = Give();
        touches[h] = Touch();
        freeSlots.push_back(h);
        std::push_heap(freeSlots.begin(), freeSlots.end(), std::greater<int>());
    }

    /**
     * A shape for a static of `kind` (0 box, 1 upright cylinder) and these sizes, made once and shared by every body of the
     * same sizes (a wood's trunks come in a few dozen sizes: [addStatics] rounds them); null if Jolt refuses it.
     */
    const Shape *sharedShape(int kind, float a, float b, float c) {
        uint32 ka, kb, kc;
        std::memcpy(&ka, &a, 4); std::memcpy(&kb, &b, 4); std::memcpy(&kc, &c, 4);
        const uint64 key = ((uint64) kind << 62) ^ ((uint64) ka << 31) ^ ((uint64) kb << 11) ^ (uint64) kc * 0x9E3779B97F4A7C15ull;
        auto it = shapes.find(key);
        if (it != shapes.end() && it->second.kind == kind && it->second.a == a && it->second.b == b && it->second.c == c) return it->second.shape.GetPtr();
        // (a bound on the cache: the shapes in use stay alive through their bodies)
        if (shapes.size() > 8192) shapes.clear();
        ShapeSettings::ShapeResult res;
        if (kind == 0) {
            float r = std::min(cDefaultConvexRadius, std::min(a, std::min(b, c)) * .3f);
            res = BoxShapeSettings(Vec3(a, b, c), r).Create();
        } else {
            float r = std::min(cDefaultConvexRadius, std::min(a, b) * .3f);
            res = CylinderShapeSettings(b, a, r).Create();
        }
        if (res.HasError()) return nullptr;
        SharedShape &e = shapes[key];
        e.kind = kind; e.a = a; e.b = b; e.c = c; e.shape = res.Get();
        return e.shape.GetPtr();
    }
    struct SharedShape { int kind = -1; float a = 0.f, b = 0.f, c = 0.f; RefConst<Shape> shape; };
    std::unordered_map<uint64, SharedShape> shapes;
    std::vector<int> freeSlots;

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
    std::vector<Give> gives;
    std::vector<uint8_t> staticFlags;
    std::vector<Touch> touches;
    std::vector<Vehicle> vehicles;
    // the steps taken (a piece's touch with a car is timed by them)
    uint32 stepNo = 1;
    int statics = 0;
    Breaker breaker{this};
    // this step's breaks (taken by takeBreaks) and the bodies slowed by soft statics (applied after the step)
    Break breaks[MAX_BREAKS];
    int breakCount = 0;
    static constexpr int MAX_DRAGS = 32;
    BodyID dragBody[MAX_DRAGS];
    float dragK[MAX_DRAGS];
    int dragCount = 0;
    // a heightfield's samples are copied by Jolt: no need to keep them
};

void Breaker::check(const Body &a, const Body &b, const ContactManifold &m, ContactSettings &s) {
    const Body *st = a.IsStatic() ? &a : (b.IsStatic() ? &b : nullptr);
    if (st == nullptr) {
        // a loose piece and a car (the cars are the only bodies in MOVING)
        if (a.GetObjectLayer() == Layers::DEBRIS && b.GetObjectLayer() == Layers::MOVING) debrisOnCar(a, b, m, s);
        else if (b.GetObjectLayer() == Layers::DEBRIS && a.GetObjectLayer() == Layers::MOVING) debrisOnCar(b, a, m, s);
        return;
    }
    const Body *dy = st == &a ? &b : &a;
    if (!dy->IsDynamic()) return;
    uint64 ud = st->GetUserData();
    if (ud == 0 || ud > w->gives.size()) return;
    const int h = (int) ud - 1;
    Give &g = w->gives[h];
    if (g.soft > 0.f) {
        s.mIsSensor = true;
        int k = 0;
        while (k < w->dragCount && w->dragBody[k] != dy->GetID()) k++;
        if (k == w->dragCount && k < World::MAX_DRAGS) { w->dragBody[k] = dy->GetID(); w->dragK[k] = 0.f; w->dragCount++; }
        if (k < World::MAX_DRAGS) w->dragK[k] = std::max(w->dragK[k], g.soft);
        return;
    }
    if (g.impulse <= 0.f) return;
    if (g.broken) { s.mIsSensor = true; return; }
    const RVec3 p = m.GetWorldSpaceContactPointOn1(0);
    const Vec3 v = dy->GetPointVelocity(p);
    float vn = std::abs(v.Dot(m.mWorldSpaceNormal));
    // (a car catching a fence's end or scraping along it tears it too: half its speed on the ground counts, whichever way)
    if (dy->GetObjectLayer() == Layers::MOVING) vn = std::max(vn, .5f * std::sqrt(v.GetX() * v.GetX() + v.GetZ() * v.GetZ()));
    const float inv = dy->GetMotionProperties()->GetInverseMass();
    const float mass = inv > 0.f ? 1.f / inv : 0.f;
    if (mass * vn < g.impulse) return;
    g.broken = true;
    s.mIsSensor = true;
    if (w->breakCount >= MAX_BREAKS) return;
    float *o = w->breaks[w->breakCount++].f;
    const Vec3 lv = dy->GetLinearVelocity();
    o[BR_HANDLE] = (float) h;
    o[BR_X] = (float) p.GetX(); o[BR_Y] = (float) p.GetY(); o[BR_Z] = (float) p.GetZ();
    o[BR_VX] = lv.GetX(); o[BR_VY] = lv.GetY(); o[BR_VZ] = lv.GetZ();
    o[BR_SPEED] = vn;
    o[BR_MASS] = mass;
    o[BR_VEHICLE] = -1.f;
    for (size_t i = 0; i < w->vehicles.size(); ++i) if (w->vehicles[i].live && w->vehicles[i].body == dy->GetID()) o[BR_VEHICLE] = (float) i;
}

void Breaker::debrisOnCar(const Body &d, const Body &car, const ContactManifold &m, ContactSettings &s) {
    s.mCombinedFriction = std::min(s.mCombinedFriction, DEBRIS_ON_CAR_FRICTION);
    const uint64 ud = d.GetUserData();
    if (ud == 0 || ud > w->touches.size()) return;
    Touch &t = w->touches[(size_t) ud - 1];
    const uint32 now = w->stepNo;
    // (a new touch: they were apart a few steps; contacts of one step all count as one)
    if (t.last + DEBRIS_APART < now) { t.first = now; t.pass = false; }
    t.last = now;
    if (!t.pass) {
        // the car's ground: its origin is on the ground between the axles
        const float ground = (float) car.GetPosition().GetY();
        const float top = d.GetWorldSpaceBounds().mMax.GetY();
        if (top < ground + DEBRIS_LOW || m.mPenetrationDepth > DEBRIS_DEEP || now - t.first > DEBRIS_LONG) t.pass = true;
    }
    if (t.pass) s.mIsSensor = true;
}

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
    // (Jolt wants the sample count a multiple of the block size: the padding is holes, no collision, so the field ends
    // at its last sample. It used to repeat the far edge: a flat shelf of up to 3 cells reaching into the next streamed
    // chunk at the edge's height, which a car rode out onto and dropped off wherever the ground fell away:
    // HeightfieldSeamTest)
    std::vector<float> padded;
    uint32 need = ((uint32) n + s.mBlockSize - 1) / s.mBlockSize * s.mBlockSize;
    if (need != (uint32) n) {
        padded.resize((size_t) need * need);
        for (uint32 z = 0; z < need; ++z)
            for (uint32 x = 0; x < need; ++x)
                padded[z * need + x] = x < (uint32) n && z < (uint32) n ? heights[z * n + x] : HeightFieldShapeConstants::cNoCollisionValue;
        s.mHeightSamples.assign(padded.begin(), padded.end());
        s.mSampleCount = need;
    }
    auto res = s.Create();
    if (res.HasError()) return -1;
    return w->addStatic(res.Get(), RVec3(x0, 0.f, z0), Quat::sIdentity(), friction);
}

void removeBody(World *w, int handle) { w->remove(handle); }

int addStatics(World *w, const float *d, int n, int *out) {
    BodyInterface &bi = w->system.GetBodyInterface();
    std::vector<BodyID> ids;
    ids.reserve((size_t) std::max(0, n));
    int made = 0;
    for (int i = 0; i < n; ++i) {
        const float *r = d + (size_t) i * ST_COUNT;
        out[i] = -1;
        const int kind = (int) r[ST_KIND];
        const float a = r[ST_HX], b = r[ST_HY], c = r[ST_HZ];
        if (a <= 0.f || b <= 0.f || (kind == 0 && c <= 0.f)) continue;
        const Shape *shape = w->sharedShape(kind, a, b, kind == 0 ? c : 0.f);
        if (shape == nullptr) continue;
        BodyCreationSettings s(shape, RVec3(r[ST_X], r[ST_Y], r[ST_Z]), kind == 0 ? yawQuat(r[ST_YAW]) : Quat::sIdentity(), EMotionType::Static, Layers::STATIC);
        s.mFriction = r[ST_FRICTION];
        Body *body = bi.CreateBody(s);
        if (body == nullptr) break;     // (the world is full: the rest are not made)
        ids.push_back(body->GetID());
        w->statics++;
        out[i] = w->keep(body->GetID(), true);
        made++;
    }
    if (!ids.empty()) {
        // (one batch into the broad phase: Jolt builds the batch's own tree and merges it in, instead of a body at a time)
        BodyInterface::AddState st = bi.AddBodiesPrepare(ids.data(), (int) ids.size());
        bi.AddBodiesFinalize(ids.data(), (int) ids.size(), st, EActivation::DontActivate);
    }
    return made;
}

void removeBodies(World *w, const int *handles, int n) {
    BodyInterface &bi = w->system.GetBodyInterface();
    std::vector<BodyID> ids;
    std::vector<int> hs;
    ids.reserve((size_t) std::max(0, n)); hs.reserve((size_t) std::max(0, n));
    for (int i = 0; i < n; ++i) {
        const int h = handles[i];
        if (h < 0 || h >= (int) w->bodies.size() || w->bodies[h].IsInvalid()) continue;
        ids.push_back(w->bodies[h]); hs.push_back(h);
        // (a handle twice in the list: once)
        w->bodies[h] = BodyID();
    }
    if (ids.empty()) return;
    bi.RemoveBodies(ids.data(), (int) ids.size());
    bi.DestroyBodies(ids.data(), (int) ids.size());
    for (int h : hs) w->forget(h);
}

void optimizeBroadPhase(World *w) { w->system.OptimizeBroadPhase(); }

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
        // (per axle: the rear's lateral grip its own where given, a race build's balance)
        const float lat = front || d[VD_FRICTION_LAT_REAR] <= 0.f ? d[VD_FRICTION_LAT] : d[VD_FRICTION_LAT_REAR];
        for (auto &p : wh->mLateralFriction.mPoints) p.mY *= lat;
        // the front tyres past their peak: Jolt's curve falls from 1.2 at 3 degrees to 1.0 at 20, so a thumb winding on
        // more lock than the peak needs washes the front out (understeer at the limit); a race front's flatter tail
        // (VD_FRONT_LAT_TAIL of the peak, held to 12 degrees, then easing to Jolt's 20-degree share) keeps it biting
        const float tail = d[VD_FRONT_LAT_TAIL];
        if (front && tail > 0.f) {
            const float peak = 1.2f * lat;
            wh->mLateralFriction.Clear();
            wh->mLateralFriction.AddPoint(0.f, 0.f);
            wh->mLateralFriction.AddPoint(3.f, peak);
            wh->mLateralFriction.AddPoint(12.f, peak * tail);
            wh->mLateralFriction.AddPoint(20.f, peak * std::min(tail, std::max(1.f / 1.2f, tail - .05f)));
        }
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
    // (the upshifts are ours, stepGearbox: Jolt's auto box will not shift up while a driven wheel slips more than 10%,
    // so a car spinning its tyres flat out sat on its limiter for up to two seconds before each shift; its downshifts
    // and its first gear from a stop stay Jolt's)
    c->mTransmission.mShiftUpRPM = 1e9f;
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
    // (in the wheels' own query layer: the ground, the statics and the other cars, not the debris the chassis pushes)
    v.tester = new VehicleCollisionTesterCastCylinder(Layers::WHEEL);
    v.constraint->SetVehicleCollisionTester(v.tester);
    v.wheelY = d[VD_WHEEL_Y];
    v.downforce = std::max(0.f, d[VD_DOWNFORCE]);
    v.downFront = std::clamp(d[VD_DOWNFORCE_FRONT], 0.f, 1.f);
    v.frontZ = d[VD_FRONT_Z];
    v.rearZ = d[VD_REAR_Z];
    v.shiftUp = d[VD_SHIFT_UP_RPM];
    v.shiftUpFull = d[VD_SHIFT_UP_FULL_RPM] > 0.f ? d[VD_SHIFT_UP_FULL_RPM] : d[VD_SHIFT_UP_RPM];
    v.shiftTime = d[VD_SHIFT_TIME] > 0.f ? d[VD_SHIFT_TIME] : .25f;
    v.drive = drive;
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
    v->inForward = forward; v->inRight = right; v->inBrake = brake; v->inHand = handbrake;
    // (during an upshift's torque cut the throttle is eased back in: stepGearbox)
    const float cut = v->cutLeft > 0.f ? 1.f - v->cutLeft / v->shiftTime : 1.f;
    c->SetDriverInput(forward > 0.f ? forward * cut : forward, right, brake, handbrake);
}

/**
 * One step of a vehicle's upshifts (before Jolt's update): in a forward gear below the top, its driven wheels on the
 * ground, with Jolt's own box idle (not mid-shift, its clutch home), past the shift point (VD_SHIFT_UP_RPM with no
 * throttle .. VD_SHIFT_UP_FULL_RPM flat out) it goes up a gear at once, the clutch staying in (the engine is pulled down
 * to the new gear's speed), and the throttle is cut and eased back in over VD_SHIFT_TIME: an automatic's torque-managed
 * shift. Wheelspin does not hold it back (Jolt's own rule would), so the engine never waits on its limiter.
 */
static void stepGearbox(Vehicle &v, float dt) {
    auto *c = static_cast<WheeledVehicleController *>(v.constraint->GetController());
    VehicleTransmission &tr = c->GetTransmission();
    if (v.cutLeft > 0.f) {
        v.cutLeft = std::max(0.f, v.cutLeft - dt);
        const float cut = 1.f - v.cutLeft / v.shiftTime;
        c->SetDriverInput(v.inForward > 0.f ? v.inForward * cut : v.inForward, v.inRight, v.inBrake, v.inHand);
        return;
    }
    const int g = tr.GetCurrentGear();
    const int n = (int) tr.mGearRatios.size();
    if (g < 1 || g >= n || v.inForward < 0.f || !tr.AllowSleep()) return;
    const float thr = std::clamp(v.inForward, 0.f, 1.f);
    const float up = v.shiftUp + (v.shiftUpFull - v.shiftUp) * thr;
    if (c->GetEngine().GetCurrentRPM() <= up) return;
    // (not in the air: after a jump the engine races and must not run up the box)
    const auto &wheels = v.constraint->GetWheels();
    const int a = v.drive == 1 ? 0 : 2;
    const int b = v.drive == 2 ? 4 : a + 2;
    for (int i = (v.drive == 2 ? 0 : a); i < b && i < (int) wheels.size(); ++i)
        if (!wheels[i]->HasContact()) return;
    tr.Set(g + 1, 1.f);
    v.cutLeft = v.shiftTime;
    c->SetDriverInput(0.f, v.inRight, v.inBrake, v.inHand);
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

int step(World *w, float dt, int collisionSteps) {
    w->dragCount = 0;
    w->stepNo++;
    // aerodynamic downforce (a race car's wing and splitter): k v^2 along the body's down, split over the axles, so the
    // tyres are pressed harder (more grip) the faster it goes; Jolt clears the forces after each update
    {
        BodyInterface &bi = w->system.GetBodyInterfaceNoLock();
        for (Vehicle &v : w->vehicles) {
            if (!v.live || v.downforce <= 0.f || !bi.IsActive(v.body)) continue;
            RVec3 p; Quat q;
            bi.GetPositionAndRotation(v.body, p, q);
            const float fwd = (q.Conjugated() * bi.GetLinearVelocity(v.body)).GetZ();
            const float f = v.downforce * fwd * fwd;
            if (f < 1.f) continue;
            const Vec3 down = q * Vec3(0.f, -1.f, 0.f);
            bi.AddForce(v.body, down * (f * v.downFront), p + q * Vec3(0.f, v.wheelY, v.frontZ));
            bi.AddForce(v.body, down * (f * (1.f - v.downFront)), p + q * Vec3(0.f, v.wheelY, v.rearZ));
        }
    }
    for (Vehicle &v : w->vehicles) if (v.live) stepGearbox(v, dt);
    w->system.Update(dt, std::max(1, collisionSteps), &w->temp, &w->jobs);
    // what passed through something soft this step is slowed (horizontally: a bush does not hold a car up)
    if (w->dragCount > 0) {
        BodyInterface &bi = w->system.GetBodyInterfaceNoLock();
        for (int k = 0; k < w->dragCount; ++k) {
            Vec3 v = bi.GetLinearVelocity(w->dragBody[k]);
            const float f = std::max(0.f, 1.f - w->dragK[k] * dt);
            bi.SetLinearVelocity(w->dragBody[k], Vec3(v.GetX() * f, v.GetY(), v.GetZ() * f));
        }
    }
    return w->breakCount;
}

void setGive(World *w, int h, float impulse, float soft) {
    if (h < 0 || h >= (int) w->bodies.size() || w->bodies[h].IsInvalid()) return;
    Give &g = w->gives[h];
    g.impulse = std::max(0.f, impulse);
    g.soft = std::max(0.f, soft);
    g.broken = false;
}

int takeBreaks(World *w, float *out, int max) {
    const int n = std::min(max, w->breakCount);
    for (int i = 0; i < n; ++i) for (int k = 0; k < BR_COUNT; ++k) out[i * BR_COUNT + k] = w->breaks[i].f[k];
    w->breakCount = 0;
    return n;
}

int addDebris(World *w, float cx, float cy, float cz, float hx, float hy, float hz, float qx, float qy, float qz, float qw,
              float mass, float vx, float vy, float vz, float ax, float ay, float az, float friction) {
    hx = std::max(hx, .01f); hy = std::max(hy, .01f); hz = std::max(hz, .01f);
    float r = std::min(cDefaultConvexRadius, std::min(hx, std::min(hy, hz)) * .3f);
    BoxShapeSettings bs(Vec3(hx, hy, hz), r);
    auto res = bs.Create();
    if (res.HasError()) return -1;
    Quat q(qx, qy, qz, qw);
    q = q.LengthSq() > 1e-8f ? q.Normalized() : Quat::sIdentity();
    BodyCreationSettings s(res.Get(), RVec3(cx, cy, cz), q, EMotionType::Dynamic, Layers::DEBRIS);
    s.mOverrideMassProperties = EOverrideMassProperties::CalculateInertia;
    s.mMassPropertiesOverride.mMass = std::max(.5f, mass);
    s.mFriction = friction;
    s.mRestitution = .1f;
    s.mLinearDamping = .08f;
    s.mAngularDamping = .4f;
    // (thin boards flung fast: no passing through the ground)
    s.mMotionQuality = EMotionQuality::LinearCast;
    s.mLinearVelocity = Vec3(vx, vy, vz);
    s.mAngularVelocity = Vec3(ax, ay, az);
    BodyInterface &bi = w->system.GetBodyInterface();
    Body *b = bi.CreateBody(s);
    if (b == nullptr) return -1;
    bi.AddBody(b->GetID(), EActivation::Activate);
    return w->keep(b->GetID(), false);
}

void bodyPoses(World *w, const int *handles, int n, float *out) {
    BodyInterface &bi = w->system.GetBodyInterfaceNoLock();
    for (int i = 0; i < n; ++i) {
        float *o = out + i * POSE_COUNT;
        const int h = handles[i];
        if (h < 0 || h >= (int) w->bodies.size() || w->bodies[h].IsInvalid()) { for (int k = 0; k < POSE_COUNT; ++k) o[k] = 0.f; o[6] = 1.f; continue; }
        RVec3 p; Quat q;
        bi.GetPositionAndRotation(w->bodies[h], p, q);
        o[0] = (float) p.GetX(); o[1] = (float) p.GetY(); o[2] = (float) p.GetZ();
        o[3] = q.GetX(); o[4] = q.GetY(); o[5] = q.GetZ(); o[6] = q.GetW();
        o[7] = bi.IsActive(w->bodies[h]) ? 1.f : 0.f;
    }
}

void addVehicleVelocity(World *w, int vehicle, float dvx, float dvy, float dvz) {
    Vehicle *v = vehicleOf(w, vehicle);
    if (v == nullptr) return;
    BodyInterface &bi = w->system.GetBodyInterface();
    bi.SetLinearVelocity(v->body, bi.GetLinearVelocity(v->body) + Vec3(dvx, dvy, dvz));
}

}  // namespace timerpg
