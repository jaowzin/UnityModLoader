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

/**
 * Captures crashes of the hosted process and publishes the previous crash on the
 * next launcher start. This is diagnostics only; it does not alter Mamo Ball flow.
 */
final class CrashDiagnostics {
    private static final String TAG = "UML.CrashDiag";
    private static final String PREFS = "uml_crash_diagnostics";
    private static final String KEY_LAST_EXIT_TS = "last_exit_timestamp";
    private static final String JAVA_CRASH_FILE = "last-java-crash.txt";
    private static final int MAX_TRACE_BYTES = 96 * 1024;

    private CrashDiagnostics() {}

    static void install(Context context) {
        installJavaHandler(context.getApplicationContext());
        publishPreviousCrash(context.getApplicationContext());
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

            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
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
                if (info == null) continue;
                if (isCrashLike(info.getReason())) {
                    chosen = info;
                    break;
                }
            }
            if (chosen == null) return;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long handled = prefs.getLong(KEY_LAST_EXIT_TS, 0L);
            if (chosen.getTimestamp() <= handled) return;

            String report = buildReport(context, chosen);
            prefs.edit().putLong(KEY_LAST_EXIT_TS, chosen.getTimestamp()).apply();
            Log.e(TAG, report);

            final String reportForUi = report;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "Mamo Ball crash report", reportForUi));
                    }
                    Toast.makeText(context,
                            "Relatorio do ultimo crash copiado. Cole no ChatGPT.",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable error) {
                    Log.e(TAG, "Could not publish crash report", error);
                }
            }, 1200L);
        } catch (Throwable error) {
            Log.e(TAG, "Could not inspect historical exits", error);
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
            return readStream(input, MAX_TRACE_BYTES);
        } catch (Throwable error) {
            return "<trace read failed: " + error.getClass().getSimpleName() + ": "
                    + String.valueOf(error.getMessage()) + ">";
        }
    }

    private static String readFile(File file, int maxBytes) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            return readStream(input, maxBytes);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readStream(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[4096];
        int remaining = maxBytes;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            out.write(buffer, 0, read);
            remaining -= read;
        }
        return out.toString(StandardCharsets.UTF_8.name());
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
