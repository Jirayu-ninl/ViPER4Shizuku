// JNI bridge between ViPER4Shizuku (Kotlin) and the unmodified ViPERDSP engine.
//
// Replaces the Android effect-framework glue of ViPERFX_RE (ViperContext): instead of an
// effect HAL calling command()/process(), the app's own audio thread calls process() and the
// UI pushes parameters through dispatch(). Both take the same mutex, mirroring how the effect
// framework serialises command() and process() on one effect instance.

#include <jni.h>

#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#endif

#include "ViPERParams.h"
#include "viper/ViPER.h"
#include "viper/constants.h"

namespace {

constexpr const char *kTag = "ViPER4ShizukuDsp";
constexpr const char *kNativeDspClass = "dev/theiceji/viper4shizuku/engine/NativeDspJni";

// Same values as android.media.AudioFormat.
constexpr jint kEncodingPcm16 = 2;
constexpr jint kEncodingPcmFloat = 4;

// Same wire format as ViperParamPayload.kt and ViperContext.cpp:
// [u32 type][i32 index][u32 count_or_size][payload...], little endian.
constexpr size_t kParamPayloadHeaderSize = sizeof(uint32_t) + sizeof(int32_t) + sizeof(uint32_t);

bool DecodeParamValue(const uint8_t *bytes, size_t byte_count, viper::ParamValue *out) {
    if (bytes == nullptr || out == nullptr || byte_count < kParamPayloadHeaderSize) {
        return false;
    }

    uint32_t type;
    uint32_t count_or_size;
    std::memcpy(&type, bytes, sizeof(type));
    std::memcpy(&out->index, bytes + sizeof(type), sizeof(out->index));
    std::memcpy(&count_or_size, bytes + sizeof(type) + sizeof(out->index), sizeof(count_or_size));

    out->type = static_cast<viper::ParamValueType>(type);
    const uint8_t *payload = bytes + kParamPayloadHeaderSize;
    const size_t payload_size = byte_count - kParamPayloadHeaderSize;

    switch (out->type) {
        case viper::ParamValueType::kBool:
            if (count_or_size != 1 || payload_size != 1) return false;
            out->bool_value = payload[0] != 0;
            return true;
        case viper::ParamValueType::kInt:
            if (count_or_size != 1 || payload_size != sizeof(int32_t)) return false;
            std::memcpy(&out->int_value, payload, sizeof(out->int_value));
            return true;
        case viper::ParamValueType::kFloat:
            if (count_or_size != 1 || payload_size != sizeof(float)) return false;
            std::memcpy(&out->float_value, payload, sizeof(out->float_value));
            return true;
        case viper::ParamValueType::kFloatArray:
            if (payload_size != count_or_size * sizeof(float)) return false;
            out->float_array = reinterpret_cast<const float *>(payload);
            out->float_count = count_or_size;
            return true;
        case viper::ParamValueType::kBytes:
            if (payload_size != count_or_size) return false;
            out->bytes = payload;
            out->byte_count = count_or_size;
            return true;
        case viper::ParamValueType::kIntArray:
            if (payload_size != count_or_size * sizeof(int32_t)) return false;
            out->int_array = reinterpret_cast<const int32_t *>(payload);
            out->int_count = count_or_size;
            return true;
        default:
            return false;
    }
}

// Flush denormals to zero while the DSP runs, as the original driver does on ARM.
class ScopedFlushToZero {
public:
    ScopedFlushToZero() {
#if defined(__aarch64__)
        asm volatile("mrs %0, fpcr" : "=r"(saved_));
        asm volatile("msr fpcr, %0" ::"r"(saved_ | (1ULL << 24)));
#elif defined(__x86_64__) || defined(__i386__)
        saved_ = _mm_getcsr();
        _mm_setcsr(static_cast<unsigned int>(saved_) | 0x8040u);  // FTZ | DAZ
#endif
    }

    ~ScopedFlushToZero() {
#if defined(__aarch64__)
        asm volatile("msr fpcr, %0" ::"r"(saved_));
#elif defined(__x86_64__) || defined(__i386__)
        _mm_setcsr(static_cast<unsigned int>(saved_));
#endif
    }

