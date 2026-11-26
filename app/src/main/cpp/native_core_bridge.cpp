#include <jni.h>
#include <algorithm>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <vector>

struct retro_game_info {
    const char* path;
    const void* data;
    size_t size;
    const char* meta;
};

static void* g_core_handle = nullptr;
static void (*g_retro_init)(void) = nullptr;
static void (*g_retro_deinit)(void) = nullptr;
static bool (*g_retro_load_game)(const retro_game_info*) = nullptr;
static void (*g_retro_unload_game)(void) = nullptr;
static void (*g_retro_run)(void) = nullptr;
static size_t (*g_retro_serialize_size)(void) = nullptr;
static bool (*g_retro_serialize)(void*, size_t) = nullptr;
static bool (*g_retro_unserialize)(const void*, size_t) = nullptr;
static void (*g_set_env)(bool (*)(unsigned, void*)) = nullptr;
static void (*g_set_video)(void (*)(const void*, unsigned, unsigned, size_t)) = nullptr;
static void (*g_set_audio_batch)(size_t (*)(const int16_t*, size_t)) = nullptr;
static void (*g_set_input_poll)(void (*)(void)) = nullptr;
static void (*g_set_input_state)(int16_t (*)(unsigned, unsigned, unsigned, unsigned)) = nullptr;

static std::vector<uint32_t> g_pixels;
static std::vector<int16_t> g_audio_ring;
static uint32_t g_p1_mask = 0;
static uint32_t g_p2_mask = 0;
static unsigned g_width = 256;
static unsigned g_height = 240;
static unsigned g_pixel_format = 2; // Libretro: 0=0RGB1555, 1=XRGB8888, 2=RGB565
static std::vector<uint8_t> g_rom_bytes;

static bool env_cb(unsigned cmd, void* data) {
    if (cmd == 10 && data) {
        const unsigned requested = *static_cast<const unsigned*>(data);
        if (requested > 2) return false;
        g_pixel_format = requested;
        return true;
    }
    return false;
}

