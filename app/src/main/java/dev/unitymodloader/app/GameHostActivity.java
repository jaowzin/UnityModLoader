package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;

/** Hosted Unity activity for the authorized Mamo Ball CTF target. */
public final class GameHostActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "target_package";

    private static final String TAG = "UML.GameHost";
    private static final String UNITY6_ACTIVITY_PLAYER =
            "com.unity3d.player.UnityPlayerForActivityOrService";
    private static final String[] UNITY_PLAYER_CLASSES = {
            UNITY6_ACTIVITY_PLAYER,
            "com.unity3d.player.UnityPlayer"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context installedGameContext;
    private GameContextBridge bridge;
    private Object unityPlayer;
    private View unityView;
    private String targetPackage;
    private TextView bootStatus;
    private TextView diagnosticOverlay;
    private long hostedStartedAt;

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
        root.addView(progress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        bootStatus = new TextView(this);
        bootStatus.setText(message);
        bootStatus.setTextSize(16f);
        bootStatus.setGravity(Gravity.CENTER);
        int pad = dp(24);
        bootStatus.setPadding(pad, pad, pad, pad);
        root.addView(bootStatus, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        setContentView(root);
    }

    private void startHostedUnity() throws Exception {
        installedGameContext = createPackageContext(
                targetPackage,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
        );
        bridge = new GameContextBridge(installedGameContext, this);

        File obbDir = bridge.getObbDir();
        Log.i(TAG, "Target paths: package=" + bridge.getPackageName()
                + "; code=" + bridge.getPackageCodePath()
                + "; resources=" + bridge.getPackageResourcePath()
                + "; nativeLibDir=" + bridge.getApplicationInfo().nativeLibraryDir
                + "; obbDir=" + (obbDir == null ? "null" : obbDir.getAbsolutePath())
                + "; obbExists=" + (obbDir != null && obbDir.exists()));

        ClassLoader gameLoader = installedGameContext.getClassLoader();
        if (gameLoader == null) {
            throw new IllegalStateException("ClassLoader do jogo é nulo");
        }
        Thread.currentThread().setContextClassLoader(gameLoader);

        Class<?> playerClass = findUnityPlayerClass(gameLoader);
        if (bootStatus != null) {
            bootStatus.setText("Unity encontrado: " + playerClass.getName()
                    + "\nOBB: " + (obbDir == null ? "não localizado" : obbDir.getAbsolutePath()));
        }

        unityPlayer = constructUnityPlayer(playerClass);
        unityView = extractUnityView(unityPlayer);
        if (unityView == null) {
            throw new IllegalStateException(
                    "Unity foi criado, mas nenhuma View compatível foi encontrada em "
                            + unityPlayer.getClass().getName());
        }

        unityView.setFocusableInTouchMode(true);
        unityView.requestFocus();

        FrameLayout hostedRoot = new FrameLayout(this);
        hostedRoot.addView(unityView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(hostedRoot);

        installDiagnostics(hostedRoot);
        scheduleBootstrapLifecyclePulse(hostedRoot);

        File pluginDir = new File(
                super.getExternalFilesDir(null),
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
                + " using " + playerClass.getName());
    }

    private void installDiagnostics(FrameLayout root) {
        hostedStartedAt = System.currentTimeMillis();

        diagnosticOverlay = new TextView(this);
        diagnosticOverlay.setTextSize(10f);
        diagnosticOverlay.setTextColor(Color.WHITE);
        diagnosticOverlay.setBackgroundColor(0x99000000);
        diagnosticOverlay.setPadding(dp(7), dp(5), dp(7), dp(5));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        params.setMargins(dp(6), dp(6), 0, 0);
        root.addView(diagnosticOverlay, params);

        Runnable updater = new Runnable() {
            @Override
            public void run() {
                TextView overlay = diagnosticOverlay;
                if (overlay == null || isFinishing()) return;

                long elapsed = Math.max(0L, System.currentTimeMillis() - hostedStartedAt);
                int identityHits = bridge == null ? 0 : bridge.getGoogleIdentityHits();
                String caller = bridge == null ? "none" : simpleClassName(bridge.getLastGoogleCaller());
                overlay.setText(
                        "UML 0.8.6 DIAG"
                                + "\nUnity: " + (unityPlayer != null ? "OK" : "WAIT")
                                + "  IL2CPP: " + (isLibraryMapped("libil2cpp.so") ? "OK" : "WAIT")
                                + "\nFocus: " + hasWindowFocus()
                                + "  GMS host hits: " + identityHits
                                + "\nGMS caller: " + caller
                                + "\nTempo: " + (elapsed / 1000L) + "s"
                );

                if (elapsed < 30000L) {
                    mainHandler.postDelayed(this, 1000L);
                } else {
                    overlay.setVisibility(View.GONE);
                }
            }
        };
        mainHandler.post(updater);
    }

    private void scheduleBootstrapLifecyclePulse(FrameLayout root) {
        root.postDelayed(() -> {
            if (unityPlayer == null || isFinishing()) return;
            Log.i(TAG, "Bootstrap lifecycle recovery pulse: resume/focus");
            invokeUnityAny("onResume", "resume");
            if (hasWindowFocus()) {
                invokeUnity("windowFocusChanged", new Class<?>[]{boolean.class}, true);
            }
        }, 1200L);

        root.postDelayed(() -> {
            if (unityPlayer == null || isFinishing() || !hasWindowFocus()) return;
            Log.i(TAG, "Bootstrap focus recovery pulse");
            invokeUnity("windowFocusChanged", new Class<?>[]{boolean.class}, true);
        }, 3500L);
    }

    private boolean isLibraryMapped(String libraryName) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(libraryName)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String simpleClassName(String value) {
        if (value == null || value.isEmpty()) return "none";
        int index = value.lastIndexOf('.');
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
    }

    private Class<?> findUnityPlayerClass(ClassLoader loader) throws ClassNotFoundException {
        for (String candidate : UNITY_PLAYER_CLASSES) {
            try {
                return Class.forName(candidate, true, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException("Nenhuma classe UnityPlayer encontrada");
    }

    private Object constructUnityPlayer(Class<?> playerClass) throws Exception {
        if (UNITY6_ACTIVITY_PLAYER.equals(playerClass.getName())) {
            Object value = constructUnity6ActivityPlayer(playerClass);
            if (value != null) return value;
        }

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
                "Nenhum construtor UnityPlayer compatível foi encontrado");
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
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("onUnityPlayerQuitted".equals(name)) {
                        runOnUiThread(this::finish);
                    } else if ("toString".equals(name)) {
                        return "MamoBallLoaderLifecycle";
                    } else if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(name)) {
                        return args != null && args.length == 1 && proxy == args[0];
                    }
                    return defaultValue(method.getReturnType());
                }
        );

        Constructor<?> constructor = playerClass.getDeclaredConstructor(Context.class, lifecycleType);
        constructor.setAccessible(true);

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
            if (type.isInstance(this)) {
                args[i] = this;
                hasContext = true;
            } else if (bridge != null && type.isInstance(bridge)) {
                args[i] = bridge;
                hasContext = true;
            } else if (Context.class.isAssignableFrom(type)) {
                if (type.isAssignableFrom(getClass())) {
                    args[i] = this;
                    hasContext = true;
                } else if (bridge != null && type.isAssignableFrom(bridge.getClass())) {
                    args[i] = bridge;
                    hasContext = true;
                } else {
                    return null;
                }
            } else if (type.isInterface() && type.getName().contains("IUnityPlayerLifecycleEvents")) {
                args[i] = Proxy.newProxyInstance(
                        installedGameContext.getClassLoader(),
                        new Class<?>[]{type},
                        (proxy, method, methodArgs) -> defaultValue(method.getReturnType()));
            } else if (type.isPrimitive()) {
                args[i] = defaultValue(type);
            } else if (type == String.class) {
                args[i] = "";
            } else {
                args[i] = null;
            }
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
                if (value instanceof View) return (View) value;
            } catch (Throwable ignored) {
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
        diagnosticOverlay = null;
        mainHandler.removeCallbacksAndMessages(null);
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
        invokeUnity("lowMemory", new Class<?>[0]);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        invokeUnity("newIntent", new Class<?>[]{Intent.class}, intent);
    }

    @Override public AssetManager getAssets() {
        return bridge != null ? bridge.getAssets() : super.getAssets();
    }

    @Override public Resources getResources() {
        return bridge != null ? bridge.getResources() : super.getResources();
    }

    @Override public ClassLoader getClassLoader() {
        return bridge != null ? bridge.getClassLoader() : super.getClassLoader();
    }

    @Override public ApplicationInfo getApplicationInfo() {
        return bridge != null ? bridge.getApplicationInfo() : super.getApplicationInfo();
    }

    @Override public String getPackageName() {
        return bridge != null ? bridge.getPackageName() : super.getPackageName();
    }

    @Override public String getPackageCodePath() {
        return bridge != null ? bridge.getPackageCodePath() : super.getPackageCodePath();
    }

    @Override public String getPackageResourcePath() {
        return bridge != null ? bridge.getPackageResourcePath() : super.getPackageResourcePath();
    }

    @Override public File getObbDir() {
        return bridge != null ? bridge.getObbDir() : super.getObbDir();
    }

    @Override public File[] getObbDirs() {
        return bridge != null ? bridge.getObbDirs() : super.getObbDirs();
    }

    @Override public File getFilesDir() {
        return bridge != null ? bridge.getFilesDir() : super.getFilesDir();
    }

    @Override public File getCacheDir() {
        return bridge != null ? bridge.getCacheDir() : super.getCacheDir();
    }

    @Override public File getExternalFilesDir(String type) {
        return super.getExternalFilesDir(type);
    }

    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return bridge != null ? bridge.getSharedPreferences(name, mode) : super.getSharedPreferences(name, mode);
    }

    private void showFatal(String message, Throwable error) {
        Throwable root = unwrap(error);
        Log.e(TAG, message, root == null ? error : root);

        TextView text = new TextView(this);
        String details = "";
        if (root != null) {
            details = "\n\n" + root.getClass().getSimpleName() + ": " + root.getMessage();
        }
        text.setText(message + details + "\n\nVolte ao Mamo Ball Mod Core.");
        text.setTextSize(16f);
        text.setGravity(Gravity.CENTER);
        text.setPadding(dp(24), dp(24), dp(24), dp(24));
        setContentView(text);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof InvocationTargetException
                    && ((InvocationTargetException) current).getTargetException() != null) {
                current = ((InvocationTargetException) current).getTargetException();
                continue;
            }
            Throwable cause = current.getCause();
            if (cause == null || cause == current) break;
            current = cause;
        }
        return current;
    }

    private int dp(int value) {
        return Math.round(value * super.getResources().getDisplayMetrics().density);
    }
}