    ScopedFlushToZero(const ScopedFlushToZero &) = delete;
    ScopedFlushToZero &operator=(const ScopedFlushToZero &) = delete;

private:
    uint64_t saved_ = 0;
};

struct Engine {
    std::mutex mutex;
    ViPER viper;
    std::vector<float> buffer;
};

Engine *FromHandle(jlong handle) {
    return reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
}

void EnsureCapacity(Engine *engine, size_t frames) {
    if (engine->buffer.size() < frames * 2) engine->buffer.resize(frames * 2);
}

inline float Clamp(float v) {
    if (std::isnan(v)) return 0.0f;
    return v < -1.0f ? -1.0f : (v > 1.0f ? 1.0f : v);
}

// --- JNI methods --------------------------------------------------------------------------

jlong NativeCreate(JNIEnv *, jclass, jint sample_rate) {
    auto *engine = new Engine();
    engine->viper.SetSamplingRate(static_cast<uint32_t>(sample_rate));
    engine->viper.ResetAllEffects();
    __android_log_print(ANDROID_LOG_INFO, kTag, "engine created, sr=%d", sample_rate);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

void NativeRelease(JNIEnv *, jclass, jlong handle) {
    delete FromHandle(handle);
}

void NativeSetSampleRate(JNIEnv *, jclass, jlong handle, jint sample_rate) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    if (engine->viper.GetSamplingRate() == static_cast<uint32_t>(sample_rate)) return;
    engine->viper.SetSamplingRate(static_cast<uint32_t>(sample_rate));
    engine->viper.ResetAllEffects();
}

void NativeReset(JNIEnv *, jclass, jlong handle) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    engine->viper.ResetAllEffects();
}

jboolean NativeDispatch(JNIEnv *env, jclass, jlong handle, jint param, jbyteArray payload) {
    if (payload == nullptr) return JNI_FALSE;
    Engine *engine = FromHandle(handle);
    const jsize size = env->GetArrayLength(payload);
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    env->GetByteArrayRegion(payload, 0, size, reinterpret_cast<jbyte *>(bytes.data()));

    viper::ParamValue value;
    if (!DecodeParamValue(bytes.data(), bytes.size(), &value)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "bad payload for param 0x%x", param);
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(engine->mutex);
    return engine->viper.DispatchParamValue(param, value) ? JNI_TRUE : JNI_FALSE;
}

// In-place processing of interleaved stereo float samples held in a Java float[].
void NativeProcessFloatArray(JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint offset,
                             jint frames) {
    if (frames <= 0) return;
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    EnsureCapacity(engine, static_cast<size_t>(frames));
    const jsize count = frames * 2;
    env->GetFloatArrayRegion(samples, offset, count, engine->buffer.data());
    {
        ScopedFlushToZero ftz;
        engine->viper.Process(engine->buffer, static_cast<uint32_t>(frames));
    }
    for (jsize i = 0; i < count; i++) engine->buffer[i] = Clamp(engine->buffer[i]);
    env->SetFloatArrayRegion(samples, offset, count, engine->buffer.data());
}

// Processing between direct ByteBuffers (may be the same buffer). Both sides use the same
// encoding: PCM_FLOAT or PCM_16BIT, interleaved stereo, native byte order.
jboolean NativeProcessDirect(JNIEnv *env, jclass, jlong handle, jobject in_buffer, jint in_offset,
                             jobject out_buffer, jint out_offset, jint frames, jint encoding) {
    if (frames <= 0) return JNI_TRUE;
    auto *in = static_cast<uint8_t *>(env->GetDirectBufferAddress(in_buffer));
    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(out_buffer));
    if (in == nullptr || out == nullptr) return JNI_FALSE;
    in += in_offset;
    out += out_offset;

    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    EnsureCapacity(engine, static_cast<size_t>(frames));
    float *buf = engine->buffer.data();
    const size_t count = static_cast<size_t>(frames) * 2;

    if (encoding == kEncodingPcmFloat) {
        std::memcpy(buf, in, count * sizeof(float));
    } else if (encoding == kEncodingPcm16) {
        const auto *src = reinterpret_cast<const int16_t *>(in);
        for (size_t i = 0; i < count; i++) buf[i] = static_cast<float>(src[i]) / 32768.0f;
    } else {
        return JNI_FALSE;
    }

    {
        ScopedFlushToZero ftz;
        engine->viper.Process(engine->buffer, static_cast<uint32_t>(frames));
    }

    if (encoding == kEncodingPcmFloat) {
        auto *dst = reinterpret_cast<float *>(out);
        for (size_t i = 0; i < count; i++) dst[i] = Clamp(buf[i]);
    } else {
        auto *dst = reinterpret_cast<int16_t *>(out);
        for (size_t i = 0; i < count; i++) {
            dst[i] = static_cast<int16_t>(std::lrintf(Clamp(buf[i]) * 32767.0f));
        }
    }
    return JNI_TRUE;
}

