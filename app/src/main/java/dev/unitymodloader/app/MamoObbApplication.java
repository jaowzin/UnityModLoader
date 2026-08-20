package dev.unitymodloader.app;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
    private static final String UNITY_PLAYER = "com.unity3d.player.UnityPlayer";
    private static final String UNITY_CURRENT_ACTIVITY = "currentActivity";
    private static final String FIREBASE_DISCOVERY =
            "com.google.firebase.components.ComponentDiscoveryService";
    private static final String FIREBASE_MESSAGING_REGISTRAR =
            "com.google.firebase.components:com.google.firebase.messaging.FirebaseMessagingRegistrar";

    private Context targetContext;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashDiagnostics.install(this);
        ShizukuCrashCollector.schedule(this);

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
        initializeHostedFirebase();
        installUnityActivityBridge();

        File dir = super.getObbDir();
        if (dir != null && !dir.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        Log.i(TAG, "Loader OBB mirror=" + (dir == null ? "null" : dir.getAbsolutePath()));
        Log.i(TAG, "Binder identity package=" + super.getPackageName()
                + "; opPackage=" + super.getOpPackageName());
    }

    /**
     * Bring up the Java Firebase graph before Unity/IL2CPP reaches the Firebase
     * C++ wrappers. In a normal APK FirebaseInitProvider does this during app
     * startup. The hosted process instead loads Firebase classes/resources from
     * the installed target APK while Android components belong to the loader, so
     * we perform the equivalent initialization explicitly.
     */
    private void initializeHostedFirebase() {
        Context target = targetContext;
        if (target == null) {
            Log.w(TAG, "Hosted Firebase bootstrap skipped: target context unavailable");
            return;
        }

        try {
            ClassLoader loader = target.getClassLoader();
            if (loader == null) {
                Log.e(TAG, "Hosted Firebase bootstrap failed: target ClassLoader is null");
                return;
            }
            Thread.currentThread().setContextClassLoader(loader);

            Class<?> optionsClass = Class.forName(
                    "com.google.firebase.FirebaseOptions", true, loader);
            Method fromResource = optionsClass.getMethod("fromResource", Context.class);
            Object options = fromResource.invoke(null, this);
            if (options == null) {
                Log.e(TAG, "Hosted Firebase bootstrap failed: FirebaseOptions.fromResource returned null");
                return;
            }

            Class<?> appClass = Class.forName("com.google.firebase.FirebaseApp", true, loader);
            Object firebaseApp = null;
            try {
                firebaseApp = appClass.getMethod("getInstance").invoke(null);
            } catch (Throwable missingDefault) {
                Log.i(TAG, "No default FirebaseApp yet; creating hosted default instance");
            }

            if (firebaseApp == null) {
                Method initializeApp = appClass.getMethod(
                        "initializeApp", Context.class, optionsClass);
                firebaseApp = initializeApp.invoke(null, this, options);
            }
            if (firebaseApp == null) {
                Log.e(TAG, "Hosted Firebase bootstrap failed: FirebaseApp is null");
                return;
            }
            Log.i(TAG, "Hosted FirebaseApp ready: " + firebaseApp);

            preflightCrashlytics(loader);
            preflightMessaging(loader);
        } catch (Throwable error) {
            Log.e(TAG, "Hosted Firebase bootstrap failed", unwrapReflection(error));
        }
    }

    private void preflightCrashlytics(ClassLoader loader) {
        try {
            Class<?> crashClass = Class.forName(
                    "com.google.firebase.crashlytics.FirebaseCrashlytics", true, loader);
            Object crashlytics = crashClass.getMethod("getInstance").invoke(null);
            if (crashlytics == null) {
                Log.e(TAG, "Hosted Firebase preflight: Crashlytics instance is NULL");
                return;
            }

            Field coreField = crashClass.getDeclaredField("core");
            coreField.setAccessible(true);
            Object core = coreField.get(crashlytics);
            if (core == null) {
                Log.e(TAG, "Hosted Firebase preflight: Crashlytics.core is NULL");
                return;
            }

            Object arbiter = null;
            try {
                Field arbiterField = core.getClass().getDeclaredField("dataCollectionArbiter");
                arbiterField.setAccessible(true);
                arbiter = arbiterField.get(core);
            } catch (Throwable error) {
                Log.w(TAG, "Hosted Firebase preflight: could not inspect dataCollectionArbiter",
                        unwrapReflection(error));
            }

            Log.i(TAG, "Hosted Firebase preflight: Crashlytics.core="
                    + core.getClass().getName()
                    + "; dataCollectionArbiter="
                    + (arbiter == null ? "NULL" : arbiter.getClass().getName()));
        } catch (Throwable error) {
            Log.e(TAG, "Hosted Firebase preflight: Crashlytics unavailable",
                    unwrapReflection(error));
        }
    }

    private void preflightMessaging(ClassLoader loader) {
        try {
            Class<?> messagingClass = Class.forName(
                    "com.google.firebase.messaging.FirebaseMessaging", true, loader);
            Object messaging = messagingClass.getMethod("getInstance").invoke(null);
            Log.i(TAG, "Hosted Firebase preflight: Messaging="
                    + (messaging == null ? "NULL" : messaging.getClass().getName()));
        } catch (Throwable error) {
            Log.e(TAG, "Hosted Firebase preflight: Messaging unavailable",
                    unwrapReflection(error));
        }
    }

    private static Throwable unwrapReflection(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null
                && (current instanceof java.lang.reflect.InvocationTargetException
                || current instanceof ExceptionInInitializerError)) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Firebase Unity's native bootstrap obtains its Android Activity from the
     * static com.unity3d.player.UnityPlayer.currentActivity field. Because the
     * hosted loader constructs UnityPlayerForActivityOrService directly instead
     * of entering through UnityPlayerActivity, Unity does not get a chance to
     * populate that field itself. Bind the real hosted Activity before its
     * onCreate() runs so Firebase C++ never receives a null jobject.
     */
    private void installUnityActivityBridge() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                bindUnityCurrentActivity(activity, "preCreate");
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                bindUnityCurrentActivity(activity, "created");
            }

            @Override
            public void onActivityStarted(Activity activity) {
                bindUnityCurrentActivity(activity, "started");
            }

            @Override
            public void onActivityResumed(Activity activity) {
                bindUnityCurrentActivity(activity, "resumed");
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
                // Do not clear Firebase's Activity bridge here. The Firebase Unity
                // native library keeps a process-wide global reference after its
                // first lookup; rebinding on the next hosted Activity is safer than
                // turning UnityPlayer.currentActivity back into null mid-shutdown.
            }
        });
    }

    private void bindUnityCurrentActivity(Activity activity, String stage) {
        if (!(activity instanceof GameHostActivity)) return;

        Context target = targetContext;
        if (target == null) {
            Log.w(TAG, "Unity currentActivity bridge skipped: target context unavailable");
            return;
        }

        try {
            ClassLoader loader = target.getClassLoader();
            Class<?> unityPlayer = Class.forName(UNITY_PLAYER, true, loader);
            Field field = unityPlayer.getDeclaredField(UNITY_CURRENT_ACTIVITY);
            field.setAccessible(true);

            Object previous = field.get(null);
            if (previous != activity) {
                field.set(null, activity);
            }

            Object current = field.get(null);
            if (current != activity) {
                throw new IllegalStateException("UnityPlayer.currentActivity did not retain GameHostActivity");
            }

            Log.i(TAG, "UnityPlayer.currentActivity bound at " + stage
                    + " -> " + activity.getClass().getName());
        } catch (Throwable error) {
            Log.e(TAG, "Could not bind UnityPlayer.currentActivity at " + stage, error);
        }
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
