package dev.unitymodloader.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dedicated target scanner for the Fire Zone CTF build.
 * We intentionally do not enumerate every installed application anymore.
 */
public final class InstalledUnityScanner {
    public static final String TARGET_PACKAGE = "com.sfcgs.gun.terrorist.shooting.missions";

    private InstalledUnityScanner() {}

    public static List<InstalledUnityGame> scan(Context context) {
        PackageManager pm = context.getPackageManager();

        try {
            // Deprecated overload is deliberate: it works on the full minSdk range.
            @SuppressWarnings("deprecation")
            ApplicationInfo app = pm.getApplicationInfo(TARGET_PACKAGE, 0);

            List<File> apkFiles = new ArrayList<>();
            List<String> apkPaths = new ArrayList<>();

            if (app.sourceDir != null) {
                apkFiles.add(new File(app.sourceDir));
                apkPaths.add(app.sourceDir);
            }

            if (app.splitSourceDirs != null) {
                for (String split : app.splitSourceDirs) {
                    if (split == null) continue;
                    apkFiles.add(new File(split));
                    apkPaths.add(split);
                }
            }

            DetectionResult result = UnityApkDetector.inspect(apkFiles);
            if (!result.isUnity()) return Collections.emptyList();

            CharSequence labelSeq = pm.getApplicationLabel(app);
            String label = labelSeq == null ? "Fire Zone" : labelSeq.toString();
            return Collections.singletonList(
                    new InstalledUnityGame(label, TARGET_PACKAGE, result, apkPaths)
            );
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }
}