jint NativeGetSampleRate(JNIEnv *, jclass, jlong handle) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    return static_cast<jint>(engine->viper.GetSamplingRate());
}

jlong NativeGetProcessedFrames(JNIEnv *, jclass, jlong handle) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    return static_cast<jlong>(engine->viper.GetProcessedFrames());
}

jint NativeGetKernelId(JNIEnv *, jclass, jlong handle) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    return static_cast<jint>(engine->viper.GetConvolverKernelID());
}

// Raw bytes of the last ViPERParams applied with kParamSetFullParams. Used by the
// serializer <-> C++ layout round-trip test.
jbyteArray NativeGetCurrentParams(JNIEnv *env, jclass, jlong handle) {
    Engine *engine = FromHandle(handle);
    std::lock_guard<std::mutex> lock(engine->mutex);
    const viper::ViPERParams &params = engine->viper.CurrentParams();
    jbyteArray out = env->NewByteArray(sizeof(params));
    env->SetByteArrayRegion(out, 0, sizeof(params), reinterpret_cast<const jbyte *>(&params));
    return out;
}

jint NativeGetParamsSize(JNIEnv *, jclass) {
    return static_cast<jint>(sizeof(viper::ViPERParams));
}

jstring NativeGetArchitecture(JNIEnv *env, jclass) {
    return env->NewStringUTF(VIPER_ARCHITECTURE);
}

jstring NativeGetVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(VIPER4SHIZUKU_ENGINE_VERSION);
}

const JNINativeMethod kMethods[] = {
    {"nativeCreate", "(I)J", reinterpret_cast<void *>(NativeCreate)},
    {"nativeRelease", "(J)V", reinterpret_cast<void *>(NativeRelease)},
    {"nativeSetSampleRate", "(JI)V", reinterpret_cast<void *>(NativeSetSampleRate)},
    {"nativeReset", "(J)V", reinterpret_cast<void *>(NativeReset)},
    {"nativeDispatch", "(JI[B)Z", reinterpret_cast<void *>(NativeDispatch)},
    {"nativeProcessFloatArray", "(J[FII)V", reinterpret_cast<void *>(NativeProcessFloatArray)},
    {"nativeProcessDirect", "(JLjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;III)Z",
     reinterpret_cast<void *>(NativeProcessDirect)},
    {"nativeGetSampleRate", "(J)I", reinterpret_cast<void *>(NativeGetSampleRate)},
    {"nativeGetProcessedFrames", "(J)J", reinterpret_cast<void *>(NativeGetProcessedFrames)},
    {"nativeGetKernelId", "(J)I", reinterpret_cast<void *>(NativeGetKernelId)},
    {"nativeGetCurrentParams", "(J)[B", reinterpret_cast<void *>(NativeGetCurrentParams)},
    {"nativeGetParamsSize", "()I", reinterpret_cast<void *>(NativeGetParamsSize)},
    {"nativeGetArchitecture", "()Ljava/lang/String;",
     reinterpret_cast<void *>(NativeGetArchitecture)},
    {"nativeGetVersion", "()Ljava/lang/String;", reinterpret_cast<void *>(NativeGetVersion)},
};

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = env->FindClass(kNativeDspClass);
    if (clazz == nullptr) return JNI_ERR;
    if (env->RegisterNatives(clazz, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        return JNI_ERR;
    }
    env->DeleteLocalRef(clazz);
    return JNI_VERSION_1_6;
}
