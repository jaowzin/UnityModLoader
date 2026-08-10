package dev.unitymodloader.app;

public final class NativeBridge {
    static {
        System.loadLibrary("umlcore");
    }

    private NativeBridge() {}

    public static native String coreVersion();

    /**
     * Loads every .so plugin in the supplied directory with RTLD_GLOBAL.
     * A native plugin may export:
     *
     *   extern "C" const char* uml_plugin_init(const char* packageName);
     *
     * The returned string is a human-readable load report for logs/UI.
     */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
