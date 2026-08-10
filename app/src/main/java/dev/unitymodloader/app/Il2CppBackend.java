package dev.unitymodloader.app;

import java.io.File;

public final class Il2CppBackend implements ModBackend {
    @Override
    public String id() { return "il2cpp"; }

    @Override
    public String displayName() { return "Unity IL2CPP"; }

    @Override
    public boolean supports(DetectionResult detection) {
        return detection.getBackend() == DetectionResult.Backend.UNITY_IL2CPP;
    }

    @Override
    public BackendStatus prepare(InstalledUnityGame game, File gameRoot) {
        if (!supports(game.getDetection())) {
            return BackendStatus.blocked("O jogo selecionado não foi detectado como IL2CPP.");
        }
        if (!game.getDetection().hasLibIl2Cpp()) {
            return BackendStatus.blocked("libil2cpp.so não foi encontrada nos APKs instalados.");
        }
        return BackendStatus.ready(
                "Host IL2CPP experimental disponível. Plugins nativos .so são carregados no processo Unity; .dll C# ainda aguardam o runtime gerenciado."
        );
    }
}
