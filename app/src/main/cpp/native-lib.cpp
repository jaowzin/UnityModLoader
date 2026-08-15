#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "UML.Native";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Fire Zone FZS.0403.GP / Unity 6000.0.68f1 / ARM64.
// <FireOneShot>d__351.MoveNext() decrements magazine/reserve with the same
// ARM64 instruction: sub w8, w8, #1. Replacing only those two SUB instructions
// with NOP keeps the original firing/reload logic intact while preserving ammo.
constexpr uintptr_t kBulletsLeftSubRva = 0x115ABF4;
constexpr uintptr_t kReserveAmmoSubRva = 0x115AD6C;
constexpr uint32_t kExpectedSubW8 = 0x51000508;
constexpr uint32_t kArm64Nop = 0xD503201F;

std::mutex g_handlesMutex;
std::vector<void*> g_pluginHandles;
std::mutex g_patchMutex;
uint32_t g_originalBullets = 0;
uint32_t g_originalReserve = 0;
bool g_savedOriginals = false;
bool g_ammoPatchActive = false;

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
    if (base == 0) {
        return "WAIT: libil2cpp.so ainda nao carregou";
    }

    const uintptr_t bulletsAddress = base + kBulletsLeftSubRva;
    const uintptr_t reserveAddress = base + kReserveAmmoSubRva;
    const uint32_t bulletsNow = *reinterpret_cast<volatile uint32_t*>(bulletsAddress);
    const uint32_t reserveNow = *reinterpret_cast<volatile uint32_t*>(reserveAddress);

    if (enabled) {
        if (!g_savedOriginals) {
            if (bulletsNow != kExpectedSubW8 || reserveNow != kExpectedSubW8) {
                char buffer[180];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura da build nao confere (0x%08x / 0x%08x)",
                              bulletsNow, reserveNow);
                return buffer;
            }
            g_originalBullets = bulletsNow;
            g_originalReserve = reserveNow;
            g_savedOriginals = true;
        }

        std::string error;
        if (bulletsNow != kArm64Nop && !writeInstruction(bulletsAddress, kArm64Nop, error)) {
            return "ERROR: patch bulletsLeft: " + error;
        }
        if (reserveNow != kArm64Nop && !writeInstruction(reserveAddress, kArm64Nop, error)) {
            return "ERROR: patch ammo: " + error;
        }

        g_ammoPatchActive = true;
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Fire Zone infinite ammo active; il2cpp=%p",
                            reinterpret_cast<void*>(base));
        return "OK: municao infinita ativa";
    }

    if (!g_savedOriginals) {
        return "OK: municao infinita desativada";
    }

    std::string error;
    if (bulletsNow == kArm64Nop && !writeInstruction(bulletsAddress, g_originalBullets, error)) {
        return "ERROR: restore bulletsLeft: " + error;
    }
    if (reserveNow == kArm64Nop && !writeInstruction(reserveAddress, g_originalReserve, error)) {
        return "ERROR: restore ammo: " + error;
    }

    g_ammoPatchActive = false;
    return "OK: municao infinita desativada";
}
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_coreVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("umlcore/0.6.0-firezone");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setFireZoneInfiniteAmmo(
        JNIEnv* env,
        jclass,
        jboolean enabled) {
    const std::string result = setFireZoneAmmoPatch(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
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

    if (directory.empty()) {
        return env->NewStringUTF("Diretorio de plugins vazio");
    }

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
            const std::string line = "ERRO " + name + ": " + (error != nullptr ? error : "dlopen falhou");
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
