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
 * The process/package identity exposed to Android and Google Play services always
 * stays on the real loader package. Target code/resources are still exposed to
 * the hosted Unity runtime through the installed Mamo Ball context.
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
            Log.i(TAG, "Target code/resource bridge ready: " + targetContext.getPackageName());
        } catch (Throwable error) {
            targetContext = null;
            Log.e(TAG, "Could not create target context", error);
        }

        logFirebaseDiscovery();

        File dir = super.getObbDir();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        Log.i(TAG, "Loader OBB mirror=" + (dir == null ? "null" : dir.getAbsolutePath()));
        Log.i(TAG, "Binder identity package=" + super.getPackageName()
                + "; opPackage=" + super.getOpPackageName());
    }

    @SuppressWarnings("deprecation")
    private void logFirebaseDiscovery() {
        try {
            ComponentName component = new ComponentName(super.getPackageName(), FIREBASE_DISCOVERY);
            Bundle meta = super.getPackageManager()
                    .getServiceInfo(component, PackageManager.GET_META_DATA).metaData;
            boolean messaging = meta != null && meta.containsKey(FIREBASE_MESSAGING_REGISTRAR);
            Log.i(TAG, "Loader Firebase ComponentDiscovery metadata: messaging=" + messaging);
        } catch (Throwable error) {
            Log.w(TAG, "Could not inspect loader Firebase ComponentDiscovery metadata", error);
        }
    }

    @Override
    public String getPackageName() {
        // Never report a foreign package name from this UID to Binder-backed SDKs.
        return super.getPackageName();
    }

    @Override
    public String getOpPackageName() {
        return super.getOpPackageName();
    }

    @Override
    public PackageManager getPackageManager() {
        // Firebase component metadata is mirrored into the loader manifest.
        return super.getPackageManager();
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
