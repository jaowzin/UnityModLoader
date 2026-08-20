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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/** Exposes Mamo code/resources while keeping loader-owned writable runtime state. */
final class GameContextBridge extends ContextWrapper {
    private static final String TAG = "UML.GameContext";

    private final Context gameContext;
    private final Context hostContext;
    private final ApplicationInfo bridgedApplicationInfo;
    private volatile boolean postBootstrapIdentity;

    GameContextBridge(Context gameContext, Context hostContext) {
        super(gameContext);
        this.gameContext = gameContext;
        Context appContext = hostContext.getApplicationContext();
        this.hostContext = appContext != null ? appContext : hostContext;

        ApplicationInfo gameInfo = gameContext.getApplicationInfo();
        ApplicationInfo hostInfo = this.hostContext.getApplicationInfo();
        bridgedApplicationInfo = new ApplicationInfo(gameInfo);

        // Unity bootstrap expects the target package/application metadata, but any
        // writable path and the Linux uid must remain owned by the loader process.
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
        return useHostIdentity() ? hostContext.getPackageManager() : gameContext.getPackageManager();
    }

    /**
     * Unity needs the target package during its native bootstrap. Once libil2cpp is
     * mapped, game-managed code is running and Google Play/Firebase may create
     * Binder clients. At that point the package name must match the loader UID.
     */
    @Override public String getPackageName() {
        return useHostIdentity() ? hostContext.getPackageName() : gameContext.getPackageName();
    }

    // Binder-facing attribution must always match the loader's real UID/package.
    @Override public String getOpPackageName() { return hostContext.getOpPackageName(); }

    @Override public String getPackageCodePath() { return gameContext.getPackageCodePath(); }
    @Override public String getPackageResourcePath() { return gameContext.getPackageResourcePath(); }

    @Override public ApplicationInfo getApplicationInfo() {
        ApplicationInfo info = new ApplicationInfo(bridgedApplicationInfo);
        if (useHostIdentity()) {
            info.packageName = hostContext.getApplicationInfo().packageName;
        }
        return info;
    }

    /** Google/Firebase application-context calls always use the real loader identity. */
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

    private boolean useHostIdentity() {
        if (postBootstrapIdentity) return true;

        // Any Google Play request initiated by IL2CPP can only happen after this
        // mapping exists. Cache the transition permanently once observed.
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("libil2cpp.so")) {
                    postBootstrapIdentity = true;
                    Log.i(TAG, "IL2CPP mapped: switching package identity to "
                            + hostContext.getPackageName());
                    return true;
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not inspect /proc/self/maps; keeping Unity bootstrap identity", error);
        }
        return false;
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "default";
        return value.replace('/', '_').replace('\\', '_');
    }
}
