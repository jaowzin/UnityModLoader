package dev.unitymodloader.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.io.File;

/** Exposes Mamo code/resources while keeping loader-owned writable runtime state. */
final class GameContextBridge extends ContextWrapper {
    private static final String TAG = "UML.GameContext";

    private static final String[] HOST_IDENTITY_CALLERS = {
            "com.google.android.gms.",
            "com.google.games.",
            "com.google.firebase.",
            "com.google.android.play.",
            "com.google.android.libraries.",
            "com.google.android.datatransport."
    };

    private final Context gameContext;
    private final Context hostContext;
    private final ApplicationInfo bridgedApplicationInfo;
    private volatile int googleIdentityHits;
    private volatile String lastGoogleCaller = "none";

    GameContextBridge(Context gameContext, Context hostContext) {
        super(gameContext);
        this.gameContext = gameContext;
        Context appContext = hostContext.getApplicationContext();
        this.hostContext = appContext != null ? appContext : hostContext;

        ApplicationInfo gameInfo = gameContext.getApplicationInfo();
        ApplicationInfo hostInfo = this.hostContext.getApplicationInfo();
        bridgedApplicationInfo = new ApplicationInfo(gameInfo);

        // Unity bootstrap expects target APK metadata, but writable storage and uid
        // must always remain owned by the real loader process.
        bridgedApplicationInfo.dataDir = hostInfo.dataDir;
        bridgedApplicationInfo.uid = hostInfo.uid;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            bridgedApplicationInfo.deviceProtectedDataDir = hostInfo.deviceProtectedDataDir;
        }
    }

    @Override public AssetManager getAssets() { return gameContext.getAssets(); }
    @Override public Resources getResources() { return gameContext.getResources(); }
    @Override public ClassLoader getClassLoader() { return gameContext.getClassLoader(); }

    @Override public PackageManager getPackageManager() {
        return useHostIdentityForCaller() ? hostContext.getPackageManager() : gameContext.getPackageManager();
    }

    /**
     * Unity and Mamo code see the target package. Google/Firebase/Play Games code
     * sees the loader package, whose Linux uid is the actual Binder caller.
     */
    @Override public String getPackageName() {
        return useHostIdentityForCaller() ? hostContext.getPackageName() : gameContext.getPackageName();
    }

    // Binder-facing attribution is always the loader package/uid.
    @Override public String getOpPackageName() { return hostContext.getOpPackageName(); }

    @Override public String getPackageCodePath() { return gameContext.getPackageCodePath(); }
    @Override public String getPackageResourcePath() { return gameContext.getPackageResourcePath(); }

    @Override public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo(bridgedApplicationInfo);
        if (useHostIdentityForCaller()) {
            ApplicationInfo hostInfo = hostContext.getApplicationInfo();
            info.packageName = hostInfo.packageName;
            info.uid = hostInfo.uid;
            info.dataDir = hostInfo.dataDir;
        }
        return info;
    }

    /** SDKs that intentionally switch to application context get real loader identity. */
    @Override public Context getApplicationContext() { return hostContext; }

    @Override public File getObbDir() { return hostContext.getObbDir(); }
    @Override public File[] getObbDirs() {
        File primary = getObbDir();
        return primary == null ? new File[0] : new File[]{primary};
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return hostContext.getSharedPreferences("game_" + safe(name), mode);
    }
    @Override public boolean deleteSharedPreferences(String name) {
        return hostContext.deleteSharedPreferences("game_" + safe(name));
    }
    @Override public File getFilesDir() { return hostContext.getFilesDir(); }
    @Override public File getCacheDir() { return hostContext.getCacheDir(); }
    @Override public File getCodeCacheDir() { return hostContext.getCodeCacheDir(); }
    @Override public File getNoBackupFilesDir() { return hostContext.getNoBackupFilesDir(); }
    @Override public File getDataDir() { return hostContext.getDataDir(); }
    @Override public File getExternalFilesDir(String type) { return hostContext.getExternalFilesDir(type); }
    @Override public File getExternalCacheDir() { return hostContext.getExternalCacheDir(); }
    @Override public File getDatabasePath(String name) { return hostContext.getDatabasePath("game_" + safe(name)); }
    @Override public File getDir(String name, int mode) { return hostContext.getDir("game_" + safe(name), mode); }

    int getGoogleIdentityHits() {
        return googleIdentityHits;
    }

    String getLastGoogleCaller() {
        return lastGoogleCaller;
    }

    private boolean useHostIdentityForCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            for (String prefix : HOST_IDENTITY_CALLERS) {
                if (className.startsWith(prefix)) {
                    googleIdentityHits++;
                    if (!className.equals(lastGoogleCaller)) {
                        lastGoogleCaller = className;
                        Log.i(TAG, "Host identity for Google caller: " + className);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "default";
        return value.replace('/', '_').replace('\\', '_');
    }
}
