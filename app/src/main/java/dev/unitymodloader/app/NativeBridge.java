package dev.unitymodloader.app;

public final class NativeBridge {
    static {
        System.loadLibrary("umlcore");
    }

    private NativeBridge() {}

    public static native String coreVersion();

    /**
     * Preloads the target libil2cpp.so by absolute nativeLibraryDir and arms the
     * Mamo Ball guest bootstrap before UnityPlayer starts.
     */
    public static native String prepareMamoBallBootstrap(String nativeLibraryDir);

    /** Routes the empty-token LoginFragment directly to silent guest login. */
    public static native String setMamoBallGuestBootstrap(boolean enabled);

    /** Applies/restores the verified Mamo Ball 4.6.15 super-kick patch. */
    public static native String setMamoBallSuperKick(boolean enabled);

    /** Applies/restores the verified Mamo Ball 4.6.15 2x movement-speed patch. */
    public static native String setMamoBallSuperSpeed(boolean enabled);

    /**
     * Legacy declaration kept only so the old Fire Zone overlay source remains
     * buildable while the project transitions targets. It is not used by Mamo Ball.
     */
    public static native float[] getFireZoneEspTargets();

    /** Loads every .so plugin in the supplied directory with RTLD_GLOBAL. */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
