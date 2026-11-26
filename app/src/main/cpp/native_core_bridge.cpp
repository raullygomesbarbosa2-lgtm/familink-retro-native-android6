#include <jni.h>
#include <algorithm>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <sys/stat.h>

struct retro_game_info {
    const char* path;
    const void* data;
    size_t size;
    const char* meta;
};

static std::mutex g_core_mutex;
static void* g_core_handle = nullptr;
static void (*g_retro_init)(void) = nullptr;
static void (*g_retro_deinit)(void) = nullptr;
static bool (*g_retro_load_game)(const retro_game_info*) = nullptr;
static void (*g_retro_unload_game)(void) = nullptr;
static void (*g_retro_run)(void) = nullptr;
static void (*g_retro_get_system_av_info)(void*) = nullptr;
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
static float g_p1_analog_x = 0.f, g_p1_analog_y = 0.f;
static float g_p2_analog_x = 0.f, g_p2_analog_y = 0.f;
static unsigned g_width = 256;
static unsigned g_height = 240;
static unsigned g_pixel_format = 2; // Libretro: 0=0RGB1555, 1=XRGB8888, 2=RGB565
static double g_audio_sample_rate = 48000.0;
static std::vector<uint8_t> g_rom_bytes;
static std::string g_game_path;
static std::string g_system_directory;
static std::string g_save_directory;
static std::string g_content_directory;
static std::string g_core_path;
static std::unordered_map<std::string, std::string> g_core_variables;
struct retro_variable { const char* key; const char* value; };

struct retro_game_geometry { unsigned base_width, base_height, max_width, max_height; float aspect_ratio; };
struct retro_system_timing { double fps, sample_rate; };
struct retro_system_av_info { retro_game_geometry geometry; retro_system_timing timing; };

static bool env_cb(unsigned cmd, void* data) {
    // Core initialization and MAME-family cores rely on these standard libretro environment services.
    switch (cmd) {
        case 2: // GET_OVERSCAN
            if (data) *static_cast<bool*>(data) = false;
            return true;
        case 3: // GET_CAN_DUPE
            if (data) *static_cast<bool*>(data) = true;
            return true;
        case 9: // GET_SYSTEM_DIRECTORY
            if (!data) return false;
            *static_cast<const char**>(data) = g_system_directory.c_str();
            return true;
        case 10: { // SET_PIXEL_FORMAT
            if (!data) return false;
            const unsigned requested = *static_cast<const unsigned*>(data);
            if (requested > 2) return false;
            g_pixel_format = requested;
            return true;
        }
        case 15: { // GET_VARIABLE
            if (!data) return false;
            auto* variable = static_cast<retro_variable*>(data);
            if (!variable->key) return false;
            auto it = g_core_variables.find(variable->key);
            if (it == g_core_variables.end()) return false;
            variable->value = it->second.c_str();
            return true;
        }
        case 16: { // SET_VARIABLES; preserve each core's documented default option.
            if (!data) return false;
            g_core_variables.clear();
            auto* variables = static_cast<retro_variable*>(data);
            for (size_t i = 0; i < 512 && variables[i].key; ++i) {
                const char* raw = variables[i].value;
                if (!raw) continue;
                const char* separator = std::strchr(raw, ';');
                if (!separator) continue;
                while (*++separator == ' ') {}
                const char* end = std::strchr(separator, '|');
                g_core_variables.emplace(variables[i].key, std::string(separator, end ? static_cast<size_t>(end - separator) : std::strlen(separator)));
            }
            return true;
        }
        case 17: // GET_VARIABLE_UPDATE
            if (data) *static_cast<bool*>(data) = false;
            return true;
        case 18: // SET_SUPPORT_NO_GAME
            return true;
        case 19: // GET_LIBRETRO_PATH
            if (!data) return false;
            *static_cast<const char**>(data) = g_core_path.c_str();
            return true;
        case 24: // GET_INPUT_DEVICE_CAPABILITIES
            if (data) *static_cast<uint64_t*>(data) = (1ULL << 1) | (1ULL << 5);
            return true;
        case 31: // GET_SAVE_DIRECTORY
            if (!data) return false;
            *static_cast<const char**>(data) = g_save_directory.c_str();
            return true;
        case 32: { // SET_SYSTEM_AV_INFO
            if (!data) return false;
            const auto* info = static_cast<const retro_system_av_info*>(data);
            if (info->timing.sample_rate >= 8000.0 && info->timing.sample_rate <= 192000.0)
                g_audio_sample_rate = info->timing.sample_rate;
            return true;
        }
        case 38: // GET_USERNAME: allow cores to run without requiring a profile.
            if (!data) return false;
            *static_cast<const char**>(data) = "FamiLink";
            return true;
        case 39: // GET_LANGUAGE: English default.
            if (data) *static_cast<unsigned*>(data) = 0;
            return true;
        case 30: // GET_CORE_ASSETS_DIRECTORY
        case 40: // GET_CONTENT_DIRECTORY
            if (!data) return false;
            *static_cast<const char**>(data) = g_content_directory.c_str();
            return true;
        default:
            return false;
    }
}

