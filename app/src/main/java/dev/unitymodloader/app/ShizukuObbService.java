package dev.unitymodloader.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Runs under Shizuku shell/root identity and only exposes Mamo Ball OBB reads. */
public final class ShizukuObbService extends IObbBridgeService.Stub {
    private static final String TAG = "UML.ShizukuOBB";
    private static final String PACKAGE = "com.alberun.mamoball";

    public ShizukuObbService() {
        Log.i(TAG, "created uid=" + Os.getuid());
    }

    public ShizukuObbService(Context context) {
        Log.i(TAG, "created with context uid=" + Os.getuid() + "; context=" + context);
    }

    @Override
    public String listMamoObb() {
        try {
            File sourceDir = findReadableSourceDir();
            if (sourceDir == null) {
                return "ERROR\tNao foi possivel listar o OBB do Mamo com uid=" + Os.getuid();
            }

            File[] files = sourceDir.listFiles((dir, name) ->
                    name != null && name.endsWith(".obb"));
            if (files == null) {
                return "ERROR\tNao foi possivel listar " + sourceDir.getAbsolutePath()
                        + " com uid=" + Os.getuid();
            }
            if (files.length == 0) {
                return "EMPTY\tNenhum .obb encontrado em " + sourceDir.getAbsolutePath();
            }

            StringBuilder out = new StringBuilder();
            for (File file : files) {
                if (!file.isFile()) continue;
                if (out.length() > 0) out.append('\n');
                out.append(file.getName()).append('\t').append(file.length());
            }
            return out.length() == 0 ? "EMPTY\tNenhum .obb encontrado" : out.toString();
        } catch (Throwable error) {
            return "ERROR\t" + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    @Override
    public String copyMamoObb(String fileName, ParcelFileDescriptor destination) {
        if (destination == null) return "ERROR\tDestino nulo";
        if (!isSafeObbName(fileName)) return "ERROR\tNome de OBB invalido";

        File sourceDir = findReadableSourceDir();
        if (sourceDir == null) return "ERROR\tDiretorio OBB nao acessivel";
        File source = new File(sourceDir, fileName);
        if (!source.isFile()) return "ERROR\tOBB nao encontrado: " + fileName;

        long copied = 0L;
        try (FileInputStream input = new FileInputStream(source);
             ParcelFileDescriptor pfd = destination;
             FileOutputStream output = new FileOutputStream(pfd.getFileDescriptor())) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
            }
            output.flush();
            return "OK\t" + copied + "\tuid=" + Os.getuid()
                    + "\tsource=" + sourceDir.getAbsolutePath();
        } catch (Throwable error) {
            return "ERROR\t" + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static File findReadableSourceDir() {
        File[] candidates = Os.getuid() == 0
                ? new File[]{
                new File("/storage/emulated/0/Android/obb/" + PACKAGE),
                new File("/sdcard/Android/obb/" + PACKAGE),
                new File("/data/media/0/Android/obb/" + PACKAGE)
        }
                : new File[]{
                new File("/storage/emulated/0/Android/obb/" + PACKAGE),
                new File("/sdcard/Android/obb/" + PACKAGE)
        };

        for (File candidate : candidates) {
            try {
                File[] probe = candidate.listFiles();
                if (probe != null) {
                    Log.i(TAG, "Readable source=" + candidate.getAbsolutePath()
                            + "; uid=" + Os.getuid());
                    return candidate;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isSafeObbName(String value) {
        return value != null
                && value.endsWith(".obb")
                && !value.contains("/")
                && !value.contains("\\")
                && !value.contains("..")
                && !value.isEmpty();
    }
}
