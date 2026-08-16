package dev.unitymodloader.app;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Loader-only identity bridge for the authorized Mamo Ball CTF target.
 *
 * The hosted Unity activity already exposes the target package/resources. Some
 * Android/Google SDKs immediately switch to activity.getApplicationContext(),
 * which normally leaks the loader package again. This Application keeps that
 * local application-context identity aligned with the installed, untouched
 * Mamo Ball APK. It does not change Binder/Linux UID and does not modify the ROM.
 */
public final class LocalIdentityApplication extends Application {
    private static final String TAG = "UML.LocalIdentity";
    private static final String TARGET_PACKAGE = "com.alberun.mamoball";

    private Context targetContext;
    private ApplicationInfo targetApplicationInfo;

    @Override
    public void onCreate() {
        super.onCreate();
        initializeTargetIdentity();
    }

    private void initializeTargetIdentity() {
        try {
            targetContext = createPackageContext(
                    TARGET_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );

            ApplicationInfo targetInfo = targetContext.getApplicationInfo();
            ApplicationInfo hostInfo = super.getApplicationInfo();
            targetApplicationInfo = new ApplicationInfo(targetInfo);

            // Preserve the real target identity/source paths, but writable data must
            // remain inside the loader's sandbox.
            targetApplicationInfo.dataDir = hostInfo.dataDir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                targetApplicationInfo.deviceProtectedDataDir = hostInfo.deviceProtectedDataDir;
            }

            logSigningIdentity();
            Log.i(TAG, "Local identity ready: package=" + TARGET_PACKAGE
                    + "; opPackage=" + getOpPackageName()
                    + "; reportedUid=" + targetApplicationInfo.uid
                    + "; realProcessUid=" + android.os.Process.myUid());
        } catch (Throwable error) {
            targetContext = null;
            targetApplicationInfo = null;
            Log.w(TAG, "Target identity unavailable; using loader identity", error);
        }
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
        return hasTarget() ? TARGET_PACKAGE : super.getOpPackageName();
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
