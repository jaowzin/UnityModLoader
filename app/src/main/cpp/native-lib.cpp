#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

namespace {
constexpr const char* kTag = "UML.Native";
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
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_coreVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("umlcore/0.4.0");
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
            g_pluginHandles.push_back(handle); // Keep loaded for the lifetime of the process.
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
