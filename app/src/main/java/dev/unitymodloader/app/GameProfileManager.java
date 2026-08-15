package dev.unitymodloader.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class GameProfileManager {
    private static final String TAG = "UML.Profile";
    private static final String MAMO_BALL_PACKAGE = "com.alberun.mamoball";

    private GameProfileManager() {}

    public static File root(Context context, InstalledUnityGame game) {
        return new File(context.getExternalFilesDir(null), "games/" + game.getPackageName());
    }

    public static File plugins(Context context, InstalledUnityGame game) {
        return new File(root(context, game), "plugins");
    }

    public static File config(Context context, InstalledUnityGame game) {
        return new File(root(context, game), "config");
    }

    public static File logs(Context context, InstalledUnityGame game) {
        return new File(root(context, game), "logs");
    }

    public static boolean prepare(Context context, InstalledUnityGame game, ModBackend backend) {
        File root = root(context, game);
        File plugins = plugins(context, game);
        File config = config(context, game);
        File logs = logs(context, game);

        boolean ok = ensure(root) && ensure(plugins) && ensure(config) && ensure(logs);
        if (!ok) return false;

        String bootstrapStatus = "not-applicable";
        if (MAMO_BALL_PACKAGE.equals(game.getPackageName())) {
            bootstrapStatus = prepareMamoBallBootstrap(context);
            Log.i(TAG, "Mamo Ball early bootstrap: " + bootstrapStatus);
        }

        File profile = new File(root, "profile.properties");
        try (FileWriter writer = new FileWriter(profile, false)) {
            writer.write("package=" + game.getPackageName() + "\n");
            writer.write("label=" + game.getLabel().replace("\n", " ") + "\n");
            writer.write("backend=" + (backend == null ? "unknown" : backend.id()) + "\n");
            writer.write("apkCount=" + game.getApkPaths().size() + "\n");
            writer.write("architectures=" + String.join(",", game.getDetection().getArchitectures()) + "\n");
            writer.write("bootstrap=" + bootstrapStatus.replace("\n", " ") + "\n");
        } catch (IOException e) {
            return false;
        }
        return !bootstrapStatus.startsWith("ERROR:");
    }

    private static String prepareMamoBallBootstrap(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            @SuppressWarnings("deprecation")
            ApplicationInfo info = pm.getApplicationInfo(MAMO_BALL_PACKAGE, 0);
            if (info.nativeLibraryDir == null || info.nativeLibraryDir.isEmpty()) {
                return "ERROR: nativeLibraryDir do Mamo Ball vazio";
            }
            return NativeBridge.prepareMamoBallBootstrap(info.nativeLibraryDir);
        } catch (Throwable error) {
            return "ERROR: early bootstrap: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage());
        }
    }

    private static boolean ensure(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }
}
