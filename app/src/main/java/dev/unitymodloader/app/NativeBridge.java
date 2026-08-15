package dev.unitymodloader.app;

public final class NativeBridge {
    static {
        System.loadLibrary("umlcore");
    }

    private NativeBridge() {}

    public static native String coreVersion();

    /**
     * Applies/restores the Fire Zone CTF infinite-ammo patch in the currently
     * hosted libil2cpp.so. Returns OK/WAIT/ERROR plus a short diagnostic.
     */
    public static native String setFireZoneInfiniteAmmo(boolean enabled);

    /**
     * Loads every .so plugin in the supplied directory with RTLD_GLOBAL.
     * A native plugin may export:
     *
     *   extern "C" const char* uml_plugin_init(const char* packageName);
     */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
