#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

namespace {
constexpr const char* kTag = "UML.MamoAuthDiag";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Mamo Ball 4.6.15 / ARM64 diagnostic only.
//
// SplashActivity.CheckAuthorizationToken() RVA 0x29BBD3C is reached immediately
// after the splash progress is set to 20%. The original APK also stalls here when
// re-signed with a different certificate, strongly tying this stage to the app's
// auth/signing identity.
//
// For diagnosis, branch directly to SplashActivity.SendInitialRequests()
// RVA 0x29BC1C4. That routine begins by setting progress to 50%.
//
// 0x29BBD3C: str x30, [sp,#-0x20]!   = 0xF81E0FFE
// branch delta = 0x488 bytes = 290 instructions
// B 0x29BC1C4                       = 0x14000122
constexpr uintptr_t kCheckAuthorizationTokenRva = 0x29BBD3C;
constexpr uintptr_t kSendInitialRequestsRva = 0x29BC1C4;
constexpr uint32_t kExpectedCheckAuthEntry = 0xF81E0FFE;
constexpr uint32_t kBranchToInitialRequests = 0x14000122;

std::mutex gMutex;
uint32_t gOriginal = 0;
bool gSaved = false;
void* gPreloadedIl2Cpp = nullptr;

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

uintptr_t findLibraryBase() {
    LibrarySearch search{kIl2CppName, 0};
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

std::string setAuthDiagnosticLocked(bool enabled) {
    const uintptr_t base = findLibraryBase();
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    const uintptr_t address = base + kCheckAuthorizationTokenRva;
    const uint32_t current = *reinterpret_cast<volatile uint32_t*>(address);

    if (enabled) {
        if (!gSaved) {
            if (current == kBranchToInitialRequests) {
                return "OK: diagnostico 20% ja estava ativo";
            }
            if (current != kExpectedCheckAuthEntry) {
                char buffer[220];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura CheckAuthorizationToken nao confere "
                              "(RVA 0x%lx: %08x)",
                              static_cast<unsigned long>(kCheckAuthorizationTokenRva),
                              current);
                return buffer;
            }
            gOriginal = current;
            gSaved = true;
        }

        if (current != kBranchToInitialRequests) {
            std::string error;
            if (!writeInstruction(address, kBranchToInitialRequests, error)) {
                return "ERROR: auth diag: " + error;
            }
        }

        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "20%% auth diagnostic active: %p -> RVA 0x%lx",
                            reinterpret_cast<void*>(address),
                            static_cast<unsigned long>(kSendInitialRequestsRva));
        return "OK: diagnostico 20% ativo -> proxima fase 50%";
    }

    if (!gSaved) return "OK: diagnostico 20% desativado";

    const uint32_t now = *reinterpret_cast<volatile uint32_t*>(address);
    if (now == kBranchToInitialRequests) {
        std::string error;
        if (!writeInstruction(address, gOriginal, error)) {
            return "ERROR: restore auth diag: " + error;
        }
    }
    return "OK: diagnostico 20% desativado";
}

std::string preloadAndPatch(const std::string& nativeLibraryDir) {
    std::lock_guard<std::mutex> lock(gMutex);

    if (findLibraryBase() == 0) {
        if (nativeLibraryDir.empty()) return "ERROR: nativeLibraryDir vazio";

        std::string path = nativeLibraryDir;
        if (!path.empty() && path.back() != '/') path += '/';
        path += kIl2CppName;

        dlerror();
        gPreloadedIl2Cpp = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (gPreloadedIl2Cpp == nullptr) {
            const char* err = dlerror();
            return std::string("ERROR: preload libil2cpp auth diag falhou: ") +
                    (err != nullptr ? err : "dlopen sem detalhe");
        }

        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Preloaded target IL2CPP for auth diagnostic: %s",
                            path.c_str());
    }

    return setAuthDiagnosticLocked(true);
}

std::string jstringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_prepareMamoBallAuthDiagnostic(
        JNIEnv* env, jclass, jstring nativeLibraryDir) {
    const std::string result = preloadAndPatch(jstringToString(env, nativeLibraryDir));
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setMamoBallAuthDiagnostic(
        JNIEnv* env, jclass, jboolean enabled) {
    std::lock_guard<std::mutex> lock(gMutex);
    const std::string result = setAuthDiagnosticLocked(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}
