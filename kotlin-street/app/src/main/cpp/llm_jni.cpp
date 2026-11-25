// JNI for com.timerpg.street.llm.NativeLlm: a thin layer over llm_core. No C++ exception ever crosses into Java,
// text always goes to Java through NewString (UTF-16, never NewStringUTF with model output), and text comes from
// Java as UTF-8 byte arrays (real UTF-8, unlike GetStringUTFChars).
#include "llm_core.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <malloc.h>

#include <new>
#include <string>
#include <vector>

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

// The native allocator's free pages back to the system before a model's copy loads (MEMORY_AUDIT.md, C2): scudo's
// M_PURGE_ALL (Android 14+: every size class), else M_PURGE. mallopt is looked up at run time (API 26+; minSdk is 24).
extern "C" JNIEXPORT jint JNICALL FN(nativePurge)(JNIEnv *, jclass) {
    using mallopt_fn = int (*)(int, int);
    auto fn = reinterpret_cast<mallopt_fn>(dlsym(RTLD_DEFAULT, "mallopt"));
    if (!fn) return 0;
    int r = 0;
#ifdef M_PURGE_ALL
    r = fn(M_PURGE_ALL, 0);
#endif
#ifdef M_PURGE
    if (r == 0) r = fn(M_PURGE, 0);
#endif
    return r;
}

extern "C" JNIEXPORT void JNICALL FN(nativeSetDevice)(JNIEnv *, jobject, jlong h, jint kind) {
    if (h) engine(h)->set_device(kind);
}

extern "C" JNIEXPORT jint JNICALL FN(nativeDevice)(JNIEnv *, jobject, jlong h) {
    return h ? engine(h)->device() : 0;
}

