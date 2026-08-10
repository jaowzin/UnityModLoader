// Minimal native plugin for UnityModLoader V0.4.
// Build this as an Android arm64-v8a shared library (.so), import it into the
// selected game's plugin folder, then use "Abrir com Loader IL2CPP".

#include <android/log.h>

extern "C" __attribute__((visibility("default")))
const char* uml_plugin_init(const char* packageName) {
    __android_log_print(
            ANDROID_LOG_INFO,
            "UML.SamplePlugin",
            "Hello from native plugin. Target package: %s",
            packageName != nullptr ? packageName : "<null>"
    );
    return "sample plugin inicializado";
}
