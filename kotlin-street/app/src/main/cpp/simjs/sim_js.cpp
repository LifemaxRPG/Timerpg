// The game's TypeScript simulation on the JVM (docs/LOOP.md, "The JavaScript bridge"):
// com.lifemax.rpg.feature.habits.simjs.SimJsNative (shared/src/main/kotlin/simjs/SimJs.kt), the same library on the
// phone (arm64) and the desktop (Linux x86-64).
//
// One instance = one QuickJS-ng runtime with one context: evaluate a bundle (assets/sim/sim.js, which sets globals),
// then call a named global function with one JSON string and get a string back. Text crosses as UTF-8 byte arrays (not
// JNI's modified UTF-8), so any character survives. A JS exception (or running out of the memory limit) becomes a
// SimJsException in Kotlin with the error's message and stack. Not thread-safe: one thread at a time (SimJs.kt's
// executor keeps it on one).
#include <jni.h>
#include <quickjs.h>

#include <cstring>
#include <string>
#include <vector>

#define FN(name) Java_com_lifemax_rpg_feature_habits_simjs_SimJsNative_##name

namespace {

struct Sim {
    JSRuntime *rt = nullptr;
    JSContext *ctx = nullptr;
};

Sim *S(jlong h) { return reinterpret_cast<Sim *>(h); }

// A SimJsException with [msg] (UTF-8: passed as bytes, as ThrowNew would read it as modified UTF-8 and garble emoji).
void throwKotlin(JNIEnv *env, const std::string &msg) {
    jclass c = env->FindClass("com/lifemax/rpg/feature/habits/simjs/SimJsException");
    if (!c) return;  // (FindClass has thrown NoClassDefFoundError)
    jmethodID ctor = env->GetMethodID(c, "<init>", "([B)V");
    jbyteArray b = env->NewByteArray((jsize) msg.size());
    if (!ctor || !b) return;
    env->SetByteArrayRegion(b, 0, (jsize) msg.size(), reinterpret_cast<const jbyte *>(msg.data()));
    jobject e = env->NewObject(c, ctor, b);
    if (e) env->Throw(static_cast<jthrowable>(e));
}

std::string str(JSContext *ctx, JSValueConst v) {
    size_t n = 0;
    const char *s = JS_ToCStringLen(ctx, &n, v);
    if (!s) return "(unprintable)";
    std::string out(s, n);
    JS_FreeCString(ctx, s);
    return out;
}

// The pending exception as "Name: message\n<stack>", and cleared.
std::string takeException(JSContext *ctx) {
    JSValue e = JS_GetException(ctx);
    std::string msg = str(ctx, e);
    if (JS_IsError(e)) {
        JSValue stack = JS_GetPropertyStr(ctx, e, "stack");
        if (JS_IsString(stack)) {
            std::string st = str(ctx, stack);
            if (!st.empty()) msg += "\n" + st;
        }
        JS_FreeValue(ctx, stack);
    }
    JS_FreeValue(ctx, e);
    // (an exception thrown while getting the stack)
    JS_FreeValue(ctx, JS_GetException(ctx));
    return msg;
}

// Bytes of a Java byte[], with a zero after them (JS_Eval wants its input zero-terminated).
std::vector<char> bytes(JNIEnv *env, jbyteArray a) {
    jsize n = a ? env->GetArrayLength(a) : 0;
    std::vector<char> v((size_t) n + 1, 0);
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(v.data()));
    return v;
}

