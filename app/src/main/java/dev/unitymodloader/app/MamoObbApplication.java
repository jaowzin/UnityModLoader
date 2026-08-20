package dev.unitymodloader.app;

import android.app.Application;
import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * OBB-only bridge for Mamo Ball.
 *
 * This intentionally does not spoof package/signing identity. It only redirects
 * Application-level expansion-file lookups to Android/obb/com.alberun.mamoball.
 */
public final class MamoObbApplication extends Application {
    private static final String TAG = "UML.MamoOBB";
    private static final String TARGET_PACKAGE = "com.alberun.mamoball";

    @SuppressWarnings("deprecation")
    private File resolveTargetObbDir() {
        return new File(Environment.getExternalStorageDirectory(),
                "Android/obb/" + TARGET_PACKAGE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            File dir = resolveTargetObbDir();
            Log.i(TAG, "Target OBB dir=" + dir.getAbsolutePath());
            File[] files = dir.listFiles((parent, name) -> name != null && name.endsWith(".obb"));
            if (files == null) {
                Log.w(TAG, "OBB path cannot be listed by loader UID: " + dir.getAbsolutePath());
            } else {
                Log.i(TAG, "Visible OBB files=" + files.length);
                for (File file : files) {
                    Log.i(TAG, "OBB " + file.getName() + " size=" + file.length()
                            + " readable=" + file.canRead());
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not inspect Mamo Ball OBB path", error);
        }
    }

    @Override
    public File getObbDir() {
        return resolveTargetObbDir();
    }

    @Override
    public File[] getObbDirs() {
        return new File[]{resolveTargetObbDir()};
    }
}