static void video_cb(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;
    if (width == 0 || height == 0 || width > 1024 || height > 768) return;
    const size_t bytes_per_pixel = (g_pixel_format == 1) ? sizeof(uint32_t) : sizeof(uint16_t);
    if (pitch < static_cast<size_t>(width) * bytes_per_pixel) return;
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

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    const uint32_t mask = (port == 0) ? g_p1_mask : (port == 1 ? g_p2_mask : 0);
    if (device == 1) { // RETRO_DEVICE_JOYPAD, for cores which use digital directions.
        return id < 32 && (mask & (1u << id)) ? 1 : 0;
    }
    if (device == 5 && index == 0) { // RETRO_DEVICE_ANALOG, left stick.
        const float x = port == 0 ? g_p1_analog_x : (port == 1 ? g_p2_analog_x : 0.f);
        const float y = port == 0 ? g_p1_analog_y : (port == 1 ? g_p2_analog_y : 0.f);
        const float value = id == 0 ? x : (id == 1 ? y : 0.f);
        return static_cast<int16_t>(value * 32767.f);
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadCore(JNIEnv* env, jobject, jstring soPath) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    const char* path = env->GetStringUTFChars(soPath, nullptr);
    g_core_path.assign(path);
    const size_t slash = g_core_path.find_last_of('/');
    const std::string core_dir = slash == std::string::npos ? "." : g_core_path.substr(0, slash);
    g_system_directory = core_dir + "/system";
    g_save_directory = core_dir + "/saves";
    g_content_directory = core_dir + "/system";
    mkdir(g_system_directory.c_str(), 0770);
    mkdir(g_save_directory.c_str(), 0770);
    g_core_variables.clear();
    g_core_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(soPath, path);
    if (!g_core_handle) return JNI_FALSE;

    g_retro_init = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_init"));
    g_retro_deinit = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_deinit"));
    g_retro_load_game = reinterpret_cast<bool(*)(const retro_game_info*)>(dlsym(g_core_handle, "retro_load_game"));
    g_retro_unload_game = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_unload_game"));
    g_retro_run = reinterpret_cast<void(*)()>(dlsym(g_core_handle, "retro_run"));
    g_retro_get_system_av_info = reinterpret_cast<void(*)(void*)>(dlsym(g_core_handle, "retro_get_system_av_info"));
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
    // Reset per-core video state so a newly selected Mega Drive/other core cannot inherit stale format.
    g_pixel_format = 2;
    g_width = 256;
    g_height = 240;
    g_pixels.clear();
    g_retro_init();
    if (g_retro_get_system_av_info) {
        retro_system_av_info info{};
        g_retro_get_system_av_info(&info);
        if (info.timing.sample_rate >= 8000.0 && info.timing.sample_rate <= 192000.0)
            g_audio_sample_rate = info.timing.sample_rate;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadRomBytes(JNIEnv* env, jobject, jstring romPath, jbyteArray bytes) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!g_retro_load_game) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(romPath, nullptr);
    g_game_path.assign(path);
    jsize len = env->GetArrayLength(bytes);
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    g_rom_bytes.assign(reinterpret_cast<uint8_t*>(raw), reinterpret_cast<uint8_t*>(raw) + len);
    retro_game_info info { g_game_path.c_str(), g_rom_bytes.data(), static_cast<size_t>(len), nullptr };
    bool ok = g_retro_load_game(&info);
    if (ok && g_retro_get_system_av_info) {
        retro_system_av_info info{};
        g_retro_get_system_av_info(&info);
        if (info.timing.sample_rate >= 8000.0 && info.timing.sample_rate <= 192000.0)
            g_audio_sample_rate = info.timing.sample_rate;
    }
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    env->ReleaseStringUTFChars(romPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeLoadRomFile(JNIEnv* env, jobject, jstring absolutePath) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!g_retro_load_game) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(absolutePath, nullptr);
    g_game_path.assign(path);
    retro_game_info info { g_game_path.c_str(), nullptr, 0, nullptr };
    bool ok = g_retro_load_game(&info);
    if (ok && g_retro_get_system_av_info) {
        retro_system_av_info av{};
        g_retro_get_system_av_info(&av);
        if (av.timing.sample_rate >= 8000.0 && av.timing.sample_rate <= 192000.0) g_audio_sample_rate = av.timing.sample_rate;
    }
    env->ReleaseStringUTFChars(absolutePath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeRunFrame(JNIEnv* env, jobject, jint p1Mask, jint p2Mask, jfloat p1AnalogX, jfloat p1AnalogY, jfloat p2AnalogX, jfloat p2AnalogY, jintArray outPixels) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!g_retro_run) return 0;
    g_p1_analog_x = std::max(-1.f, std::min(1.f, static_cast<float>(p1AnalogX)));
    g_p1_analog_y = std::max(-1.f, std::min(1.f, static_cast<float>(p1AnalogY)));
    g_p2_analog_x = std::max(-1.f, std::min(1.f, static_cast<float>(p2AnalogX)));
    g_p2_analog_y = std::max(-1.f, std::min(1.f, static_cast<float>(p2AnalogY)));
    g_p1_mask = static_cast<uint32_t>(p1Mask);
    g_p2_mask = static_cast<uint32_t>(p2Mask);
    if (g_p1_analog_y < -0.45f) g_p1_mask |= (1u << 4);
    if (g_p1_analog_y > 0.45f) g_p1_mask |= (1u << 5);
    if (g_p1_analog_x < -0.45f) g_p1_mask |= (1u << 6);
    if (g_p1_analog_x > 0.45f) g_p1_mask |= (1u << 7);
    if (g_p2_analog_y < -0.45f) g_p2_mask |= (1u << 4);
    if (g_p2_analog_y > 0.45f) g_p2_mask |= (1u << 5);
    if (g_p2_analog_x < -0.45f) g_p2_mask |= (1u << 6);
    if (g_p2_analog_x > 0.45f) g_p2_mask |= (1u << 7);
    g_retro_run();
    if (g_pixels.empty()) return 0; // Do not present a fabricated blank frame before a core draws.
    jsize maxLen = env->GetArrayLength(outPixels);
    jsize copyCount = std::min<jsize>(maxLen, static_cast<jsize>(g_pixels.size()));
    if (copyCount > 0) {
        env->SetIntArrayRegion(outPixels, 0, copyCount, reinterpret_cast<const jint*>(g_pixels.data()));
    }
    return static_cast<jint>((g_width << 16) | (g_height & 0xFFFF));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeGetAudioSampleRate(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    return static_cast<jint>(g_audio_sample_rate + 0.5);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeReadAudio(JNIEnv* env, jobject, jshortArray outPcm) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
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
    std::lock_guard<std::mutex> lock(g_core_mutex);
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
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (!g_retro_unserialize) return JNI_FALSE;
    jsize len = env->GetArrayLength(stateBytes);
    jbyte* raw = env->GetByteArrayElements(stateBytes, nullptr);
    bool ok = g_retro_unserialize(raw, static_cast<size_t>(len));
    env->ReleaseByteArrayElements(stateBytes, raw, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_familink_emulator_NativeCoreBridge_nativeUnload(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_core_mutex);
    if (g_retro_unload_game) g_retro_unload_game();
    if (g_retro_deinit) g_retro_deinit();
    g_rom_bytes.clear(); g_game_path.clear(); g_pixels.clear(); g_audio_ring.clear();
    if (g_core_handle) { dlclose(g_core_handle); g_core_handle = nullptr; }
    g_retro_init = nullptr; g_retro_deinit = nullptr; g_retro_load_game = nullptr;
    g_retro_unload_game = nullptr; g_retro_run = nullptr; g_retro_get_system_av_info = nullptr;
}