jbyteArray toJava(JNIEnv *env, const char *s, size_t n) {
    jbyteArray a = env->NewByteArray((jsize) n);
    if (a && n > 0) env->SetByteArrayRegion(a, 0, (jsize) n, reinterpret_cast<const jbyte *>(s));
    return a;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL FN(create)(JNIEnv *env, jclass, jlong memoryLimit, jlong stackLimit) {
    Sim *s = new Sim();
    s->rt = JS_NewRuntime();
    if (!s->rt) { delete s; throwKotlin(env, "QuickJS: cannot create a runtime"); return 0; }
    if (memoryLimit > 0) JS_SetMemoryLimit(s->rt, (size_t) memoryLimit);
    if (stackLimit > 0) JS_SetMaxStackSize(s->rt, (size_t) stackLimit);
    s->ctx = JS_NewContext(s->rt);
    if (!s->ctx) { JS_FreeRuntime(s->rt); delete s; throwKotlin(env, "QuickJS: cannot create a context"); return 0; }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL FN(destroy)(JNIEnv *, jclass, jlong h) {
    Sim *s = S(h);
    if (!s) return;
    JS_FreeContext(s->ctx);
    JS_FreeRuntime(s->rt);
    delete s;
}

// Runs [code] as a global script (the bundle: it sets the functions it exports as globals).
JNIEXPORT void JNICALL FN(eval)(JNIEnv *env, jclass, jlong h, jbyteArray code, jstring name) {
    Sim *s = S(h);
    // (the stack limit is measured from the thread that runs JS: SimJs may be created on another one)
    JS_UpdateStackTop(s->rt);
    std::vector<char> src = bytes(env, code);
    const char *fname = env->GetStringUTFChars(name, nullptr);
    JSValue r = JS_Eval(s->ctx, src.data(), src.size() - 1, fname, JS_EVAL_TYPE_GLOBAL);
    env->ReleaseStringUTFChars(name, fname);
    if (JS_IsException(r)) { throwKotlin(env, takeException(s->ctx)); return; }
    JS_FreeValue(s->ctx, r);
}

// globalThis[fn](json): its result as a string (a string as it is; anything else through JSON.stringify).
JNIEXPORT jbyteArray JNICALL FN(call)(JNIEnv *env, jclass, jlong h, jstring fn, jbyteArray json) {
    Sim *s = S(h);
    JSContext *ctx = s->ctx;
    JS_UpdateStackTop(s->rt);
    const char *fname = env->GetStringUTFChars(fn, nullptr);
    std::string name(fname);
    env->ReleaseStringUTFChars(fn, fname);

    JSValue global = JS_GetGlobalObject(ctx);
    JSValue f = JS_GetPropertyStr(ctx, global, name.c_str());
    JS_FreeValue(ctx, global);
    if (!JS_IsFunction(ctx, f)) {
        JS_FreeValue(ctx, f);
        throwKotlin(env, "QuickJS: no global function " + name + " (is the bundle loaded?)");
        return nullptr;
    }
    std::vector<char> arg = bytes(env, json);
    JSValue a = JS_NewStringLen(ctx, arg.data(), arg.size() - 1);
    if (JS_IsException(a)) { JS_FreeValue(ctx, f); throwKotlin(env, takeException(ctx)); return nullptr; }
    JSValue r = JS_Call(ctx, f, JS_UNDEFINED, 1, &a);
    JS_FreeValue(ctx, a);
    JS_FreeValue(ctx, f);
    if (JS_IsException(r)) { throwKotlin(env, name + ": " + takeException(ctx)); return nullptr; }

    JSValue out = r;
    if (!JS_IsString(r)) {
        out = JS_JSONStringify(ctx, r, JS_UNDEFINED, JS_UNDEFINED);
        JS_FreeValue(ctx, r);
        if (JS_IsException(out)) { throwKotlin(env, name + ": " + takeException(ctx)); return nullptr; }
        if (JS_IsUndefined(out)) out = JS_NewString(ctx, "null");
    }
    size_t n = 0;
    const char *c = JS_ToCStringLen(ctx, &n, out);
    JS_FreeValue(ctx, out);
    if (!c) { throwKotlin(env, name + ": " + takeException(ctx)); return nullptr; }
    jbyteArray result = toJava(env, c, n);
    JS_FreeCString(ctx, c);
    return result;
}

// Bytes the runtime holds now (QuickJS's own count of what it allocated).
JNIEXPORT jlong JNICALL FN(memoryUsed)(JNIEnv *, jclass, jlong h) {
    JSMemoryUsage u;
    JS_ComputeMemoryUsage(S(h)->rt, &u);
    return (jlong) u.malloc_size;
}

JNIEXPORT void JNICALL FN(gc)(JNIEnv *, jclass, jlong h) { JS_RunGC(S(h)->rt); }

}  // extern "C"
