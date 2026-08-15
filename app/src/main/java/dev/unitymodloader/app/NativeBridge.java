package dev.unitymodloader.app;

public final class NativeBridge {
    static {
        System.loadLibrary("umlcore");
    }

    private NativeBridge() {}

    public static native String coreVersion();

    /** Applies/restores the verified Fire Zone infinite-ammo patch. */
    public static native String setFireZoneInfiniteAmmo(boolean enabled);

    /**
     * Applies/restores the verified Fire Zone cash patch.
     * While enabled CurrentCash returns 999999 and writes are suppressed so the
     * user's original saved balance is preserved.
     */
    public static native String setFireZoneInfiniteCoins(boolean enabled);

    /** Loads every .so plugin in the supplied directory with RTLD_GLOBAL. */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
