#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "UML.Native";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Fire Zone FZS.0403.GP / Unity 6000.0.68f1 / ARM64.
// Infinite ammo: <FireOneShot>d__351.MoveNext() decrements magazine/reserve
// with sub w8, w8, #1. NOP only those verified decrement instructions.
constexpr uintptr_t kBulletsLeftSubRva = 0x115ABF4;
constexpr uintptr_t kReserveAmmoSubRva = 0x115AD6C;
constexpr uint32_t kExpectedSubW8 = 0x51000508;
constexpr uint32_t kArm64Nop = 0xD503201F;

// Infinite coins: Prefs.get_CurrentCash() / Prefs.set_CurrentCash(int).
// Getter is replaced with: return 999999;
// Setter is replaced with: return;  (preserves the user's real saved balance)
constexpr uintptr_t kCashGetterRva = 0x11605C0;
constexpr uintptr_t kCashSetterRva = 0x11605FC;
constexpr uint32_t kCashGetterExpected0 = 0xA9BF4FFE;
constexpr uint32_t kCashGetterExpected1 = 0x3941D008;
constexpr uint32_t kCashGetterExpected2 = 0xAA0003F3;
constexpr uint32_t kCashSetterExpected0 = 0xAA0003E8;
constexpr uint32_t kCashPatch0 = 0x528847E0;
constexpr uint32_t kCashPatch1 = 0x72A001E0;
constexpr uint32_t kArm64Ret = 0xD65F03C0;

// ESP hologram: reads the game's GameController.npcAIs and projects each hostile
// AI position through the same Unity camera. Drawing is done by a transparent
// Android View above Unity, so the marker is visible even when geometry occludes it.
constexpr uintptr_t kGameControllerGetInstanceRva = 0x115D584;
constexpr uintptr_t kCameraGetMainInjectedRva = 0x2193CFC;
constexpr uintptr_t kTransformGetPositionInjectedRva = 0x21D0480;
constexpr uintptr_t kCameraWorldToScreenInjectedRva = 0x21937A8;
constexpr uintptr_t kScreenWidthRva = 0x219D100;
constexpr uintptr_t kScreenHeightRva = 0x219D128;

constexpr uintptr_t kGameControllerNpcAIsOffset = 0xA8;
constexpr uintptr_t kAiCharacterDamageOffset = 0x70;
constexpr uintptr_t kAiFactionOffset = 0xF0;
constexpr uintptr_t kAiTransformOffset = 0x100;
constexpr uintptr_t kCharacterHitPointsOffset = 0x30;
constexpr uintptr_t kUnityObjectCachedPtrOffset = 0x10;
constexpr uintptr_t kIl2CppArrayLengthOffset = 0x18;
constexpr uintptr_t kIl2CppArrayDataOffset = 0x20;
constexpr size_t kMaxEspTargets = 64;

struct Vec3 {
    float x;
    float y;
    float z;
};

std::mutex g_handlesMutex;
std::vector<void*> g_pluginHandles;
std::mutex g_patchMutex;
std::mutex g_espMutex;

uint32_t g_originalBullets = 0;
uint32_t g_originalReserve = 0;
bool g_savedAmmoOriginals = false;
bool g_ammoPatchActive = false;

uint32_t g_originalCashGetter[3] = {0, 0, 0};
uint32_t g_originalCashSetter = 0;
bool g_savedCashOriginals = false;
bool g_cashPatchActive = false;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool endsWith(const std::string& value, const std::string& suffix) {
    return value.size() >= suffix.size() &&
           value.compare(value.size() - suffix.size(), suffix.size(), suffix) == 0;
}

std::vector<std::string> findPlugins(const std::string& directory) {
    std::vector<std::string> result;
    DIR* dir = opendir(directory.c_str());
    if (dir == nullptr) return result;

    while (dirent* entry = readdir(dir)) {
        const std::string name(entry->d_name);
        if (name == "." || name == ".." || !endsWith(name, ".so")) continue;
        result.push_back(name);
    }
    closedir(dir);
    std::sort(result.begin(), result.end());
    return result;
}

void appendLine(std::string& report, const std::string& line) {
    if (!report.empty()) report += '\n';
    report += line;
}

struct LibrarySearch {
    const char* needle;
    uintptr_t base;
};

int libraryCallback(dl_phdr_info* info, size_t, void* data) {
    auto* search = static_cast<LibrarySearch*>(data);
    if (info == nullptr || search == nullptr || info->dlpi_name == nullptr) return 0;

    const char* name = std::strrchr(info->dlpi_name, '/');
    name = name != nullptr ? name + 1 : info->dlpi_name;
    if (std::strcmp(name, search->needle) == 0) {
        search->base = static_cast<uintptr_t>(info->dlpi_addr);
        return 1;
    }
    return 0;
}

