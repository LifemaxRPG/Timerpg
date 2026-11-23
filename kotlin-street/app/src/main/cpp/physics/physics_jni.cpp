// The physics on the JVM: com.lifemax.rpg.feature.habits.physics.JoltNative (shared/src/main/kotlin/physics/JoltPhysics.kt),
// the same library on the phone (arm64, NEON) and the desktop (Linux x86-64). Arrays are read and written in place with
// Get/SetFloatArrayRegion: a frame's calls allocate nothing on either side.
#include <jni.h>

#include <vector>

#include "physics_core.h"

using namespace timerpg;

#define FN(name) Java_com_lifemax_rpg_feature_habits_physics_JoltNative_##name

static World *W(jlong h) { return reinterpret_cast<World *>(h); }

extern "C" {

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *, jclass, jint maxBodies) {
    return reinterpret_cast<jlong>(worldCreate(maxBodies));
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *, jclass, jlong w) { worldDestroy(W(w)); }

JNIEXPORT jint JNICALL FN(addBox)(JNIEnv *, jclass, jlong w, jfloat cx, jfloat cy, jfloat cz, jfloat hx, jfloat hy, jfloat hz, jfloat yaw, jfloat friction) {
    return addBox(W(w), cx, cy, cz, hx, hy, hz, yaw, friction);
}

JNIEXPORT jint JNICALL FN(addCylinder)(JNIEnv *, jclass, jlong w, jfloat cx, jfloat cy, jfloat cz, jfloat r, jfloat hh, jfloat friction) {
    return addCylinder(W(w), cx, cy, cz, r, hh, friction);
}

JNIEXPORT jint JNICALL FN(addHeightfield)(JNIEnv *env, jclass, jlong w, jfloat x0, jfloat z0, jint n, jfloat cell, jfloatArray heights, jfloat friction) {
    if (n < 4 || env->GetArrayLength(heights) < n * n) return -1;
    std::vector<float> h((size_t) n * n);
    env->GetFloatArrayRegion(heights, 0, n * n, h.data());
    return addHeightfield(W(w), x0, z0, n, cell, h.data(), friction);
}

JNIEXPORT void JNICALL FN(removeBody)(JNIEnv *, jclass, jlong w, jint h) { removeBody(W(w), h); }

JNIEXPORT jint JNICALL FN(bodyCount)(JNIEnv *, jclass, jlong w) { return bodyCount(W(w)); }

JNIEXPORT jint JNICALL FN(addVehicle)(JNIEnv *env, jclass, jlong w, jfloatArray desc, jfloatArray boxes, jfloat x, jfloat y, jfloat z, jfloat yaw) {
    if (env->GetArrayLength(desc) < VD_COUNT) return -1;
    float d[VD_COUNT];
    env->GetFloatArrayRegion(desc, 0, VD_COUNT, d);
    jsize nb = env->GetArrayLength(boxes) / 6;
    std::vector<float> b((size_t) nb * 6);
    if (nb > 0) env->GetFloatArrayRegion(boxes, 0, nb * 6, b.data());
    return addVehicle(W(w), d, b.data(), nb, x, y, z, yaw);
}

JNIEXPORT void JNICALL FN(removeVehicle)(JNIEnv *, jclass, jlong w, jint v) { removeVehicle(W(w), v); }

JNIEXPORT void JNICALL FN(setVehicleInput)(JNIEnv *, jclass, jlong w, jint v, jfloat forward, jfloat right, jfloat brake, jfloat handbrake) {
    setVehicleInput(W(w), v, forward, right, brake, handbrake);
}

JNIEXPORT void JNICALL FN(setVehiclePose)(JNIEnv *, jclass, jlong w, jint v, jfloat x, jfloat y, jfloat z, jfloat yaw) {
    setVehiclePose(W(w), v, x, y, z, yaw);
}

JNIEXPORT void JNICALL FN(vehicleState)(JNIEnv *env, jclass, jlong w, jint v, jfloatArray out) {
    float o[VS_COUNT];
    vehicleState(W(w), v, o);
    jsize n = env->GetArrayLength(out);
    env->SetFloatArrayRegion(out, 0, n < VS_COUNT ? n : VS_COUNT, o);
}

JNIEXPORT void JNICALL FN(step)(JNIEnv *, jclass, jlong w, jfloat dt, jint collisionSteps) { step(W(w), dt, collisionSteps); }

JNIEXPORT jint JNICALL FN(descCount)(JNIEnv *, jclass) { return VD_COUNT; }
JNIEXPORT jint JNICALL FN(stateCount)(JNIEnv *, jclass) { return VS_COUNT; }

}  // extern "C"
