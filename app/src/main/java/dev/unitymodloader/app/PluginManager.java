package dev.unitymodloader.app;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PluginManager {
    private PluginManager() {}

    public static List<File> list(Context context, InstalledUnityGame game) {
        File dir = GameProfileManager.plugins(context, game);
        File[] files = dir.listFiles(file -> file.isFile());
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return new ArrayList<>(Arrays.asList(files));
    }

    public static File importPlugin(Context context, InstalledUnityGame game, Uri source, String requestedName) throws Exception {
        String name = sanitizeName(requestedName);
        if (name.isEmpty()) name = "plugin.bin";

        File dir = GameProfileManager.plugins(context, game);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Não foi possível criar a pasta de plugins");
        }

        File out = uniqueFile(dir, name);
        try (InputStream in = context.getContentResolver().openInputStream(source);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("Não foi possível abrir o plugin");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
        return out;
    }

    private static File uniqueFile(File dir, String name) {
        File candidate = new File(dir, name);
        if (!candidate.exists()) return candidate;

        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + "-" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        throw new IllegalStateException("Muitos plugins com o mesmo nome");
    }

    private static String sanitizeName(String name) {
        if (name == null) return "";
        return name.replace('\\', '_').replace('/', '_').replace("..", "_").trim();
    }
}
