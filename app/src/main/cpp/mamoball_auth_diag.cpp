#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>

namespace {
constexpr const char* kTag = "UML.MamoApiDiag";
constexpr const char* kIl2CppName = "libil2cpp.so";

// Mamo Ball 4.6.15 / ARM64 / IL2CPP.
// These are diagnostic hooks only; they do not alter API results.
//
// ApiManager.CallOnApiRequestSend(ApiMethodType, ApiRequest)
constexpr uintptr_t kRequestRva = 0x29D25A0;
constexpr uint32_t kRequestExpected[4] = {
        0xF81C0FFE, // str x30, [sp,#-0x40]!
        0xA9015FF8, // stp x24, x23, [sp,#0x10]
        0xA90257F6, // stp x22, x21, [sp,#0x20]
        0xA9034FF4  // stp x20, x19, [sp,#0x30]
};

// ApiManager.CallOnApiResponseReceive(ApiMethodType, ApiResponse, bool)
constexpr uintptr_t kResponseRva = 0x29D4368;
constexpr uint32_t kResponseExpected[4] = {
        0xA9BC67FE, // stp x30, x25, [sp,#-0x40]!
        0xA9015FF8, // stp x24, x23, [sp,#0x10]
        0xA90257F6, // stp x22, x21, [sp,#0x20]
        0xA9034FF4  // stp x20, x19, [sp,#0x30]
};

using RequestFn = void (*)(void*, int32_t, void*, const void*);
using ResponseFn = void (*)(void*, int32_t, void*, bool, const void*);

std::mutex gMutex;
std::mutex gStatusMutex;
RequestFn gOriginalRequest = nullptr;
ResponseFn gOriginalResponse = nullptr;
bool gInstalled = false;
void* gPreloadedIl2Cpp = nullptr;
std::string gStatus = "API DIAG: aguardando inicializacao";

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

const char* apiName(int32_t methodType) {
    switch (methodType) {
        case 0: return "GET_INIT_CONFIG";
        case 1: return "GET_CONFIG";
        case 2: return "GET_INIT_DATA";
        case 3: return "GET_PLAYER_POWER_CONFIG";
        case 4: return "GET_ASSET_SERVERS";
        case 5: return "GET_TEST_USERS";
        case 6: return "GUEST_REGISTER";
        case 7: return "REFRESH_TOKEN";
        case 64: return "LOGIN_WITH_SOCIAL";
        default: return "API";
    }
}

void setStatus(const std::string& value) {
    {
        std::lock_guard<std::mutex> lock(gStatusMutex);
        gStatus = value;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", value.c_str());
}

std::string getStatus() {
    std::lock_guard<std::mutex> lock(gStatusMutex);
    return gStatus;
}

std::string il2cppStringToUtf8(void* raw, size_t maxChars = 180) {
    if (raw == nullptr) return {};

    // Unity IL2CPP System.String layout on 64-bit:
    // Il2CppObject (16 bytes), int32 length, UTF-16 chars at +0x14.
    const auto* bytes = static_cast<const uint8_t*>(raw);
    int32_t length = 0;
    std::memcpy(&length, bytes + 0x10, sizeof(length));
    if (length <= 0 || length > 1'000'000) return {};

    const auto* chars = reinterpret_cast<const uint16_t*>(bytes + 0x14);
    const size_t count = std::min(static_cast<size_t>(length), maxChars);
    std::string out;
    out.reserve(count);

    for (size_t i = 0; i < count; ++i) {
        uint32_t c = chars[i];
        if (c == '\r' || c == '\n' || c == '\t') {
            out.push_back(' ');
        } else if (c < 0x80) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (c >> 6)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < count) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                ++i;
                const uint32_t cp = 0x10000 + (((c - 0xD800) << 10) | (low - 0xDC00));
                out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else {
                out += "?";
            }
        } else {
            out.push_back(static_cast<char>(0xE0 | (c >> 12)));
            out.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        }
    }

