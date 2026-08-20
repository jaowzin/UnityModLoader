package dev.unitymodloader.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Diagnostics for crashes of the hosted Mamo process. */
final class CrashDiagnostics {
    private static final String TAG = "UML.CrashDiag";
    private static final String PREFS = "uml_crash_diagnostics";
    private static final String KEY_LAST_EXIT_TS = "last_exit_timestamp";
    private static final String KEY_LAST_CRASH_TS = "last_crash_timestamp";
    private static final String KEY_LAST_CRASH_PID = "last_crash_pid";
    private static final String JAVA_CRASH_FILE = "last-java-crash.txt";
    private static final String REPORT_FILE = "last-crash-report.txt";
    private static final int MAX_TRACE_BYTES = 96 * 1024;

    private CrashDiagnostics() {}

    static void install(Context context) {
        Context app = context.getApplicationContext();
        installJavaHandler(app);
        publishPreviousCrash(app);
    }

    private static void installJavaHandler(Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                File file = new File(context.getFilesDir(), JAVA_CRASH_FILE);
                try (FileOutputStream out = new FileOutputStream(file, false);
                     PrintWriter writer = new PrintWriter(out)) {
                    writer.println("UML JAVA CRASH");
                    writer.println("time=" + System.currentTimeMillis());
                    writer.println("thread=" + (thread == null ? "null" : thread.getName()));
                    writer.println("process=" + context.getPackageName());
                    if (throwable != null) throwable.printStackTrace(writer);
                    writer.flush();
                }
            } catch (Throwable error) {
                Log.e(TAG, "Could not persist Java crash", error);
            }

            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static void publishPreviousCrash(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            if (am == null) return;

            List<ApplicationExitInfo> history = am.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 8);
            if (history == null || history.isEmpty()) return;

            ApplicationExitInfo chosen = null;
            for (ApplicationExitInfo info : history) {
                if (info != null && isCrashLike(info.getReason())) {
                    chosen = info;
                    break;
                }
            }
            if (chosen == null) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long handled = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            if (chosen.getTimestamp() <= handled) return;

            String report = buildReport(context, chosen);
            prefs.edit()
                    .putLong(KEY_LAST_EXIT_TS, chosen.getTimestamp())
                    .putLong(KEY_LAST_CRASH_TS, chosen.getTimestamp())
                    .putInt(KEY_LAST_CRASH_PID, chosen.getPid())
                    .apply();
            writeText(new File(context.getFilesDir(), REPORT_FILE), report);
            Log.e(TAG, report);
            publishClipboard(context, report,
                    "Relatorio do ultimo crash copiado. Cole no ChatGPT.", 1200L);
        } catch (Throwable error) {
            Log.e(TAG, "Could not inspect historical exits", error);
        }
    }

    static int getLastCrashPid(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LAST_CRASH_PID, -1);
    }

    static long getLastCrashTimestamp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_CRASH_TS, 0L);
    }

    /** Merges the Shizuku shell crash-buffer block into the report already generated. */
    static void appendPrivilegedCrashLog(Context context, String serviceResult) {
        Context app = context.getApplicationContext();
        if (serviceResult == null || !serviceResult.startsWith("OK\t")) return;

        try {
            File reportFile = new File(app.getFilesDir(), REPORT_FILE);
            String report = readFile(reportFile, 256 * 1024);
            if (report.isEmpty() || report.contains("--- SHIZUKU CRASH LOGCAT ---")) return;

            int newline = serviceResult.indexOf('\n');
            String logcat = newline >= 0 ? serviceResult.substring(newline + 1).trim() : "";
            if (logcat.isEmpty()) return;

            String marker = "\n--- SHIZUKU CRASH LOGCAT ---\n" + logcat + "\n";
            int endMarker = report.lastIndexOf("=== END REPORT ===");
            String enhanced = endMarker >= 0
                    ? report.substring(0, endMarker) + marker + report.substring(endMarker)
                    : report + marker;

            writeText(reportFile, enhanced);
            Log.e(TAG, enhanced);
            publishClipboard(app, enhanced,
                    "Crash nativo + logcat Shizuku copiados. Cole no ChatGPT.", 250L);
        } catch (Throwable error) {
            Log.e(TAG, "Could not append Shizuku crash log", error);
        }
    }

    private static String buildReport(Context context, ApplicationExitInfo info) {
        StringBuilder out = new StringBuilder(4096);
        out.append("=== MAMO HOST CRASH REPORT ===\n");
        out.append("loaderPackage=").append(context.getPackageName()).append('\n');
        out.append("timestamp=").append(info.getTimestamp()).append(" (")
                .append(formatTime(info.getTimestamp())).append(")\n");
        out.append("reason=").append(reasonName(info.getReason()))
                .append(" (").append(info.getReason()).append(")\n");
        out.append("status=").append(info.getStatus()).append('\n');
        out.append("importance=").append(info.getImportance()).append('\n');
        out.append("pssKb=").append(info.getPss()).append('\n');
        out.append("rssKb=").append(info.getRss()).append('\n');
        out.append("description=").append(String.valueOf(info.getDescription())).append('\n');
        out.append("processName=").append(String.valueOf(info.getProcessName())).append('\n');
        out.append("pid=").append(info.getPid()).append('\n');

        String javaCrash = readFile(new File(context.getFilesDir(), JAVA_CRASH_FILE), 48 * 1024);
        if (!javaCrash.isEmpty()) {
            out.append("\n--- JAVA UNCAUGHT ---\n").append(javaCrash).append('\n');
        }

        String trace = readExitTrace(info);
        if (!trace.isEmpty()) {
            out.append("\n--- SYSTEM EXIT TRACE ---\n").append(trace).append('\n');
        }
        out.append("=== END REPORT ===");
        return out.toString();
    }

    private static boolean isCrashLike(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_SIGNALED;
    }

    private static String readExitTrace(ApplicationExitInfo info) {
        try (InputStream input = info.getTraceInputStream()) {
            if (input == null) return "";
            byte[] bytes = readStreamBytes(input, MAX_TRACE_BYTES);
            if (bytes.length == 0) return "";
            if (!looksLikeText(bytes)) {
                return "<trace do sistema veio em formato binario/protobuf; usando logcat Shizuku>";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            return "<trace read failed: " + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()) + ">";
        }
    }

    private static boolean looksLikeText(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        int bad = 0;
        for (int i = 0; i < sample; i++) {
            int value = bytes[i] & 0xff;
            if (value == 0) return false;
            if (value < 0x09 || (value > 0x0d && value < 0x20)) bad++;
        }
        return bad < Math.max(3, sample / 50);
    }

    private static String readFile(File file, int maxBytes) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            return new String(readStreamBytes(input, maxBytes), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static byte[] readStreamBytes(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toByteArray();
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static void publishClipboard(Context context, String report, String toast, long delayMs) {
        final String value = report;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Mamo Ball crash report", value));
                }
                Toast.makeText(context, toast, Toast.LENGTH_LONG).show();
            } catch (Throwable error) {
                Log.e(TAG, "Could not publish crash report", error);
            }
        }, delayMs);
    }

    private static String formatTime(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT)
                .format(new Date(timestamp));
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "JAVA_CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "NATIVE_CRASH";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "PACKAGE_UPDATED";
            default: return "UNKNOWN";
        }
    }
}
