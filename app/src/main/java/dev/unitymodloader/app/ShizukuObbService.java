package dev.unitymodloader.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Runs under Shizuku shell/root identity for Mamo OBB import and loader diagnostics. */
public final class ShizukuObbService extends IObbBridgeService.Stub {
    private static final String TAG = "UML.ShizukuOBB";
    private static final String PACKAGE = "com.alberun.mamoball";
    private static final String LOADER_PACKAGE = "dev.unitymodloader.app";
    private static final int MAX_LOGCAT_BYTES = 512 * 1024;
    private static final int MAX_RETURN_BYTES = 96 * 1024;

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

    /**
     * Reads Android's crash log buffer under the Shizuku shell/root identity and
     * returns only the block associated with this loader process. This avoids
     * exposing unrelated applications' logs through the bridge.
     */
    @Override
    public String readLoaderCrashLog(int targetPid, long crashTimestampMs) {
        try {
            String raw = runLogcat("crash", 900);
            if (raw.isEmpty()) {
                raw = runLogcat("all", 2200);
            }
            if (raw.isEmpty()) {
                return "ERROR\tlogcat nao retornou dados; uid=" + Os.getuid();
            }

            String block = isolateLoaderCrash(raw, targetPid);
            if (block.isEmpty()) {
                return "EMPTY\tNenhum bloco do loader encontrado no crash buffer"
                        + "\tpid=" + targetPid + "\tts=" + crashTimestampMs
                        + "\tuid=" + Os.getuid();
            }
            return "OK\tuid=" + Os.getuid() + "\n" + block;
        } catch (Throwable error) {
            return "ERROR\t" + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage());
        }
    }

    private static String runLogcat(String bufferName, int lines) throws Exception {
        Process process = new ProcessBuilder(
                "logcat", "-b", bufferName, "-v", "threadtime", "-d", "-t", String.valueOf(lines)
        ).redirectErrorStream(true).start();

        String text;
        try (InputStream input = process.getInputStream()) {
            text = readLimited(input, MAX_LOGCAT_BYTES);
        }
        try {
            process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return text == null ? "" : text;
    }

    private static String isolateLoaderCrash(String raw, int targetPid) {
        if (raw == null || raw.isEmpty()) return "";

        String pidNeedleA = "pid: " + targetPid;
        String pidNeedleB = " " + targetPid + " ";
        int anchor = raw.lastIndexOf(LOADER_PACKAGE);
        if (anchor < 0 && targetPid > 0) anchor = raw.lastIndexOf(pidNeedleA);
        if (anchor < 0 && targetPid > 0) anchor = raw.lastIndexOf(pidNeedleB);
        if (anchor < 0) return "";

        int header = raw.lastIndexOf("*** *** ***", anchor);
        int start = header >= 0 ? header : Math.max(0, anchor - 10000);

        // Include Fatal-signal / tombstoned lines immediately preceding the DEBUG block.
        int fatal = raw.lastIndexOf("Fatal signal", anchor);
        if (fatal >= 0 && fatal >= start - 12000) {
            int fatalLine = raw.lastIndexOf('\n', fatal);
            start = Math.max(0, fatalLine >= 0 ? fatalLine + 1 : fatal);
        }

        int nextHeader = raw.indexOf("*** *** ***", anchor + 12);
        int end = nextHeader > anchor ? nextHeader : Math.min(raw.length(), anchor + MAX_RETURN_BYTES);
        if (end <= start) return "";

        String block = raw.substring(start, end).trim();
        byte[] bytes = block.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_RETURN_BYTES) return block;

        int charStart = Math.max(0, block.length() - MAX_RETURN_BYTES);
        return "<logcat truncado para os ultimos 96 KiB>\n" + block.substring(charStart);
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 16384));
        byte[] buffer = new byte[8192];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toString(StandardCharsets.UTF_8.name());
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