    if (static_cast<size_t>(length) > count) out += "...";
    return out;
}

void emitAbsoluteJump(uint8_t* dst, uintptr_t target) {
    // ldr x17, #8 ; br x17 ; .quad target
    const uint32_t ldrX17 = 0x58000051;
    const uint32_t brX17 = 0xD61F0220;
    std::memcpy(dst, &ldrX17, sizeof(ldrX17));
    std::memcpy(dst + 4, &brX17, sizeof(brX17));
    std::memcpy(dst + 8, &target, sizeof(target));
}

bool makeWritableExecutable(uintptr_t address, size_t length, std::string& error) {
    const long pageSizeLong = sysconf(_SC_PAGESIZE);
    if (pageSizeLong <= 0) {
        error = "page size invalido";
        return false;
    }
    const uintptr_t pageSize = static_cast<uintptr_t>(pageSizeLong);
    const uintptr_t start = address & ~(pageSize - 1u);
    const uintptr_t end = (address + length + pageSize - 1u) & ~(pageSize - 1u);
    if (mprotect(reinterpret_cast<void*>(start), end - start,
                 PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        error = "mprotect RWX falhou";
        return false;
    }
    return true;
}

void restoreExecutable(uintptr_t address, size_t length) {
    const long pageSizeLong = sysconf(_SC_PAGESIZE);
    if (pageSizeLong <= 0) return;
    const uintptr_t pageSize = static_cast<uintptr_t>(pageSizeLong);
    const uintptr_t start = address & ~(pageSize - 1u);
    const uintptr_t end = (address + length + pageSize - 1u) & ~(pageSize - 1u);
    if (mprotect(reinterpret_cast<void*>(start), end - start, PROT_READ | PROT_EXEC) != 0) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "mprotect RX restore failed at %p",
                            reinterpret_cast<void*>(start));
    }
}

bool installInlineHook(uintptr_t address,
                       const uint32_t expected[4],
                       void* replacement,
                       void** original,
                       std::string& error) {
    const auto* current = reinterpret_cast<const uint32_t*>(address);
    for (size_t i = 0; i < 4; ++i) {
        if (current[i] != expected[i]) {
            char buffer[180];
            std::snprintf(buffer, sizeof(buffer),
                          "assinatura hook nao confere +0x%zx (%08x != %08x)",
                          i * 4, current[i], expected[i]);
            error = buffer;
            return false;
        }
    }

    constexpr size_t kPatchSize = 16;
    constexpr size_t kTrampolineSize = 32;
    void* mem = mmap(nullptr, kTrampolineSize,
                     PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        error = "mmap trampoline falhou";
        return false;
    }

    auto* trampoline = static_cast<uint8_t*>(mem);
    std::memcpy(trampoline, reinterpret_cast<const void*>(address), kPatchSize);
    emitAbsoluteJump(trampoline + kPatchSize, address + kPatchSize);
    __builtin___clear_cache(reinterpret_cast<char*>(trampoline),
                            reinterpret_cast<char*>(trampoline + kTrampolineSize));

    if (mprotect(mem, kTrampolineSize, PROT_READ | PROT_EXEC) != 0) {
        munmap(mem, kTrampolineSize);
        error = "mprotect trampoline RX falhou";
        return false;
    }

    if (!makeWritableExecutable(address, kPatchSize, error)) {
        munmap(mem, kTrampolineSize);
        return false;
    }

    uint8_t patch[kPatchSize];
    emitAbsoluteJump(patch, reinterpret_cast<uintptr_t>(replacement));
    std::memcpy(reinterpret_cast<void*>(address), patch, sizeof(patch));
    __builtin___clear_cache(reinterpret_cast<char*>(address),
                            reinterpret_cast<char*>(address + kPatchSize));
    restoreExecutable(address, kPatchSize);

    *original = trampoline;
    return true;
}

