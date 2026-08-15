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

    /**
     * Returns [screenW, screenH, x, feetY, headY, depth, hp, ...] for hostile AI.
     * Coordinates come from the authorized Fire Zone CTF build's own Unity camera.
     */
    public static native float[] getFireZoneEspTargets();

    /** Loads every .so plugin in the supplied directory with RTLD_GLOBAL. */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
