package dev.unitymodloader.app;

import android.app.Application;
import android.util.Log;

import java.io.File;

/**
 * Keeps the loader's normal identity and exposes its own writable OBB directory
 * as the mirror consumed by hosted Mamo Ball.
 */
public final class MamoObbApplication extends Application {
    private static final String TAG = "UML.MamoOBB";

    @Override
    public void onCreate() {
        super.onCreate();
        File dir = getObbDir();
        Log.i(TAG, "Loader OBB mirror=" + (dir == null ? "null" : dir.getAbsolutePath()));
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