void onRequest(void* self, int32_t methodType, void* request, const void* method) {
    char buffer[240];
    std::snprintf(buffer, sizeof(buffer),
                  "REQ %s (%d) enviado - aguardando resposta",
                  apiName(methodType), methodType);
    setStatus(buffer);

    RequestFn original = gOriginalRequest;
    if (original != nullptr) original(self, methodType, request, method);
}

void onResponse(void* self, int32_t methodType, void* apiResponse,
                bool isSuccess, const void* method) {
    int32_t apiErrorType = -1;
    int32_t statusCode = -1;
    std::string sdkMessage;
    std::string responseBody;

    if (apiResponse != nullptr) {
        const auto* bytes = static_cast<const uint8_t*>(apiResponse);
        std::memcpy(&apiErrorType, bytes + 0x20, sizeof(apiErrorType));
        void* sdkString = nullptr;
        void* responseString = nullptr;
        std::memcpy(&sdkString, bytes + 0x28, sizeof(sdkString));
        std::memcpy(&responseString, bytes + 0x30, sizeof(responseString));
        std::memcpy(&statusCode, bytes + 0x38, sizeof(statusCode));
        sdkMessage = il2cppStringToUtf8(sdkString, 120);
        responseBody = il2cppStringToUtf8(responseString, 180);
    }

    std::string status = "RESP ";
    status += apiName(methodType);
    status += " (" + std::to_string(methodType) + ")";
    status += " HTTP=" + std::to_string(statusCode);
    status += isSuccess ? " success=SIM" : " success=NAO";
    status += " errType=" + std::to_string(apiErrorType);
    if (!sdkMessage.empty()) status += " msg=" + sdkMessage;
    if (!responseBody.empty()) status += " body=" + responseBody;
    setStatus(status);

    ResponseFn original = gOriginalResponse;
    if (original != nullptr) original(self, methodType, apiResponse, isSuccess, method);
}

std::string installDiagnosticsLocked() {
    if (gInstalled) return "OK: API diag hooks ativos";

    const uintptr_t base = findLibraryBase();
    if (base == 0) return "WAIT: libil2cpp.so ainda nao carregou";

    std::string error;
    void* requestTrampoline = nullptr;
    if (!installInlineHook(base + kRequestRva,
                           kRequestExpected,
                           reinterpret_cast<void*>(&onRequest),
                           &requestTrampoline,
                           error)) {
        return "ERROR: hook request: " + error;
    }
    gOriginalRequest = reinterpret_cast<RequestFn>(requestTrampoline);

    void* responseTrampoline = nullptr;
    if (!installInlineHook(base + kResponseRva,
                           kResponseExpected,
                           reinterpret_cast<void*>(&onResponse),
                           &responseTrampoline,
                           error)) {
        return "ERROR: hook response: " + error;
    }
    gOriginalResponse = reinterpret_cast<ResponseFn>(responseTrampoline);

    gInstalled = true;
    setStatus("API DIAG: hooks ativos - aguardando requests do splash");
    return "OK: API diag request/response ativo";
}

std::string preloadAndInstall(const std::string& nativeLibraryDir) {
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
            return std::string("ERROR: preload libil2cpp API diag falhou: ") +
                    (err != nullptr ? err : "dlopen sem detalhe");
        }
        __android_log_print(ANDROID_LOG_INFO, kTag,
                            "Preloaded target IL2CPP for API diagnostics: %s", path.c_str());
    }

    return installDiagnosticsLocked();
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
    const std::string result = preloadAndInstall(jstringToString(env, nativeLibraryDir));
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_setMamoBallAuthDiagnostic(
        JNIEnv* env, jclass, jboolean enabled) {
    if (enabled != JNI_TRUE) {
        return env->NewStringUTF("OK: API diag permanece instalado ate reiniciar o processo");
    }
    std::lock_guard<std::mutex> lock(gMutex);
    const std::string result = installDiagnosticsLocked();
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_getMamoBallAuthDiagnosticStatus(
        JNIEnv* env, jclass) {
    const std::string status = getStatus();
    return env->NewStringUTF(status.c_str());
}