/** Which accelerators there are: bit 0 the GPU, bit 1 the NPU. */
extern "C" JNIEXPORT jint JNICALL FN(nativeAccelerators)(JNIEnv *, jobject) {
    try { return (llm::has_gpu() ? 1 : 0) | (llm::has_npu() ? 2 : 0); } catch (...) { return 0; }
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

/** The last load's memory (MiB): weights mapped, weights copied, KV cache, recurrent state, compute (llm::last_load_mem). */
extern "C" JNIEXPORT jdoubleArray JNICALL FN(nativeLoadMem)(JNIEnv * env, jobject) {
    double m[5] = {0, 0, 0, 0, 0};
    try { llm::last_load_mem(m); } catch (...) {}
    jdoubleArray a = env->NewDoubleArray(5);
    if (a) env->SetDoubleArrayRegion(a, 0, 5, m);
    return a;
}

/** The loaded model's cache is in 8 bits. */
extern "C" JNIEXPORT jboolean JNICALL FN(nativeKvQ8)(JNIEnv *, jobject, jlong h) {
    return h && engine(h)->kv_q8() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL FN(nativeLoadInfo)(JNIEnv * env, jobject) {
    return to_jstring(env, llm::last_load_info());
}

extern "C" JNIEXPORT jlongArray JNICALL FN(nativeStats)(JNIEnv * env, jobject, jlong h) {
    const llm::Stats s = h ? engine(h)->stats() : llm::Stats{};
    const jlong v[13] = {s.load_ms, s.prompt_tokens, s.reused_tokens, s.prefill_ms, s.gen_tokens, s.gen_ms, s.first_token_ms, s.threads,
                         s.drafted, s.accepted, s.ngram_drafted, s.ngram_accepted, s.steps};
    jlongArray a = env->NewLongArray(13);
    if (!a) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetLongArrayRegion(a, 0, 13, v);
    return a;
}

/** The n-gram guesses' pool: the person's lines in the bank, one per line (llm::Engine::set_draft_pool). */
extern "C" JNIEXPORT void JNICALL FN(nativeSetPool)(JNIEnv * env, jobject, jlong h, jbyteArray lines) {
    if (!h) return;
    try { engine(h)->set_draft_pool(from_bytes(env, lines)); } catch (...) {}
}

/** Saved states on flash (llm::Engine::set_snapshot_dir): [dir] empty keeps them in RAM. */
extern "C" JNIEXPORT void JNICALL FN(nativeSetSnapshots)(JNIEnv * env, jobject, jlong h, jbyteArray dir, jlong budget_bytes, jboolean hot,
                                                        jint points) {
    if (!h) return;
    try { engine(h)->set_snapshot_dir(from_bytes(env, dir), budget_bytes, hot == JNI_TRUE, points); } catch (...) {}
}

/** Pre-bakes a prompt's saved states (llm::Engine::bake): the tokens covered, 0 nothing to bake, -1 failed. */
extern "C" JNIEXPORT jint JNICALL FN(nativeBake)(JNIEnv * env, jobject, jlong h, jbyteArray prompt) {
    if (!h) return -1;
    try { return engine(h)->bake(from_bytes(env, prompt)); } catch (...) { return -1; }
}

/** llm::SnapStats: files, file bytes, in RAM, RAM bytes, a state's size, restores from flash, from RAM, restore ms,
 *  writes, write ms, removed for the budget. */
extern "C" JNIEXPORT jlongArray JNICALL FN(nativeSnapStats)(JNIEnv * env, jobject, jlong h) {
    const llm::SnapStats s = h ? engine(h)->snap_stats() : llm::SnapStats{};
    const jlong v[11] = {s.files, s.file_bytes, s.ram, s.ram_bytes, s.state_bytes, s.restores_file, s.restores_ram, s.restore_ms,
                         s.writes, s.write_ms, s.evicted};
    jlongArray a = env->NewLongArray(11);
    if (!a) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    env->SetLongArrayRegion(a, 0, 11, v);
    return a;
}

// ------------------------------------------------------------------------------------------------ speech to text
// JNI for com.timerpg.street.llm.NativeAsr: llm::Asr (Qwen3-ASR through mtmd).
#include "asr_core.h"

#define ASR(name) Java_com_timerpg_street_llm_NativeAsr_##name

namespace { llm::Asr * asr(jlong h) { return reinterpret_cast<llm::Asr *>(h); } }

extern "C" JNIEXPORT jlong JNICALL ASR(nativeCreate)(JNIEnv *, jobject) {
    try {
        llm::init_backend(llm::cpu_variant_libs(), nullptr);
        return reinterpret_cast<jlong>(new (std::nothrow) llm::Asr());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL ASR(nativeDestroy)(JNIEnv *, jobject, jlong h) {
    try { delete asr(h); } catch (...) {}
}

extern "C" JNIEXPORT jboolean JNICALL ASR(nativeLoad)(JNIEnv * env, jobject, jlong h, jbyteArray model, jbyteArray mmproj, jint threads) {
    if (!h) return JNI_FALSE;
    try {
        return asr(h)->load(from_bytes(env, model), from_bytes(env, mmproj), threads) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL ASR(nativeRate)(JNIEnv *, jobject, jlong h) {
    return h ? asr(h)->sample_rate() : 0;
}

extern "C" JNIEXPORT jstring JNICALL ASR(nativeError)(JNIEnv * env, jobject, jlong h) {
    return h ? to_jstring(env, asr(h)->error()) : nullptr;
}

extern "C" JNIEXPORT void JNICALL ASR(nativeCancel)(JNIEnv *, jobject, jlong h) {
    if (h) asr(h)->cancel();
}

extern "C" JNIEXPORT jstring JNICALL ASR(nativeTranscribe)(JNIEnv * env, jobject, jlong h, jfloatArray pcm, jbyteArray context) {
    if (!h || !pcm) return nullptr;
    try {
        const jsize n = env->GetArrayLength(pcm);
        std::vector<float> samples((size_t) n);
        if (n > 0) env->GetFloatArrayRegion(pcm, 0, n, samples.data());
        const std::string text = asr(h)->transcribe(samples.data(), samples.size(), from_bytes(env, context));
        return to_jstring(env, text);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL ASR(nativeTimes)(JNIEnv * env, jobject, jlong h) {
    if (!h) return nullptr;
    jlong v[2] = { (jlong) asr(h)->encode_ms(), (jlong) asr(h)->total_ms() };
    jlongArray a = env->NewLongArray(2);
    if (a) env->SetLongArrayRegion(a, 0, 2, v);
    return a;
}

extern "C" JNIEXPORT jdoubleArray JNICALL ASR(nativeMem)(JNIEnv * env, jobject, jlong h) {
    jdoubleArray a = env->NewDoubleArray(5);
    if (a && h) env->SetDoubleArrayRegion(a, 0, 5, asr(h)->mem());
    return a;
}

// ------------------------------------------------------------------------------------------------ memory embeddings
// JNI for com.timerpg.street.llm.NativeEmbedder: llm::Embedder (Qwen3-Embedding).
#include "embed_core.h"

#define EMB(name) Java_com_timerpg_street_llm_NativeEmbedder_##name

namespace { llm::Embedder * emb(jlong h) { return reinterpret_cast<llm::Embedder *>(h); } }

extern "C" JNIEXPORT jlong JNICALL EMB(nativeCreate)(JNIEnv *, jobject) {
    try {
        llm::init_backend(llm::cpu_variant_libs(), nullptr);
        return reinterpret_cast<jlong>(new (std::nothrow) llm::Embedder());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL EMB(nativeDestroy)(JNIEnv *, jobject, jlong h) {
    try { delete emb(h); } catch (...) {}
}

extern "C" JNIEXPORT jboolean JNICALL EMB(nativeLoad)(JNIEnv * env, jobject, jlong h, jbyteArray model, jint threads) {
    if (!h) return JNI_FALSE;
    try { return emb(h)->load(from_bytes(env, model), threads) ? JNI_TRUE : JNI_FALSE; } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jstring JNICALL EMB(nativeError)(JNIEnv * env, jobject, jlong h) {
    return h ? to_jstring(env, emb(h)->error()) : nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL EMB(nativeEmbed)(JNIEnv * env, jobject, jlong h, jbyteArray text) {
    if (!h) return nullptr;
    try {
        const std::vector<float> v = emb(h)->embed(from_bytes(env, text));
        if (v.empty()) return nullptr;
        jfloatArray a = env->NewFloatArray((jsize) v.size());
        if (a) env->SetFloatArrayRegion(a, 0, (jsize) v.size(), v.data());
        return a;
    } catch (...) {
        return nullptr;
    }
}

// JNI for com.timerpg.street.llm.NativeReranker: llm::Reranker (Qwen3-Reranker).
#define RR(name) Java_com_timerpg_street_llm_NativeReranker_##name

namespace { llm::Reranker * rr(jlong h) { return reinterpret_cast<llm::Reranker *>(h); } }

extern "C" JNIEXPORT jlong JNICALL RR(nativeCreate)(JNIEnv *, jobject) {
    try {
        llm::init_backend(llm::cpu_variant_libs(), nullptr);
        return reinterpret_cast<jlong>(new (std::nothrow) llm::Reranker());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL RR(nativeDestroy)(JNIEnv *, jobject, jlong h) {
    try { delete rr(h); } catch (...) {}
}

extern "C" JNIEXPORT jboolean JNICALL RR(nativeLoad)(JNIEnv * env, jobject, jlong h, jbyteArray model, jint threads) {
    if (!h) return JNI_FALSE;
    try { return rr(h)->load(from_bytes(env, model), threads) ? JNI_TRUE : JNI_FALSE; } catch (...) { return JNI_FALSE; }
}

extern "C" JNIEXPORT jstring JNICALL RR(nativeError)(JNIEnv * env, jobject, jlong h) {
    return h ? to_jstring(env, rr(h)->error()) : nullptr;
}

extern "C" JNIEXPORT jfloat JNICALL RR(nativeScore)(JNIEnv * env, jobject, jlong h, jbyteArray inst, jbyteArray query, jbyteArray doc) {
    if (!h) return -1.f;
    try { return rr(h)->score(from_bytes(env, inst), from_bytes(env, query), from_bytes(env, doc)); } catch (...) { return -1.f; }
}
