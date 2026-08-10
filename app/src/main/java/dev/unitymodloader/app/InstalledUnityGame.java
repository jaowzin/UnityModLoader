package dev.unitymodloader.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InstalledUnityGame {
    private final String label;
    private final String packageName;
    private final DetectionResult detection;
    private final List<String> apkPaths;

    public InstalledUnityGame(String label, String packageName, DetectionResult detection, List<String> apkPaths) {
        this.label = label;
        this.packageName = packageName;
        this.detection = detection;
        this.apkPaths = Collections.unmodifiableList(new ArrayList<>(apkPaths));
    }

    public String getLabel() { return label; }
    public String getPackageName() { return packageName; }
    public DetectionResult getDetection() { return detection; }
    public List<String> getApkPaths() { return apkPaths; }
}
