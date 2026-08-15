#include <jni.h>
#include <android/log.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

namespace {
constexpr const char* kTag = "UML.MamoBoot";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Mamo Ball 4.6.15 / ARM64.
// SplashActivity reaches 20% after GET_CONFIG, then opens LoginFragment when
// PreferenceHelper.GetAuthToken() is empty in the loader sandbox.
//
// LoginFragment.OnLayoutReady() RVA 0x2C897B0 normally checks first-login and
// social-login flags before eventually calling ProcessGuestLogin(bool).
// In the hosted CTF environment we deliberately route this callback straight to
// ProcessGuestLogin(true), so the loader gets its own guest session instead of
// depending on the original app's private PlayerPrefs/auth token.
constexpr uintptr_t kLoginOnLayoutReadyRva = 0x2C897B0;
constexpr uint32_t kExpected0 = 0xF81E0FFE; // str x30, [sp,#-0x20]!
constexpr uint32_t kExpected1 = 0xA9014FF4; // stp x20, x19, [sp,#0x10]
constexpr uint32_t kPatch0 = 0x52800021;    // mov w1, #1
constexpr uint32_t kPatch1 = 0x14000128;    // b 0x2C89C54 (ProcessGuestLogin)

std::mutex gMutex;
uint32_t gOriginal0 = 0;
uint32_t gOriginal1 = 0;
bool gSaved = false;

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

std::string setGuestBootstrap(bool enabled) {
    std::lock_guard<std::mutex> lock(gMutex);

    const uintptr_t base = findLibraryBase();
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    const uintptr_t a0 = base + kLoginOnLayoutReadyRva;
    const uintptr_t a1 = a0 + 4;
    const uint32_t now0 = *reinterpret_cast<volatile uint32_t*>(a0);
    const uint32_t now1 = *reinterpret_cast<volatile uint32_t*>(a1);

    if (enabled) {
        if (!gSaved) {
            if (now0 != kExpected0 || now1 != kExpected1) {
                char buffer[220];
                std::snprintf(buffer, sizeof(buffer),
                              "ERROR: assinatura guest bootstrap nao confere (%08x/%08x)",
                              now0, now1);
                return buffer;
            }
            gOriginal0 = now0;
            gOriginal1 = now1;
            gSaved = true;
        }

        std::string error;
        if (now0 != kPatch0 && !writeInstruction(a0, kPatch0, error))
            return "ERROR: guest bootstrap[0]: " + error;
        if (now1 != kPatch1 && !writeInstruction(a1, kPatch1, error))
            return "ERROR: guest bootstrap[1]: " + error;

        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Guest bootstrap active at %p", reinterpret_cast<void*>(a0));
        return "OK: bootstrap guest CTF ativo";
    }

    if (!gSaved) return "OK: bootstrap guest CTF desativado";

    std::string error;
    const uint32_t current0 = *reinterpret_cast<volatile uint32_t*>(a0);
    const uint32_t current1 = *reinterpret_cast<volatile uint32_t*>(a1);
    if (current0 == kPatch0 && !writeInstruction(a0, gOriginal0, error))
        return "ERROR: restore guest bootstrap[0]: " + error;
    if (current1 == kPatch1 && !writeInstruction(a1, gOriginal1, error))
        return "ERROR: restore guest bootstrap[1]: " + error;

    return "OK: bootstrap guest CTF desativado";
}
} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setMamoBallGuestBootstrap(
        JNIEnv* env, jclass, jboolean enabled) {
    const std::string result = setGuestBootstrap(enabled == JNI_TRUE);
    return env->NewStringUTF(result.c_str());
}
