package dev.unitymodloader.app;

import java.io.File;

public interface ModBackend {
    String id();
    String displayName();
    boolean supports(DetectionResult detection);
    BackendStatus prepare(InstalledUnityGame game, File gameRoot);
}
