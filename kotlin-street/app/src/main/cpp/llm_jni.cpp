// JNI for com.timerpg.street.llm.NativeLlm: a thin layer over llm_core. No C++ exception ever crosses into Java,
// text always goes to Java through NewString (UTF-16, never NewStringUTF with model output), and text comes from
// Java as UTF-8 byte arrays (real UTF-8, unlike GetStringUTFChars).
#include "llm_core.h"

#include <android/log.h>
#include <jni.h>

#include <new>
#include <string>

namespace {

constexpr const char * kTag = "TimerpgLlm";

void logcat_sink(int level, const char * text) {
    if (level <= 1 || !text) return;  // llama.cpp's debug lines are dropped
    const int prio = level >= 4 ? ANDROID_LOG_ERROR : level == 3 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(prio, kTag, text);
}

llm::Engine * engine(jlong handle) { return reinterpret_cast<llm::Engine *>(handle); }

jstring to_jstring(JNIEnv * env, const std::string & s) {
    try {
        const std::u16string u = llm::utf8_to_utf16(s);
        jstring js = env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize) u.size());
        if (!js && env->ExceptionCheck()) env->ExceptionClear();
        return js;
    } catch (...) {
        return nullptr;
    }
}

std::string from_bytes(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    const jsize n = env->GetArrayLength(a);
    std::string s((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&s[0]));
    return s;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    llm::set_log_sink(logcat_sink);
    return JNI_VERSION_1_6;
}

#define FN(name) Java_com_timerpg_street_llm_NativeLlm_##name

extern "C" JNIEXPORT jstring JNICALL FN(nativeBackendInfo)(JNIEnv * env, jobject) {
    std::string info;
    try {
        llm::init_backend(llm::cpu_variant_libs(), &info);
    } catch (...) {
        info = "backend init failed";
    }
    return to_jstring(env, info);
}

extern "C" JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *, jobject) {
    try {
        return reinterpret_cast<jlong>(new (std::nothrow) llm::Engine());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *, jobject, jlong h) {
    try {
        delete engine(h);
    } catch (...) {
    }
}

extern "C" JNIEXPORT jboolean JNICALL FN(nativeLoad)(JNIEnv * env, jobject, jlong h, jbyteArray path, jint threads, jint ctx) {
    if (!h) return JNI_FALSE;
    try {
        return engine(h)->load(from_bytes(env, path), threads, ctx) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL FN(nativeError)(JNIEnv * env, jobject, jlong h) {
    return to_jstring(env, h ? engine(h)->error() : std::string("no engine"));
}

extern "C" JNIEXPORT void JNICALL FN(nativeArm)(JNIEnv *, jobject, jlong h) {
    if (h) engine(h)->arm();
}

extern "C" JNIEXPORT void JNICALL FN(nativeCancel)(JNIEnv *, jobject, jlong h) {
    if (h) engine(h)->cancel();
}

extern "C" JNIEXPORT jstring JNICALL FN(nativeGenerate)(JNIEnv * env, jobject, jlong h, jbyteArray prompt, jint max_tokens,
                                                       jfloat temperature, jobject sink) {
    if (!h) return to_jstring(env, "");
    std::string reply;
    try {
        jmethodID on_text = nullptr;
        if (sink) {
            jclass cls = env->GetObjectClass(sink);
            on_text = cls ? env->GetMethodID(cls, "onText", "(Ljava/lang/String;)Z") : nullptr;
            if (env->ExceptionCheck()) env->ExceptionClear();
            if (cls) env->DeleteLocalRef(cls);
        }
        reply = engine(h)->generate(from_bytes(env, prompt), max_tokens, temperature, [&](const std::string & text) {
            if (!on_text) return true;
            jstring js = to_jstring(env, text);
            if (!js) return true;  // out of memory for the piece: keep generating, the whole reply is still returned
            const jboolean more = env->CallBooleanMethod(sink, on_text, js);
            env->DeleteLocalRef(js);
            if (env->ExceptionCheck()) {  // NativeLlm catches its callback's exceptions; this is a last resort
                env->ExceptionClear();
                return false;
            }
            return more == JNI_TRUE;
        });
    } catch (...) {
    }
    return to_jstring(env, reply);
}

extern "C" JNIEXPORT jlongArray JNICALL FN(nativeStats)(JNIEnv * env, jobject, jlong h) {
    const llm::Stats s = h ? engine(h)->stats() : llm::Stats{};
    const jlong v[8] = {s.load_ms, s.prompt_tokens, s.reused_tokens, s.prefill_ms, s.gen_tokens, s.gen_ms, s.first_token_ms, s.threads};
    jlongArray a = env->NewLongArray(8);
    if (!a) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetLongArrayRegion(a, 0, 8, v);
    return a;
}
