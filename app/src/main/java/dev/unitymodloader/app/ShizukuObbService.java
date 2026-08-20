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
    private static final File SOURCE_DIR = new File(
            "/storage/emulated/0/Android/obb/com.alberun.mamoball");

    public ShizukuObbService() {
        Log.i(TAG, "created uid=" + Os.getuid());
    }

    public ShizukuObbService(Context context) {
        Log.i(TAG, "created with context uid=" + Os.getuid() + "; context=" + context);
    }

    @Override
    public String listMamoObb() {
        try {
            File[] files = SOURCE_DIR.listFiles((dir, name) ->
                    name != null && name.endsWith(".obb"));
            if (files == null) {
                return "ERROR\tNao foi possivel listar " + SOURCE_DIR.getAbsolutePath()
                        + " com uid=" + Os.getuid();
            }
            if (files.length == 0) {
                return "EMPTY\tNenhum .obb encontrado";
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

        File source = new File(SOURCE_DIR, fileName);
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
            return "OK\t" + copied + "\tuid=" + Os.getuid();
        } catch (Throwable error) {
            return "ERROR\t" + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
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
