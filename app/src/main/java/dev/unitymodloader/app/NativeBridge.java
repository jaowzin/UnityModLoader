package dev.unitymodloader.app;

public final class NativeBridge {
    static {
        System.loadLibrary("umlcore");
    }

    private NativeBridge() {}

    public static native String coreVersion();

    /**
     * Diagnostic for Mamo Ball 4.6.15: preload target IL2CPP and hook ApiManager
     * request/response callbacks without changing the game's API results.
     */
    public static native String prepareMamoBallAuthDiagnostic(String nativeLibraryDir);
    public static native String setMamoBallAuthDiagnostic(boolean enabled);
    public static native String getMamoBallAuthDiagnosticStatus();

    /** Legacy guest bootstrap retained for comparison/diagnostics. */
    public static native String prepareMamoBallBootstrap(String nativeLibraryDir);
    public static native String setMamoBallGuestBootstrap(boolean enabled);

    /** Applies/restores the verified Mamo Ball 4.6.15 super-kick patch. */
    public static native String setMamoBallSuperKick(boolean enabled);

    /** Applies/restores the verified Mamo Ball 4.6.15 2x movement-speed patch. */
    public static native String setMamoBallSuperSpeed(boolean enabled);

    /** Legacy declaration; Fire Zone ESP is not used by Mamo Ball. */
    public static native float[] getFireZoneEspTargets();

    /** Loads every .so plugin in the supplied directory with RTLD_GLOBAL. */
    public static native String loadNativePlugins(String pluginDirectory, String packageName);
}
