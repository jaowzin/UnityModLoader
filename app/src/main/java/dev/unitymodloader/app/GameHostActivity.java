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
 * Experimental Unity host.
 *
 * Fire Zone (com.sfcgs.gun.terrorist.shooting.missions) was inspected directly:
 * Unity 6000.0.68f1, ARM64, IL2CPP. Its launcher UnityPlayerActivity creates
 * UnityPlayerForActivityOrService(Context, IUnityPlayerLifecycleEvents), not the
 * older UnityPlayer(Context) shape. This host mirrors that path first.
 */
public final class GameHostActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    private static final String TAG = "UML.GameHost";

    private static final String FIRE_ZONE_PACKAGE = "com.sfcgs.gun.terrorist.shooting.missions";
    private static final String UNITY6_ACTIVITY_PLAYER = "com.unity3d.player.UnityPlayerForActivityOrService";

    private static final String[] UNITY_PLAYER_CLASSES = {
            // Unity 6 Activity path first. Fire Zone uses this class directly.
            UNITY6_ACTIVITY_PLAYER,
            "com.unity3d.player.UnityPlayer"
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

        Log.i(TAG, "Target context paths: package=" + getPackageName()
                + "; code=" + getPackageCodePath()
                + "; resources=" + getPackageResourcePath()
                + "; sourceDir=" + getApplicationInfo().sourceDir
                + "; dataDir=" + getApplicationInfo().dataDir);

        ClassLoader gameLoader = installedGameContext.getClassLoader();
        if (gameLoader == null) {
            throw new IllegalStateException("ClassLoader do jogo é nulo");
        }
        Thread.currentThread().setContextClassLoader(gameLoader);

        Class<?> playerClass = findUnityPlayerClass(gameLoader);
        if (bootStatus != null) {
            String suffix = FIRE_ZONE_PACKAGE.equals(targetPackage)
                    ? " (Fire Zone / Unity 6000.0.68f1)"
                    : "";
            bootStatus.setText("Unity encontrado: " + playerClass.getName() + suffix);
        }

        unityPlayer = constructUnityPlayer(playerClass);
        unityView = extractUnityView(unityPlayer);
        if (unityView == null) {
            throw new IllegalStateException(
                    "Unity foi criado, mas nenhuma View/FrameLayout compatível foi encontrada em "
                            + unityPlayer.getClass().getName()
            );
        }

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
        // Fire Zone / Unity 6 exact launcher path:
        // new UnityPlayerForActivityOrService(this, thisLifecycleEvents)
        if (UNITY6_ACTIVITY_PLAYER.equals(playerClass.getName())) {
            Object unity6 = constructUnity6ActivityPlayer(playerClass);
            if (unity6 != null) return unity6;
        }

        // Fallback for older/different Unity versions.
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

    private Object constructUnity6ActivityPlayer(Class<?> playerClass) throws Exception {
        ClassLoader loader = installedGameContext.getClassLoader();
        Class<?> lifecycleType = Class.forName(
                "com.unity3d.player.IUnityPlayerLifecycleEvents",
                true,
                loader
        );

        Object lifecycleProxy = Proxy.newProxyInstance(
                loader,
                new Class<?>[]{lifecycleType},
                (proxy, method, methodArgs) -> {
                    String name = method.getName();
                    if ("onUnityPlayerQuitted".equals(name)) {
                        Log.i(TAG, "Unity lifecycle: onUnityPlayerQuitted");
                        runOnUiThread(this::finish);
                    } else if ("onUnityPlayerUnloaded".equals(name)) {
                        Log.i(TAG, "Unity lifecycle: onUnityPlayerUnloaded");
                    } else if ("toString".equals(name)) {
                        return "UnityModLoaderLifecycleProxy";
                    } else if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return methodArgs != null && methodArgs.length == 1 && proxy == methodArgs[0];
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Constructor<?> constructor = playerClass.getDeclaredConstructor(Context.class, lifecycleType);
        constructor.setAccessible(true);

        // The real Fire Zone UnityPlayerActivity passes the Activity itself as Context.
        // Our Activity exposes the target game's resources/classloader/APK path through overrides.
        Object value = constructor.newInstance(this, lifecycleProxy);
        Log.i(TAG, "Unity 6 Activity constructor selected: " + constructor);
        return value;
    }

    private Object[] buildConstructorArguments(Class<?>[] types) {
        if (types.length == 0) return new Object[0];

        Object[] args = new Object[types.length];
        boolean hasContext = false;

        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];

            // Prefer the Activity for Context because modern Unity checks Activity features.
            if (type.isInstance(this)) {
                args[i] = this;
                hasContext = true;
                continue;
            }
            if (type.isInstance(bridge)) {
                args[i] = bridge;
                hasContext = true;
                continue;
            }
            if (Context.class.isAssignableFrom(type)) {
                if (type.isAssignableFrom(getClass())) {
                    args[i] = this;
                    hasContext = true;
                    continue;
                }
                if (type.isAssignableFrom(bridge.getClass())) {
                    args[i] = bridge;
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

            args[i] = null;
        }

        return hasContext ? args : null;
    }

    private View extractUnityView(Object player) {
        if (player == null) return null;
        if (player instanceof View) return (View) player;

        for (String methodName : new String[]{"getFrameLayout", "getView"}) {
            try {
                Method method = player.getClass().getMethod(methodName);
                method.setAccessible(true);
                Object value = method.invoke(player);
                if (value instanceof View) {
                    Log.i(TAG, "Unity view selected via " + methodName + "(): "
                            + value.getClass().getName());
                    return (View) value;
                }
            } catch (Throwable error) {
                Log.d(TAG, "Unity view method unavailable: " + methodName, error);
            }
        }
        return null;
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

    private boolean invokeUnity(String name, Class<?>[] parameterTypes, Object... args) {
        Object player = unityPlayer;
        if (player == null) return false;

        Class<?> current = player.getClass();
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                method.invoke(player, args);
                return true;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable error) {
                Log.w(TAG, "Unity lifecycle call failed: " + name, error);
                return false;
            }
        }
        return false;
    }

    private void invokeUnityAny(String primary, String fallback) {
        if (!invokeUnity(primary, new Class<?>[0]) && fallback != null) {
            invokeUnity(fallback, new Class<?>[0]);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        invokeUnityAny("onStart", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        invokeUnityAny("onResume", "resume");
    }

    @Override
    protected void onPause() {
        invokeUnityAny("onPause", "pause");
        super.onPause();
    }

    @Override
    protected void onStop() {
        invokeUnityAny("onStop", null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (!invokeUnity("destroy", new Class<?>[0])) {
            if (!invokeUnity("quit", new Class<?>[0])) {
                invokeUnity("shutdown", new Class<?>[0]);
            }
        }
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
        // Older UnityPlayer exposes lowMemory(). Unity 6 uses onTrimMemory(enum),
        // so no fake enum is injected here.
        invokeUnity("lowMemory", new Class<?>[0]);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        invokeUnity("newIntent", new Class<?>[]{Intent.class}, intent);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Object player = unityPlayer;
        if (player == null) return;
        try {
            Method method = player.getClass().getMethod(
                    "permissionResponse",
                    Activity.class,
                    int.class,
                    String[].class,
                    int[].class
            );
            method.invoke(player, this, requestCode, permissions, grantResults);
        } catch (NoSuchMethodException ignored) {
            // Older Unity versions may handle this differently.
        } catch (Throwable error) {
            Log.w(TAG, "Unity permissionResponse failed", error);
        }
    }

    private void showFatal(String message, Throwable error) {
        Log.e(TAG, message, error);
        TextView text = new TextView(this);
        String details = error == null ? "" : "\n\n" + error.getClass().getSimpleName() + ": " + error.getMessage();
        String targetHint = FIRE_ZONE_PACKAGE.equals(targetPackage)
                ? "\n\nPerfil: Fire Zone / Unity 6000.0.68f1 / IL2CPP ARM64"
                : "";
        text.setText(message + details + targetHint + "\n\nVolte ao Unity Mod Loader.");
        text.setTextSize(16f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(24), dp(24), dp(24));
        setContentView(text);
    }

    /*
     * Expose the installed game's resources/code/native-library metadata while
     * writable storage remains inside UnityModLoader's own sandbox.
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
    public String getPackageCodePath() {
        return bridge != null ? bridge.getPackageCodePath() : super.getPackageCodePath();
    }

    @Override
    public String getPackageResourcePath() {
        return bridge != null ? bridge.getPackageResourcePath() : super.getPackageResourcePath();
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
