package dev.unitymodloader.app;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * OBB-only bridge for Mamo Ball.
 *
 * This intentionally does not spoof package/signing identity. It only makes
 * Application-level expansion-file lookups resolve through the installed
 * com.alberun.mamoball context, while the loader keeps its own UID/package for
 * Binder/system-service attribution.
 */
public final class MamoObbApplication extends Application {
    private static final String TAG = "UML.MamoOBB";
    private static final String TARGET_PACKAGE = "com.alberun.mamoball";

    private Context targetContext;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            targetContext = createPackageContext(TARGET_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            File dir = targetContext.getObbDir();
            Log.i(TAG, "Target OBB dir=" + (dir == null ? "null" : dir.getAbsolutePath()));
            if (dir != null) {
                File[] files = dir.listFiles((parent, name) -> name != null && name.endsWith(".obb"));
                if (files == null) {
                    Log.w(TAG, "OBB directory exists as a path but cannot be listed by loader UID");
                } else {
                    Log.i(TAG, "Visible OBB files=" + files.length);
                    for (File file : files) {
                        Log.i(TAG, "OBB " + file.getName() + " size=" + file.length()
                                + " readable=" + file.canRead());
                    }
                }
            }
        } catch (Throwable error) {
            targetContext = null;
            Log.w(TAG, "Could not create Mamo Ball OBB context", error);
        }
    }

    @Override
    public File getObbDir() {
        Context target = targetContext;
        return target != null ? target.getObbDir() : super.getObbDir();
    }

    @Override
    public File[] getObbDirs() {
        Context target = targetContext;
        return target != null ? target.getObbDirs() : super.getObbDirs();
    }
}
