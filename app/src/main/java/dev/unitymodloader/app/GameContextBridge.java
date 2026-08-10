package dev.unitymodloader.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;

import java.io.File;

/**
 * Exposes the installed game's code/resources/native-library paths while keeping
 * writable app data inside UnityModLoader's own sandbox.
 */
final class GameContextBridge extends ContextWrapper {
    private final Context gameContext;
    private final Context hostContext;
    private final ApplicationInfo bridgedApplicationInfo;

    GameContextBridge(Context gameContext, Context hostContext) {
        super(gameContext);
        this.gameContext = gameContext;

        Context appContext = hostContext.getApplicationContext();
        this.hostContext = appContext != null ? appContext : hostContext;

        ApplicationInfo gameInfo = gameContext.getApplicationInfo();
        ApplicationInfo hostInfo = this.hostContext.getApplicationInfo();
        bridgedApplicationInfo = new ApplicationInfo(gameInfo);

        // The loader cannot write to another package's /data/user/... sandbox.
        // Keep the game's source/native paths but redirect writable data to us.
        bridgedApplicationInfo.dataDir = hostInfo.dataDir;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            bridgedApplicationInfo.deviceProtectedDataDir = hostInfo.deviceProtectedDataDir;
        }
    }

    @Override
    public AssetManager getAssets() {
        return gameContext.getAssets();
    }

    @Override
    public Resources getResources() {
        return gameContext.getResources();
    }

    @Override
    public ClassLoader getClassLoader() {
        return gameContext.getClassLoader();
    }

    @Override
    public String getPackageName() {
        return gameContext.getPackageName();
    }

    @Override
    public String getPackageCodePath() {
        return gameContext.getPackageCodePath();
    }

    @Override
    public String getPackageResourcePath() {
        return gameContext.getPackageResourcePath();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return bridgedApplicationInfo;
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return hostContext.getSharedPreferences("game_" + safe(name), mode);
    }

    @Override
    public boolean deleteSharedPreferences(String name) {
        return hostContext.deleteSharedPreferences("game_" + safe(name));
    }

    @Override
    public File getFilesDir() {
        return hostContext.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        return hostContext.getCacheDir();
    }

    @Override
    public File getCodeCacheDir() {
        return hostContext.getCodeCacheDir();
    }

    @Override
    public File getNoBackupFilesDir() {
        return hostContext.getNoBackupFilesDir();
    }

    @Override
    public File getDataDir() {
        return hostContext.getDataDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
        return hostContext.getExternalFilesDir(type);
    }

    @Override
    public File getExternalCacheDir() {
        return hostContext.getExternalCacheDir();
    }

    @Override
    public File getDatabasePath(String name) {
        return hostContext.getDatabasePath("game_" + safe(name));
    }

    @Override
    public File getDir(String name, int mode) {
        return hostContext.getDir("game_" + safe(name), mode);
    }

    private static String safe(String value) {
        if (value == null || value.isEmpty()) return "default";
        return value.replace('/', '_').replace('\\', '_');
    }
}
