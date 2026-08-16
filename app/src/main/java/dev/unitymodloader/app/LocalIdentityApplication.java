package dev.unitymodloader.app;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Loader-only identity bridge for the authorized Mamo Ball CTF target.
 *
 * Local package/resource/signing queries are pointed at the installed original
 * Mamo Ball APK. Binder-facing operation attribution deliberately stays on the
 * loader package/UID, because Android system services validate callingPackage
 * against Binder.getCallingUid().
 */
public final class LocalIdentityApplication extends Application {
    private static final String TAG = "UML.LocalIdentity";
    private static final String TARGET_PACKAGE = "com.alberun.mamoball";

    private Context targetContext;
    private Context hostBaseContext;
    private ApplicationInfo targetApplicationInfo;
    private String hostOpPackageName;
    private int hostUid;

    private Handler diagnosticHandler;
    private String lastDiagnosticStatus = "";
    private long lastDiagnosticChangeAt;
    private long lastPendingRepeatAt;
    private Toast diagnosticToast;

    @Override
    public void onCreate() {
        super.onCreate();
        hostBaseContext = getBaseContext();
        initializeTargetIdentity();
        startApiDiagnosticNotifier();
    }

    private void initializeTargetIdentity() {
        try {
            // Capture Binder-safe host identity before exposing target-local identity.
            hostOpPackageName = super.getOpPackageName();
            ApplicationInfo hostInfo = super.getApplicationInfo();
            hostUid = hostInfo.uid;

            targetContext = createPackageContext(
                    TARGET_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );

            ApplicationInfo targetInfo = targetContext.getApplicationInfo();
            targetApplicationInfo = new ApplicationInfo(targetInfo);

            // Source/resources remain the real target APK. Runtime-owned fields must
            // remain compatible with the loader process and its writable sandbox.
            targetApplicationInfo.uid = hostUid;
            targetApplicationInfo.dataDir = hostInfo.dataDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                targetApplicationInfo.deviceProtectedDataDir = hostInfo.deviceProtectedDataDir;
            }

            logSigningIdentity();
            Log.i(TAG, "Split identity ready: localPackage=" + TARGET_PACKAGE
                    + "; binderOpPackage=" + getOpPackageName()
                    + "; runtimeUid=" + hostUid
                    + "; processUid=" + android.os.Process.myUid());
        } catch (Throwable error) {
            targetContext = null;
            targetApplicationInfo = null;
            hostOpPackageName = null;
            hostUid = 0;
            Log.w(TAG, "Target identity unavailable; using loader identity", error);
        }
    }

    private void startApiDiagnosticNotifier() {
        diagnosticHandler = new Handler(Looper.getMainLooper());
        lastDiagnosticChangeAt = SystemClock.elapsedRealtime();
        diagnosticHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String status = NativeBridge.getMamoBallAuthDiagnosticStatus();
                    if (status == null) status = "";
                    long now = SystemClock.elapsedRealtime();
                    boolean changed = !status.equals(lastDiagnosticStatus);
                    if (changed) {
                        lastDiagnosticStatus = status;
                        lastDiagnosticChangeAt = now;
                        Log.i(TAG, "API diagnostic: " + status);
                        if (isImportantApiStatus(status)) {
                            showDiagnosticToast(status);
                            lastPendingRepeatAt = now;
                        }
                    } else if (status.contains("GUEST_REGISTER")
                            && status.contains("aguardando resposta")
                            && now - lastPendingRepeatAt >= 4500L) {
                        showDiagnosticToast(status + "\nSEM RESPOSTA ha mais de 4s");
                        lastPendingRepeatAt = now;
                    } else if (status.contains("GET_CONFIG")
                            && now - lastDiagnosticChangeAt >= 5500L
                            && now - lastPendingRepeatAt >= 5500L) {
                        showDiagnosticToast(status + "\nNenhuma etapa de login apareceu depois disso");
                        lastPendingRepeatAt = now;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "API diagnostic notifier failed", error);
                } finally {
                    diagnosticHandler.postDelayed(this, 650L);
                }
            }
        });
    }

    private static boolean isImportantApiStatus(String status) {
        return status.contains("GET_CONFIG")
                || status.contains("GUEST_REGISTER")
                || status.contains("REFRESH_TOKEN")
                || status.contains("LOGIN_WITH_SOCIAL")
                || status.startsWith("ERROR:");
    }

    private void showDiagnosticToast(String status) {
        Context context = hostBaseContext != null ? hostBaseContext : this;
        if (diagnosticToast != null) diagnosticToast.cancel();
        diagnosticToast = Toast.makeText(context, "MAMO API DIAG\n" + status, Toast.LENGTH_LONG);
        diagnosticToast.show();
    }

    private void logSigningIdentity() {
        try {
            PackageManager pm = targetContext.getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo != null) {
                    Signature[] signatures = info.signingInfo.hasMultipleSigners()
                            ? info.signingInfo.getApkContentsSigners()
                            : info.signingInfo.getSigningCertificateHistory();
                    logSignatures(signatures);
                    return;
                }
            } else {
                @SuppressWarnings("deprecation")
                PackageInfo legacy = pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SIGNATURES);
                info = legacy;
            }

            @SuppressWarnings("deprecation")
            Signature[] signatures = info.signatures;
            logSignatures(signatures);
        } catch (Throwable error) {
            Log.w(TAG, "Could not read target signing identity", error);
        }
    }

    private static void logSignatures(Signature[] signatures) throws Exception {
        if (signatures == null || signatures.length == 0) {
            Log.w(TAG, "Target has no visible signing certificates");
            return;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < signatures.length; i++) {
            byte[] hash = digest.digest(signatures[i].toByteArray());
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            Log.i(TAG, "Target cert SHA-256[" + i + "]=" + hex);
        }
    }

    private boolean hasTarget() {
        return targetContext != null;
    }

    @Override
    public String getPackageName() {
        return hasTarget() ? TARGET_PACKAGE : super.getPackageName();
    }

    @Override
    public String getOpPackageName() {
        // ActivityThread.currentOpPackageName() feeds several Binder system APIs
        // (including StorageManager). It MUST belong to Process.myUid().
        return hostOpPackageName != null ? hostOpPackageName : super.getOpPackageName();
    }

    @Override
    public PackageManager getPackageManager() {
        return hasTarget() ? targetContext.getPackageManager() : super.getPackageManager();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return targetApplicationInfo != null
                ? new ApplicationInfo(targetApplicationInfo)
                : super.getApplicationInfo();
    }

    @Override
    public String getPackageCodePath() {
        return hasTarget() ? targetContext.getPackageCodePath() : super.getPackageCodePath();
    }

    @Override
    public String getPackageResourcePath() {
        return hasTarget() ? targetContext.getPackageResourcePath() : super.getPackageResourcePath();
    }
}
