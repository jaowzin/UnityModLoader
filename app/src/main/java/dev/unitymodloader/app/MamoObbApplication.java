package dev.unitymodloader.app;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import java.io.File;

/**
 * Hosted Application bridge for Mamo Ball.
 *
 * Writable storage and Binder attribution stay on the real loader package/UID,
 * while package/class/resource lookups used by in-process SDKs are resolved from
 * the installed Mamo Ball APK. This lets Firebase component discovery see the
 * target manifest without spoofing Binder identity.
 */
public final class MamoObbApplication extends Application {
    private static final String TAG = "UML.MamoApp";
    private static final String TARGET_PACKAGE = "com.alberun.mamoball";
    private static final String FIREBASE_DISCOVERY =
            "com.google.firebase.components.ComponentDiscoveryService";
    private static final String FIREBASE_MESSAGING_REGISTRAR =
            "com.google.firebase.components:com.google.firebase.messaging.FirebaseMessagingRegistrar";

    private Context targetContext;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashDiagnostics.install(this);

        try {
            targetContext = super.createPackageContext(
                    TARGET_PACKAGE,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            Log.i(TAG, "Target Application bridge ready: " + targetContext.getPackageName());
            logFirebaseDiscovery();
        } catch (Throwable error) {
            targetContext = null;
            Log.e(TAG, "Could not create target Application context", error);
        }

        File dir = super.getObbDir();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        Log.i(TAG, "Loader OBB mirror=" + (dir == null ? "null" : dir.getAbsolutePath()));
    }

    @SuppressWarnings("deprecation")
    private void logFirebaseDiscovery() {
        Context target = targetContext;
        if (target == null) return;
        try {
            ComponentName component = new ComponentName(TARGET_PACKAGE, FIREBASE_DISCOVERY);
            Bundle meta = target.getPackageManager()
                    .getServiceInfo(component, PackageManager.GET_META_DATA).metaData;
            boolean messaging = meta != null && meta.containsKey(FIREBASE_MESSAGING_REGISTRAR);
            Log.i(TAG, "Firebase ComponentDiscovery metadata: messaging=" + messaging);
        } catch (Throwable error) {
            Log.w(TAG, "Could not inspect Firebase ComponentDiscovery metadata", error);
        }
    }

    @Override
    public String getPackageName() {
        Context target = targetContext;
        return target != null ? target.getPackageName() : super.getPackageName();
    }

    @Override
    public String getOpPackageName() {
        // Never attribute Binder calls to the foreign package: real UID is the loader.
        return super.getOpPackageName();
    }

    @Override
    public PackageManager getPackageManager() {
        Context target = targetContext;
        return target != null ? target.getPackageManager() : super.getPackageManager();
    }

    @Override
    public ClassLoader getClassLoader() {
        Context target = targetContext;
        return target != null ? target.getClassLoader() : super.getClassLoader();
    }

    @Override
    public Resources getResources() {
        Context target = targetContext;
        return target != null ? target.getResources() : super.getResources();
    }

    @Override
    public AssetManager getAssets() {
        Context target = targetContext;
        return target != null ? target.getAssets() : super.getAssets();
    }

    @Override
    public File getObbDir() {
        // The imported mirror must remain writable/readable by the loader UID.
        File dir = super.getObbDir();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    @Override
    public File[] getObbDirs() {
        File primary = getObbDir();
        return primary == null ? new File[0] : new File[]{primary};
    }
}
