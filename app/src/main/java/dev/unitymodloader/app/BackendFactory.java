package dev.unitymodloader.app;

public final class BackendFactory {
    private static final ModBackend IL2CPP = new Il2CppBackend();
    private static final ModBackend MONO = new MonoBackend();

    private BackendFactory() {}

    public static ModBackend forGame(InstalledUnityGame game) {
        if (IL2CPP.supports(game.getDetection())) return IL2CPP;
        if (MONO.supports(game.getDetection())) return MONO;
        return null;
    }
}