uintptr_t findLibraryBase(const char* libraryName) {
    LibrarySearch search{libraryName, 0};
    dl_iterate_phdr(libraryCallback, &search);
    return search.base;
}

bool writeInstruction(uintptr_t address, uint32_t instruction, std::string& error) {
    const long pageSizeLong = sysconf(_SC_PAGESIZE);
    if (pageSizeLong <= 0) {
        error = "page size invalido";
        return false;
    }

    const uintptr_t pageSize = static_cast<uintptr_t>(pageSizeLong);
    const uintptr_t pageStart = address & ~(pageSize - 1u);

    if (mprotect(reinterpret_cast<void*>(pageStart), pageSize,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        error = "mprotect RWX falhou";
        return false;
    }

    *reinterpret_cast<volatile uint32_t*>(address) = instruction;
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address + sizeof(uint32_t)));

    if (mprotect(reinterpret_cast<void*>(pageStart), pageSize,
                 PROT_READ | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "mprotect RX restore failed at %p",
                            reinterpret_cast<void*>(pageStart));
    }
    return true;
}

std::string setFireZoneAmmoPatch(bool enabled) {
    std::lock_guard<std::mutex> lock(g_patchMutex);

    const uintptr_t base = findLibraryBase(kIl2CppName);
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    const uintptr_t bulletsAddress = base + kBulletsLeftSubRva;
    const uintptr_t reserveAddress = base + kReserveAmmoSubRva;
    const uint32_t bulletsNow = *reinterpret_cast<volatile uint32_t*>(bulletsAddress);
    const uint32_t reserveNow = *reinterpret_cast<volatile uint32_t*>(reserveAddress);

    if (enabled) {
        if (!g_savedAmmoOriginals) {
            if (bulletsNow != kExpectedSubW8 || reserveNow != kExpectedSubW8) {
                char buffer[180];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura ammo nao confere (0x%08x / 0x%08x)",
                              bulletsNow, reserveNow);
                return buffer;
            }
            g_originalBullets = bulletsNow;
            g_originalReserve = reserveNow;
            g_savedAmmoOriginals = true;
        }

        std::string error;
        if (bulletsNow != kArm64Nop && !writeInstruction(bulletsAddress, kArm64Nop, error))
            return "ERROR: patch bulletsLeft: " + error;
        if (reserveNow != kArm64Nop && !writeInstruction(reserveAddress, kArm64Nop, error))
            return "ERROR: patch ammo: " + error;

        g_ammoPatchActive = true;
        return "OK: municao infinita ativa";
    }

    if (!g_savedAmmoOriginals) return "OK: municao infinita desativada";

    std::string error;
    if (bulletsNow == kArm64Nop && !writeInstruction(bulletsAddress, g_originalBullets, error))
        return "ERROR: restore bulletsLeft: " + error;
    if (reserveNow == kArm64Nop && !writeInstruction(reserveAddress, g_originalReserve, error))
        return "ERROR: restore ammo: " + error;

    g_ammoPatchActive = false;
    return "OK: municao infinita desativada";
}

