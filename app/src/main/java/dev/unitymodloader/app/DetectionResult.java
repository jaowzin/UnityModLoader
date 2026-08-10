package dev.unitymodloader.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DetectionResult {
    public enum Backend {
        UNITY_IL2CPP,
        UNITY_MONO,
        UNITY_UNKNOWN,
        NOT_UNITY
    }

    private final Backend backend;
    private final boolean hasLibUnity;
    private final boolean hasLibIl2Cpp;
    private final boolean hasGlobalMetadata;
    private final int managedDllCount;
    private final List<String> architectures;

    public DetectionResult(Backend backend, boolean hasLibUnity, boolean hasLibIl2Cpp,
                           boolean hasGlobalMetadata, int managedDllCount,
                           List<String> architectures) {
        this.backend = backend;
        this.hasLibUnity = hasLibUnity;
        this.hasLibIl2Cpp = hasLibIl2Cpp;
        this.hasGlobalMetadata = hasGlobalMetadata;
        this.managedDllCount = managedDllCount;
        this.architectures = Collections.unmodifiableList(new ArrayList<>(architectures));
    }

    public Backend getBackend() { return backend; }
    public boolean hasLibUnity() { return hasLibUnity; }
    public boolean hasLibIl2Cpp() { return hasLibIl2Cpp; }
    public boolean hasGlobalMetadata() { return hasGlobalMetadata; }
    public int getManagedDllCount() { return managedDllCount; }
    public List<String> getArchitectures() { return architectures; }
    public boolean isUnity() { return backend != Backend.NOT_UNITY; }
}
