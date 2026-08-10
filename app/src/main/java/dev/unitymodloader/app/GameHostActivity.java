package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Experimental host that creates an installed Unity game's UnityPlayer inside
 * UnityModLoader's process. It does not modify or re-sign the target APK.
 */
public final class GameHostActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String TAG = "UML.GameHost";

    private static final String[] UNITY_PLAYER_CLASSES = {
            "com.unity3d.player.UnityPlayer",
            "com.unity3d.player.UnityPlayerForActivityOrService"
    };

    private Context installedGameContext;
    private GameContextBridge bridge;
    private Object unityPlayer;
    private View unityView;
    private String targetPackage;
    private TextView bootStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            showFatal("Pacote do jogo não informado.", null);
            return;
        }

        showBootUi("Preparando " + targetPackage + "…");
        try {
            startHostedUnity();
        } catch (Throwable error) {
            showFatal("Falha ao iniciar Unity dentro do loader", error);
        }
    }

    private void showBootUi(String message) {
        FrameLayout root = new FrameLayout(this);

        ProgressBar progress = new ProgressBar(this);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(progress, progressParams);

        bootStatus = new TextView(this);
        bootStatus.setText(message);
        bootStatus.setTextSize(16f);
        bootStatus.setGravity(Gravity.CENTER);
        int pad = dp(24);
        bootStatus.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        root.addView(bootStatus, textParams);
        setContentView(root);
    }

    private void startHostedUnity() throws Exception {
        installedGameContext = createPackageContext(
                targetPackage,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
        );
        bridge = new GameContextBridge(installedGameContext, this);

        ClassLoader gameLoader = installedGameContext.getClassLoader();
        if (gameLoader == null) {
            throw new IllegalStateException("ClassLoader do jogo é nulo");
        }
        Thread.currentThread().setContextClassLoader(gameLoader);

        Class<?> playerClass = findUnityPlayerClass(gameLoader);
        if (bootStatus != null) {
            bootStatus.setText("Unity encontrado: " + playerClass.getName());
        }

        unityPlayer = constructUnityPlayer(playerClass);
        if (!(unityPlayer instanceof View)) {
            throw new IllegalStateException(
                    "UnityPlayer foi criado, mas não é uma View: " + unityPlayer.getClass().getName()
            );
        }

        unityView = (View) unityPlayer;
        unityView.setFocusableInTouchMode(true);
        unityView.requestFocus();
        setContentView(unityView);

        File pluginDir = new File(
                getExternalFilesDir(null),
                "games/" + targetPackage + "/plugins"
        );
        if (!pluginDir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            pluginDir.mkdirs();
        }

        String pluginReport = NativeBridge.loadNativePlugins(
                pluginDir.getAbsolutePath(),
                targetPackage
        );
        Log.i(TAG, pluginReport);
        if (pluginReport.contains("CARREGADO")) {
            Toast.makeText(this, "Plugin(s) nativo(s) carregado(s)", Toast.LENGTH_SHORT).show();
        }

        Log.i(TAG, "Hosted Unity started for " + targetPackage
                + " using " + playerClass.getName()
                + "; nativeLibDir=" + installedGameContext.getApplicationInfo().nativeLibraryDir);
    }

    private Class<?> findUnityPlayerClass(ClassLoader loader) throws ClassNotFoundException {
        List<String> errors = new ArrayList<>();
        for (String candidate : UNITY_PLAYER_CLASSES) {
            try {
                return Class.forName(candidate, true, loader);
            } catch (ClassNotFoundException e) {
                errors.add(candidate);
            }
        }
        throw new ClassNotFoundException("Nenhuma classe UnityPlayer encontrada: " + errors);
    }

    private Object constructUnityPlayer(Class<?> playerClass) throws Exception {
        Constructor<?>[] constructors = playerClass.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

        Throwable lastError = null;
        for (Constructor<?> constructor : constructors) {
            Object[] args = buildConstructorArguments(constructor.getParameterTypes());
            if (args == null) continue;

            try {
                constructor.setAccessible(true);
                Object value = constructor.newInstance(args);
                Log.i(TAG, "UnityPlayer constructor selected: " + constructor);
                return value;
            } catch (Throwable error) {
                lastError = error;
                Log.w(TAG, "UnityPlayer constructor failed: " + constructor, error);
            }
        }

        IllegalStateException failure = new IllegalStateException(
                "Nenhum construtor UnityPlayer compatível foi encontrado"
        );
        if (lastError != null) failure.initCause(lastError);
        throw failure;
    }

    private Object[] buildConstructorArguments(Class<?>[] types) {
        if (types.length == 0) return new Object[0];

        Object[] args = new Object[types.length];
        boolean hasContext = false;

        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];

            if (type.isInstance(bridge)) {
                args[i] = bridge;
                hasContext = true;
                continue;
            }
            if (type.isInstance(this)) {
                args[i] = this;
                hasContext = true;
                continue;
            }
            if (Context.class.isAssignableFrom(type)) {
                if (type.isAssignableFrom(bridge.getClass())) {
                    args[i] = bridge;
                    hasContext = true;
                    continue;
                }
                if (type.isAssignableFrom(getClass())) {
                    args[i] = this;
                    hasContext = true;
                    continue;
                }
                return null;
            }
            if (type.isInterface() && type.getName().contains("IUnityPlayerLifecycleEvents")) {
                args[i] = Proxy.newProxyInstance(
                        installedGameContext.getClassLoader(),
                        new Class<?>[]{type},
                        (proxy, method, methodArgs) -> defaultValue(method.getReturnType())
                );
                continue;
            }
            if (type.isPrimitive()) {
                args[i] = defaultValue(type);
                continue;
            }
            if (type == String.class) {
                args[i] = "";
                continue;
            }

            // Optional Unity helper/listener argument. Null is safer than inventing
            // an implementation for an unknown game-specific class.
            args[i] = null;
        }

        // UnityPlayer constructors are expected to be attached to a Context/Activity.
        return hasContext ? args : null;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == void.class || !type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private void invokeUnity(String name, Class<?>[] parameterTypes, Object... args) {
        Object player = unityPlayer;
        if (player == null) return;

        Class<?> current = player.getClass();
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                method.invoke(player, args);
                return;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                Log.w(TAG, "Unity lifecycle call failed: " + name, error);
                return;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        invokeUnity("resume", new Class<?>[0]);
    }

    @Override
    protected void onPause() {
        invokeUnity("pause", new Class<?>[0]);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        invokeUnity("quit", new Class<?>[0]);
        unityPlayer = null;
        unityView = null;
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        invokeUnity("windowFocusChanged", new Class<?>[]{boolean.class}, hasFocus);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invokeUnity("configurationChanged", new Class<?>[]{Configuration.class}, newConfig);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        invokeUnity("lowMemory", new Class<?>[0]);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        invokeUnity("newIntent", new Class<?>[]{Intent.class}, intent);
    }

    private void showFatal(String message, Throwable error) {
        Log.e(TAG, message, error);
        TextView text = new TextView(this);
        String details = error == null ? "" : "\n\n" + error.getClass().getSimpleName() + ": " + error.getMessage();
        text.setText(message + details + "\n\nVolte ao Unity Mod Loader e tente outro jogo.");
        text.setTextSize(16f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(24), dp(24), dp(24));
        setContentView(text);
    }

    /*
     * When UnityPlayer has a constructor that specifically requires Activity,
     * these overrides make our Activity expose the target game's resources/code
     * while keeping writable storage in our own sandbox.
     */
    @Override
    public AssetManager getAssets() {
        return bridge != null ? bridge.getAssets() : super.getAssets();
    }

    @Override
    public Resources getResources() {
        return bridge != null ? bridge.getResources() : super.getResources();
    }

    @Override
    public ClassLoader getClassLoader() {
        return bridge != null ? bridge.getClassLoader() : super.getClassLoader();
    }

    @Override
    public ApplicationInfo getApplicationInfo() {
        return bridge != null ? bridge.getApplicationInfo() : super.getApplicationInfo();
    }

    @Override
    public String getPackageName() {
        return bridge != null ? bridge.getPackageName() : super.getPackageName();
    }

    @Override
    public File getFilesDir() {
        return bridge != null ? bridge.getFilesDir() : super.getFilesDir();
    }

    @Override
    public File getCacheDir() {
        return bridge != null ? bridge.getCacheDir() : super.getCacheDir();
    }

    @Override
    public File getExternalFilesDir(String type) {
        // Always use the loader package's external sandbox. Calling bridge here
        // would recurse because bridge delegates this method back to the host.
        return super.getExternalFilesDir(type);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return bridge != null ? bridge.getSharedPreferences(name, mode) : super.getSharedPreferences(name, mode);
    }

    private int dp(int value) {
        return Math.round(value * super.getResources().getDisplayMetrics().density);
    }
}