std::string setFireZoneCashPatch(bool enabled) {
    std::lock_guard<std::mutex> lock(g_patchMutex);

    const uintptr_t base = findLibraryBase(kIl2CppName);
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    const uintptr_t getter = base + kCashGetterRva;
    const uintptr_t setter = base + kCashSetterRva;

    const uint32_t g0 = *reinterpret_cast<volatile uint32_t*>(getter + 0);
    const uint32_t g1 = *reinterpret_cast<volatile uint32_t*>(getter + 4);
    const uint32_t g2 = *reinterpret_cast<volatile uint32_t*>(getter + 8);
    const uint32_t s0 = *reinterpret_cast<volatile uint32_t*>(setter);

    if (enabled) {
        if (!g_savedCashOriginals) {
            if (g0 != kCashGetterExpected0 || g1 != kCashGetterExpected1 ||
                g2 != kCashGetterExpected2 || s0 != kCashSetterExpected0) {
                char buffer[220];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura cash nao confere (%08x/%08x/%08x/%08x)",
                              g0, g1, g2, s0);
                return buffer;
            }
            g_originalCashGetter[0] = g0;
            g_originalCashGetter[1] = g1;
            g_originalCashGetter[2] = g2;
            g_originalCashSetter = s0;
            g_savedCashOriginals = true;
        }

        std::string error;
        if (g0 != kCashPatch0 && !writeInstruction(getter + 0, kCashPatch0, error))
            return "ERROR: patch cash getter[0]: " + error;
        if (g1 != kCashPatch1 && !writeInstruction(getter + 4, kCashPatch1, error))
            return "ERROR: patch cash getter[1]: " + error;
        if (g2 != kArm64Ret && !writeInstruction(getter + 8, kArm64Ret, error))
            return "ERROR: patch cash getter[2]: " + error;
        if (s0 != kArm64Ret && !writeInstruction(setter, kArm64Ret, error))
            return "ERROR: patch cash setter: " + error;

        g_cashPatchActive = true;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Fire Zone infinite cash active; getter=%p setter=%p",
                            reinterpret_cast<void*>(getter), reinterpret_cast<void*>(setter));
        return "OK: moedas infinitas ativas (999999)";
    }

    if (!g_savedCashOriginals) return "OK: moedas infinitas desativadas";

    std::string error;
    const uint32_t now0 = *reinterpret_cast<volatile uint32_t*>(getter + 0);
    const uint32_t now1 = *reinterpret_cast<volatile uint32_t*>(getter + 4);
    const uint32_t now2 = *reinterpret_cast<volatile uint32_t*>(getter + 8);
    const uint32_t nowS = *reinterpret_cast<volatile uint32_t*>(setter);

    if (now0 == kCashPatch0 && !writeInstruction(getter + 0, g_originalCashGetter[0], error))
        return "ERROR: restore cash getter[0]: " + error;
    if (now1 == kCashPatch1 && !writeInstruction(getter + 4, g_originalCashGetter[1], error))
        return "ERROR: restore cash getter[1]: " + error;
    if (now2 == kArm64Ret && !writeInstruction(getter + 8, g_originalCashGetter[2], error))
        return "ERROR: restore cash getter[2]: " + error;
    if (nowS == kArm64Ret && !writeInstruction(setter, g_originalCashSetter, error))
        return "ERROR: restore cash setter: " + error;

    g_cashPatchActive = false;
    return "OK: moedas infinitas desativadas";
}

