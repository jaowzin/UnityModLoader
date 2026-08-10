package dev.unitymodloader.app;

import java.io.File;

public final class MonoBackend implements ModBackend {
    @Override
    public String id() { return "mono"; }

    @Override
    public String displayName() { return "Unity Mono"; }

    @Override
    public boolean supports(DetectionResult detection) {
        return detection.getBackend() == DetectionResult.Backend.UNITY_MONO;
    }

    @Override
    public BackendStatus prepare(InstalledUnityGame game, File gameRoot) {
        if (!supports(game.getDetection())) {
            return BackendStatus.blocked("O jogo selecionado não foi detectado como Unity Mono.");
        }
        return BackendStatus.ready("Perfil Mono preparado. Runtime de plugins será conectado a este backend.");
    }
}
