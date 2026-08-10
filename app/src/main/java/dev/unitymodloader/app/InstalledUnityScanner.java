package dev.unitymodloader.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class InstalledUnityScanner {
    private InstalledUnityScanner() {}

    public static List<InstalledUnityGame> scan(Context context) {
        PackageManager pm = context.getPackageManager();
        List<InstalledUnityGame> games = new ArrayList<>();

        for (ApplicationInfo app : pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))) {
            try {
                List<File> apkFiles = new ArrayList<>();
                List<String> apkPaths = new ArrayList<>();

                if (app.sourceDir != null) {
                    File base = new File(app.sourceDir);
                    apkFiles.add(base);
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
                if (!result.isUnity()) continue;

                CharSequence labelSeq = pm.getApplicationLabel(app);
                String label = labelSeq == null ? app.packageName : labelSeq.toString();
                games.add(new InstalledUnityGame(label, app.packageName, result, apkPaths));
            } catch (Throwable ignored) {
                // Um pacote quebrado/inacessível não deve interromper a varredura inteira.
            }
        }

        games.sort(Comparator.comparing(InstalledUnityGame::getLabel, String.CASE_INSENSITIVE_ORDER));
        return games;
    }
}
