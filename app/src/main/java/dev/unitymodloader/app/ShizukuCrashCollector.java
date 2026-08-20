package dev.unitymodloader.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/** Silently enriches the previous hosted native crash using the Shizuku crash buffer. */
final class ShizukuCrashCollector {
    private static final String TAG = "UML.ShizukuCrash";

    private ShizukuCrashCollector() {}

    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> collect(app), 1800L);
    }

    private static void collect(Context context) {
        try {
            int pid = CrashDiagnostics.getLastCrashPid(context);
            long timestamp = CrashDiagnostics.getLastCrashTimestamp(context);
            if (pid <= 0 || timestamp <= 0L) return;
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Shizuku permission not granted; skipping crash-buffer capture");
                return;
            }

            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(context, ShizukuObbService.class)
            ).daemon(false)
                    .processNameSuffix("mamo_diag")
                    .tag("mamoball-crash-diag")
                    .version(2);

            ServiceConnection connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    IObbBridgeService service = IObbBridgeService.Stub.asInterface(binder);
                    new Thread(() -> {
                        try {
                            String result = service.readLoaderCrashLog(pid, timestamp);
                            if (result != null && result.startsWith("OK\t")) {
                                CrashDiagnostics.appendPrivilegedCrashLog(context, result);
                            } else {
                                Log.w(TAG, "Crash log unavailable: " + result);
                            }
                        } catch (Throwable error) {
                            Log.w(TAG, "Could not read Shizuku crash buffer", error);
                        }
                    }, "mamoball-shizuku-crash").start();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.i(TAG, "Crash diagnostic UserService disconnected: " + name);
                }
            };

            Shizuku.bindUserService(args, connection);
        } catch (Throwable error) {
            Log.w(TAG, "Could not start Shizuku crash collector", error);
        }
    }
}