std::vector<float> getFireZoneEspTargetsNative() {
    std::lock_guard<std::mutex> lock(g_espMutex);
    std::vector<float> out;

    const uintptr_t base = findLibraryBase(kIl2CppName);
    if (base == 0) return out;

    using GetInstanceFn = void* (*)(const void* method);
    using GetNativePtrFn = void* (*)(const void* method);
    using GetIntFn = int (*)(const void* method);
    using GetPositionInjectedFn = void (*)(void* unitySelf, Vec3* ret, const void* method);
    using WorldToScreenInjectedFn = void (*)(void* unitySelf, const Vec3* position,
                                             int eye, Vec3* ret, const void* method);

    auto getController = reinterpret_cast<GetInstanceFn>(base + kGameControllerGetInstanceRva);
    auto getMainCameraNative = reinterpret_cast<GetNativePtrFn>(base + kCameraGetMainInjectedRva);
    auto getPosition = reinterpret_cast<GetPositionInjectedFn>(base + kTransformGetPositionInjectedRva);
    auto worldToScreen = reinterpret_cast<WorldToScreenInjectedFn>(base + kCameraWorldToScreenInjectedRva);
    auto getScreenWidth = reinterpret_cast<GetIntFn>(base + kScreenWidthRva);
    auto getScreenHeight = reinterpret_cast<GetIntFn>(base + kScreenHeightRva);

    const int screenWidth = getScreenWidth(nullptr);
    const int screenHeight = getScreenHeight(nullptr);
    if (screenWidth < 100 || screenHeight < 100 || screenWidth > 10000 || screenHeight > 10000)
        return out;

    void* cameraNative = getMainCameraNative(nullptr);
    if (cameraNative == nullptr) return out;

    void* controller = getController(nullptr);
    if (controller == nullptr) return out;

    void* npcArray = *reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(controller) +
                                               kGameControllerNpcAIsOffset);
    if (npcArray == nullptr) return out;

    const size_t length = *reinterpret_cast<size_t*>(reinterpret_cast<uintptr_t>(npcArray) +
                                                     kIl2CppArrayLengthOffset);
    if (length > 512) return out;

    out.reserve(2 + std::min(length, kMaxEspTargets) * 5);
    out.push_back(static_cast<float>(screenWidth));
    out.push_back(static_cast<float>(screenHeight));

    auto** items = reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(npcArray) +
                                            kIl2CppArrayDataOffset);

    size_t emitted = 0;
    for (size_t i = 0; i < length && emitted < kMaxEspTargets; ++i) {
        void* ai = items[i];
        if (ai == nullptr) continue;

        const int faction = *reinterpret_cast<int*>(reinterpret_cast<uintptr_t>(ai) + kAiFactionOffset);
        if (faction == 1 || faction <= 0 || faction > 8) continue;

        void* damage = *reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(ai) +
                                                 kAiCharacterDamageOffset);
        float hp = 1.0f;
        if (damage != nullptr) {
            hp = *reinterpret_cast<float*>(reinterpret_cast<uintptr_t>(damage) +
                                          kCharacterHitPointsOffset);
            if (!std::isfinite(hp) || hp <= 0.0f) continue;
        }

        void* transform = *reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(ai) +
                                                    kAiTransformOffset);
        if (transform == nullptr) continue;

        void* transformNative = *reinterpret_cast<void**>(reinterpret_cast<uintptr_t>(transform) +
                                                          kUnityObjectCachedPtrOffset);
        if (transformNative == nullptr) continue;

        Vec3 feet{};
        getPosition(transformNative, &feet, nullptr);
        if (!std::isfinite(feet.x) || !std::isfinite(feet.y) || !std::isfinite(feet.z)) continue;

        Vec3 head = feet;
        head.y += 1.75f;

        Vec3 feetScreen{};
        Vec3 headScreen{};
        worldToScreen(cameraNative, &feet, 2, &feetScreen, nullptr);
        worldToScreen(cameraNative, &head, 2, &headScreen, nullptr);

        if (!std::isfinite(feetScreen.x) || !std::isfinite(feetScreen.y) ||
            !std::isfinite(feetScreen.z) || !std::isfinite(headScreen.x) ||
            !std::isfinite(headScreen.y) || feetScreen.z <= 0.05f || headScreen.z <= 0.05f) {
            continue;
        }

        if (feetScreen.x < -screenWidth || feetScreen.x > screenWidth * 2.0f ||
            feetScreen.y < -screenHeight || feetScreen.y > screenHeight * 2.0f) {
            continue;
        }

        out.push_back(feetScreen.x);
        out.push_back(feetScreen.y);
        out.push_back(headScreen.y);
        out.push_back(feetScreen.z);
        out.push_back(hp);
        ++emitted;
    }

    return out;
}
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_coreVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("umlcore/0.6.2-firezone");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setFireZoneInfiniteAmmo(
        JNIEnv* env, jclass, jboolean enabled) {
    const std::string result = setFireZoneAmmoPatch(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setFireZoneInfiniteCoins(
        JNIEnv* env, jclass, jboolean enabled) {
    const std::string result = setFireZoneCashPatch(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_dev_unitymodloader_app_NativeBridge_getFireZoneEspTargets(JNIEnv* env, jclass) {
    const std::vector<float> values = getFireZoneEspTargetsNative();
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_loadNativePlugins(
        JNIEnv* env,
        jclass,
        jstring pluginDirectory,
        jstring packageName) {
    const std::string directory = toString(env, pluginDirectory);
    const std::string package = toString(env, packageName);
    std::string report;

    if (directory.empty()) return env->NewStringUTF("Diretorio de plugins vazio");

    const std::vector<std::string> plugins = findPlugins(directory);
    if (plugins.empty()) {
        const std::string message = "Nenhum plugin nativo .so encontrado em " + directory;
        __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
        return env->NewStringUTF(message.c_str());
    }

    for (const std::string& name : plugins) {
        const std::string path = directory + "/" + name;
        dlerror();
        void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (handle == nullptr) {
            const char* error = dlerror();
            const std::string line = "ERRO " + name + ": " +
                    (error != nullptr ? error : "dlopen falhou");
            appendLine(report, line);
            __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", line.c_str());
            continue;
        }

        {
            std::lock_guard<std::mutex> lock(g_handlesMutex);
            g_pluginHandles.push_back(handle);
        }

        dlerror();
        using InitFn = const char* (*)(const char*);
        auto init = reinterpret_cast<InitFn>(dlsym(handle, "uml_plugin_init"));
        const char* symbolError = dlerror();

        if (init == nullptr || symbolError != nullptr) {
            const std::string line = "CARREGADO " + name + " (sem uml_plugin_init)";
            appendLine(report, line);
            __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
            continue;
        }

        const char* pluginMessage = init(package.c_str());
        std::string line = "CARREGADO " + name;
        if (pluginMessage != nullptr && pluginMessage[0] != '\0') {
            line += " - ";
            line += pluginMessage;
        }
        appendLine(report, line);
        __android_log_print(ANDROID_LOG_INFO, kTag, "%s", line.c_str());
    }

    return env->NewStringUTF(report.c_str());
}