static void video_cb(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    if (width == 0 || height == 0 || width > 640 || height > 480) return;
    g_width = width;
    g_height = height;
    g_pixels.resize(width * height);
    const uint8_t* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; y++) {
        if (g_pixel_format == 1) { // XRGB8888 uses 4 bytes per pixel.
            const uint32_t* row = reinterpret_cast<const uint32_t*>(src + y * pitch);
            for (unsigned x = 0; x < width; x++) g_pixels[y * width + x] = 0xFF000000 | (row[x] & 0x00FFFFFF);
        } else {
            const uint16_t* row = reinterpret_cast<const uint16_t*>(src + y * pitch);
            for (unsigned x = 0; x < width; x++) {
                const uint16_t c = row[x];
                uint32_t r, g, b;
                if (g_pixel_format == 0) { // 0RGB1555
                    r = ((c >> 10) & 0x1F) * 255 / 31;
                    g = ((c >> 5) & 0x1F) * 255 / 31;
                    b = (c & 0x1F) * 255 / 31;
                } else { // RGB565
                    r = ((c >> 11) & 0x1F) * 255 / 31;
                    g = ((c >> 5) & 0x3F) * 255 / 63;
                    b = (c & 0x1F) * 255 / 31;
                }
                g_pixels[y * width + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }
}

static size_t audio_batch_cb(const int16_t* data, size_t frames) {
    if (data && frames > 0) {
        const size_t sampleCount = frames * 2;
        const size_t maxSamples = 44100 * 2;
        if (sampleCount >= maxSamples) {
            g_audio_ring.assign(data + sampleCount - maxSamples, data + sampleCount);
        } else {
            if (g_audio_ring.size() + sampleCount > maxSamples) {
                const size_t removeCount = g_audio_ring.size() + sampleCount - maxSamples;
                g_audio_ring.erase(g_audio_ring.begin(), g_audio_ring.begin() + removeCount);
            }
            g_audio_ring.insert(g_audio_ring.end(), data, data + sampleCount);
        }
    }
    return frames;
}

static void input_poll_cb(void) {}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned, unsigned id) {
    if (device != 1) return 0; // RETRO_DEVICE_JOYPAD
    uint32_t mask = (port == 0) ? g_p1_mask : (port == 1 ? g_p2_mask : 0);
    return (mask & (1u << id)) ? 1 : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadCore(JNIEnv* env, jobject, jstring soPath) {
    const char* path = env->GetStringUTFChars(soPath, nullptr);
    g_core_handle = dlopen(path, RTLD_LAZY);
    env->ReleaseStringUTFChars(soPath, path);
    if (!g_core_handle) return JNI_FALSE;

    g_retro_init = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_init"));
    g_retro_deinit = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_deinit"));
    g_retro_load_game = reinterpret_cast<bool(*)(const retro_game_info*)>(dlsym(g_core_handle, "retro_load_game"));
    g_retro_unload_game = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_unload_game"));
    g_retro_run = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_run"));
    g_retro_serialize_size = reinterpret_cast<size_t(*)()>(dlsym(g_core_handle, "retro_serialize_size"));
    g_retro_serialize = reinterpret_cast<bool(*)(void*, size_t)>(dlsym(g_core_handle, "retro_serialize"));
    g_retro_unserialize = reinterpret_cast<bool(*)(const void*, size_t)>(dlsym(g_core_handle, "retro_unserialize"));
    g_set_env = reinterpret_cast<void(*)(bool(*)(unsigned, void*))>(dlsym(g_core_handle, "retro_set_environment"));
    g_set_video = reinterpret_cast<void(*)(void(*)(const void*, unsigned, unsigned, size_t))>(dlsym(g_core_handle, "retro_set_video_refresh"));
    g_set_audio_batch = reinterpret_cast<void(*)(size_t(*)(const int16_t*, size_t))>(dlsym(g_core_handle, "retro_set_audio_sample_batch"));
    g_set_input_poll = reinterpret_cast<void(*)(void(*)())>(dlsym(g_core_handle, "retro_set_input_poll"));
    g_set_input_state = reinterpret_cast<void(*)(int16_t(*)(unsigned, unsigned, unsigned, unsigned))>(dlsym(g_core_handle, "retro_set_input_state"));

    if (!g_retro_init || !g_retro_run || !g_retro_load_game || !g_set_env || !g_set_video || !g_set_input_poll || !g_set_input_state) return JNI_FALSE;
    g_set_env(env_cb);
    g_set_video(video_cb);
    if (g_set_audio_batch) g_set_audio_batch(audio_batch_cb);
    g_set_input_poll(input_poll_cb);
    g_set_input_state(input_state_cb);
    g_retro_init();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadRomBytes(JNIEnv* env, jobject, jstring romPath, jbyteArray bytes) {
    if (!g_retro_load_game) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(romPath, nullptr);
    jsize len = env->GetArrayLength(bytes);
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    g_rom_bytes.assign(reinterpret_cast<uint8_t*>(raw), reinterpret_cast<uint8_t*>(raw) + len);
    retro_game_info info { path, g_rom_bytes.data(), static_cast<size_t>(len), nullptr };
    bool ok = g_retro_load_game(&info);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    env->ReleaseStringUTFChars(romPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeRunFrame(JNIEnv* env, jobject, jint p1Mask, jint p2Mask, jintArray outPixels) {
    if (!g_retro_run) return 0;
    g_p1_mask = static_cast<uint32_t>(p1Mask);
    g_p2_mask = static_cast<uint32_t>(p2Mask);
    g_retro_run();
    jsize maxLen = env->GetArrayLength(outPixels);
    jsize copyCount = std::min<jsize>(maxLen, static_cast<jsize>(g_pixels.size()));
    if (copyCount > 0) {
        env->SetIntArrayRegion(outPixels, 0, copyCount, reinterpret_cast<const jint*>(g_pixels.data()));
    }
    return static_cast<jint>((g_width << 16) | (g_height & 0xFFFF));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeReadAudio(JNIEnv* env, jobject, jshortArray outPcm) {
    jsize maxLen = env->GetArrayLength(outPcm);
    jsize count = std::min<jsize>(maxLen, static_cast<jsize>(g_audio_ring.size()));
    if (count > 0) {
        env->SetShortArrayRegion(outPcm, 0, count, g_audio_ring.data());
        g_audio_ring.erase(g_audio_ring.begin(), g_audio_ring.begin() + count);
    }
    return count;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeSaveState(JNIEnv* env, jobject) {
    if (!g_retro_serialize_size || !g_retro_serialize) return nullptr;
    size_t sz = g_retro_serialize_size();
    std::vector<uint8_t> buf(sz);
    if (!g_retro_serialize(buf.data(), sz)) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(sz));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(sz), reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadState(JNIEnv* env, jobject, jbyteArray stateBytes) {
    if (!g_retro_unserialize) return JNI_FALSE;
    jsize len = env->GetArrayLength(stateBytes);
    jbyte* raw = env->GetByteArrayElements(stateBytes, nullptr);
    bool ok = g_retro_unserialize(raw, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(stateBytes, raw, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeUnload(JNIEnv*, jobject) {
    if (g_retro_unload_game) g_retro_unload_game();
    if (g_retro_deinit) g_retro_deinit();
    g_rom_bytes.clear(); g_pixels.clear(); g_audio_ring.clear();
    if (g_core_handle) { dlclose(g_core_handle); g_core_handle = nullptr; }
    g_retro_init = nullptr; g_retro_deinit = nullptr; g_retro_load_game = nullptr;
    g_retro_unload_game = nullptr; g_retro_run = nullptr;
}
