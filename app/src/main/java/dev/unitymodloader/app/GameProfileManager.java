package dev.unitymodloader.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public final class GameProfileManager {
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

        File profile = new File(root, "profile.properties");
        try (FileWriter writer = new FileWriter(profile, false)) {
            writer.write("package=" + game.getPackageName() + "\n");
            writer.write("label=" + game.getLabel().replace("\n", " ") + "\n");
            writer.write("backend=" + (backend == null ? "unknown" : backend.id()) + "\n");
            writer.write("apkCount=" + game.getApkPaths().size() + "\n");
            writer.write("architectures=" + String.join(",", game.getDetection().getArchitectures()) + "\n");
            writer.write("identityMode=loader-only-target-context\n");
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private static boolean ensure(File dir) {
        return dir.isDirectory() || dir.mkdirs();
    }
}
