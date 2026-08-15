#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "UML.MamoBall";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Mamo Ball 4.6.15 / Unity 6000.0.59f2 / IL2CPP / ARM64.
//
// BallController.Kick(..., float kickStrength)
// RVA 0x2C9AC24: fmov s8, s2  -> fadd s8, s2, s2
// This doubles the kickStrength argument while leaving the remaining kick logic intact.
constexpr uintptr_t kSuperKickRva = 0x2C9AC24;
constexpr uint32_t kSuperKickExpected = 0x1E204048;
constexpr uint32_t kSuperKickPatch = 0x1E222848;

// PlayerController.ApplyJoystickState()
// RVA 0x2CCAFC4: fmov s8, s0 -> fadd s8, s0, s0
// s0 is the decrypted playerSpeed/sprintSpeed value, so both movement modes become 2x.
constexpr uintptr_t kSuperSpeedRva = 0x2CCAFC4;
constexpr uint32_t kSuperSpeedExpected = 0x1E204008;
constexpr uint32_t kSuperSpeedPatch = 0x1E202808;

std::mutex g_patchMutex;
uint32_t g_originalKick = 0;
uint32_t g_originalSpeed = 0;
bool g_savedKick = false;
bool g_savedSpeed = false;

std::mutex g_handlesMutex;
std::vector<void*> g_pluginHandles;

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

std::string setVerifiedPatch(bool enabled,
                             uintptr_t rva,
                             uint32_t expected,
                             uint32_t replacement,
                             uint32_t& original,
                             bool& saved,
                             const char* label) {
    std::lock_guard<std::mutex> lock(g_patchMutex);

    const uintptr_t base = findLibraryBase(kIl2CppName);
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    const uintptr_t address = base + rva;
    const uint32_t current = *reinterpret_cast<volatile uint32_t*>(address);

    if (enabled) {
        if (!saved) {
            if (current != expected) {
                char buffer[200];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura %s nao confere (RVA 0x%lx: %08x)",
                              label, static_cast<unsigned long>(rva), current);
                return buffer;
            }
            original = current;
            saved = true;
        }

        if (current != replacement) {
            std::string error;
            if (!writeInstruction(address, replacement, error)) {
                return std::string("ERROR: patch ") + label + ": " + error;
            }
        }

        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "%s enabled at %p (base=%p)", label,
                            reinterpret_cast<void*>(address),
                            reinterpret_cast<void*>(base));
        return std::string("OK: ") + label + " ativo";
    }

    if (!saved) return std::string("OK: ") + label + " desativado";

    const uint32_t now = *reinterpret_cast<volatile uint32_t*>(address);
    if (now == replacement) {
        std::string error;
        if (!writeInstruction(address, original, error)) {
            return std::string("ERROR: restore ") + label + ": " + error;
        }
    }
    return std::string("OK: ") + label + " desativado";
}

std::string setSuperKick(bool enabled) {
    return setVerifiedPatch(enabled,
                            kSuperKickRva,
                            kSuperKickExpected,
                            kSuperKickPatch,
                            g_originalKick,
                            g_savedKick,
                            "super chute x2");
}

std::string setSuperSpeed(bool enabled) {
    return setVerifiedPatch(enabled,
                            kSuperSpeedRva,
                            kSuperSpeedExpected,
                            kSuperSpeedPatch,
                            g_originalSpeed,
                            g_savedSpeed,
                            "super velocidade x2");
}
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_coreVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("umlcore/0.7.0-mamoball");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setMamoBallSuperKick(
        JNIEnv* env, jclass, jboolean enabled) {
    const std::string result = setSuperKick(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setMamoBallSuperSpeed(
        JNIEnv* env, jclass, jboolean enabled) {
    const std::string result = setSuperSpeed(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}

// Legacy Fire Zone overlay stub. The old source file remains in the tree but is not
// activated by the Mamo Ball launcher.
extern "C"
JNIEXPORT jfloatArray JNICALL
Java_dev_unitymodloader_app_NativeBridge_getFireZoneEspTargets(JNIEnv* env, jclass) {
    return env->NewFloatArray(0);
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
            continue;
        }

        const char* pluginMessage = init(package.c_str());
        std::string line = "CARREGADO " + name;
        if (pluginMessage != nullptr && pluginMessage[0] != '\0') {
            line += " - ";
            line += pluginMessage;
        }
        appendLine(report, line);
    }

    return env->NewStringUTF(report.c_str());
}
