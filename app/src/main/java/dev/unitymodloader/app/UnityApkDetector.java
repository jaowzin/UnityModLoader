package dev.unitymodloader.app;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class UnityApkDetector {
    private UnityApkDetector() {}

    public static DetectionResult inspect(File apk) throws IOException {
        boolean libUnity = false;
        boolean libIl2Cpp = false;
        boolean globalMetadata = false;
        int managedDlls = 0;
        Set<String> abis = new LinkedHashSet<>();

        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.matches("lib/[^/]+/libunity\\.so")) {
                    libUnity = true;
                    addAbi(name, abis);
                }

                if (lower.matches("lib/[^/]+/libil2cpp\\.so")) {
                    libIl2Cpp = true;
                    addAbi(name, abis);
                }

                if (lower.endsWith("/metadata/global-metadata.dat")) {
                    globalMetadata = true;
                }

                if (lower.contains("/managed/") && lower.endsWith(".dll")) {
                    managedDlls++;
                }
            }
        }

        DetectionResult.Backend backend;
        if (libUnity && libIl2Cpp) {
            backend = DetectionResult.Backend.UNITY_IL2CPP;
        } else if (libUnity && managedDlls > 0) {
            backend = DetectionResult.Backend.UNITY_MONO;
        } else if (libUnity) {
            backend = DetectionResult.Backend.UNITY_UNKNOWN;
        } else {
            backend = DetectionResult.Backend.NOT_UNITY;
        }

        return new DetectionResult(
                backend,
                libUnity,
                libIl2Cpp,
                globalMetadata,
                managedDlls,
                new ArrayList<>(abis)
        );
    }

    private static void addAbi(String entryName, Set<String> abis) {
        String[] parts = entryName.split("/");
        if (parts.length >= 3 && "lib".equals(parts[0])) {
            abis.add(parts[1]);
        }
    }
}
