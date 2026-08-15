package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Dedicated Fire Zone CTF launcher. */
public final class MainActivity extends Activity {
    private static final String TAG = "FZ.ModCore";
    private static final String TARGET_PACKAGE = InstalledUnityScanner.TARGET_PACKAGE;
    private static final String PREFS = "firezone_mod_prefs";
    private static final String KEY_INFINITE_AMMO = "infinite_ammo";

    private static final int BG = Color.rgb(8, 11, 18);
    private static final int CARD = Color.rgb(18, 24, 35);
    private static final int CARD_ALT = Color.rgb(22, 29, 42);
    private static final int TEXT = Color.rgb(242, 245, 250);
    private static final int MUTED = Color.rgb(153, 164, 181);
    private static final int ACCENT = Color.rgb(255, 102, 61);
    private static final int GREEN = Color.rgb(42, 211, 151);
    private static final int STROKE = Color.rgb(42, 52, 69);

    private ImageView gameIcon;
    private TextView gameName;
    private TextView packageLine;
    private TextView statusBadge;
    private TextView details;
    private ProgressBar progress;
    private Switch infiniteAmmoSwitch;
    private Button launchModded;
    private Button launchClean;
    private Button recheck;

    private InstalledUnityGame targetGame;
    private ModBackend targetBackend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);

        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        infiniteAmmoSwitch.setChecked(prefs.getBoolean(KEY_INFINITE_AMMO, true));
        infiniteAmmoSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_INFINITE_AMMO, checked).apply());

        scanTarget();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        TextView overline = text("FIRE ZONE // MOD CORE", 12f, ACCENT, Typeface.BOLD);
        overline.setLetterSpacing(0.16f);
        root.addView(overline);

        TextView title = text("CTF Launcher", 32f, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);

        TextView subtitle = text("Loader IL2CPP dedicado • build 0.6.0", 14f, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        LinearLayout gameCard = card();
        gameCard.setOrientation(LinearLayout.VERTICAL);
        root.addView(gameCard, matchWrap(dp(14)));

        LinearLayout gameTop = new LinearLayout(this);
        gameTop.setOrientation(LinearLayout.HORIZONTAL);
        gameTop.setGravity(Gravity.CENTER_VERTICAL);
        gameCard.addView(gameTop);

        gameIcon = new ImageView(this);
        gameIcon.setBackground(roundRect(CARD_ALT, 18, STROKE, 1));
        gameIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
        gameTop.addView(gameIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout gameText = new LinearLayout(this);
        gameText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gameTextParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gameTextParams.setMargins(dp(14), 0, dp(8), 0);
        gameTop.addView(gameText, gameTextParams);

        gameName = text("Fire Zone", 22f, TEXT, Typeface.BOLD);
        gameText.addView(gameName);

        packageLine = text(TARGET_PACKAGE, 12f, MUTED, Typeface.NORMAL);
        packageLine.setPadding(0, dp(4), 0, 0);
        gameText.addView(packageLine);

        statusBadge = text("CHECKING", 11f, MUTED, Typeface.BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusBadge.setBackground(roundRect(Color.rgb(30, 37, 50), 999, STROKE, 1));
        gameTop.addView(statusBadge);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(18), 0, dp(4));
        gameCard.addView(progress, progressParams);

        details = text("Validando APK base + split ARM64…", 13f, MUTED, Typeface.NORMAL);
        details.setLineSpacing(0f, 1.18f);
        details.setPadding(0, dp(14), 0, 0);
        gameCard.addView(details);

        TextView modHeader = text("MODS DO CTF", 12f, MUTED, Typeface.BOLD);
        modHeader.setLetterSpacing(0.12f);
        modHeader.setPadding(0, dp(24), 0, dp(10));
        root.addView(modHeader);

        LinearLayout modCard = card();
        modCard.setOrientation(LinearLayout.VERTICAL);
        modCard.setBackground(roundRect(CARD_ALT, 22, STROKE, 1));
        root.addView(modCard, matchWrap(0));

        LinearLayout modRow = new LinearLayout(this);
        modRow.setOrientation(LinearLayout.HORIZONTAL);
        modRow.setGravity(Gravity.CENTER_VERTICAL);
        modCard.addView(modRow);

        LinearLayout modText = new LinearLayout(this);
        modText.setOrientation(LinearLayout.VERTICAL);
        modRow.addView(modText, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView ammoTitle = text("Munição infinita", 18f, TEXT, Typeface.BOLD);
        modText.addView(ammoTitle);

        TextView ammoDesc = text("Preserva pente + reserva sem alterar a lógica de tiro.",
                13f, MUTED, Typeface.NORMAL);
        ammoDesc.setPadding(0, dp(4), dp(12), 0);
        modText.addView(ammoDesc);

        infiniteAmmoSwitch = new Switch(this);
        infiniteAmmoSwitch.setShowText(false);
        modRow.addView(infiniteAmmoSwitch);

        TextView signature = text(
                "Patch verificado para FZS.0403.GP • 0x115ABF4 / 0x115AD6C",
                11f, MUTED, Typeface.NORMAL);
        signature.setPadding(0, dp(14), 0, 0);
        modCard.addView(signature);

        launchModded = button("INICIAR COM MODS", true);
        launchModded.setEnabled(false);
        launchModded.setOnClickListener(v -> launchHosted());
        LinearLayout.LayoutParams launchParams = matchWrap(dp(22));
        launchParams.height = dp(58);
        root.addView(launchModded, launchParams);

        launchClean = button("Abrir jogo original", false);
        launchClean.setEnabled(false);
        launchClean.setOnClickListener(v -> launchClean());
        LinearLayout.LayoutParams cleanParams = matchWrap(dp(10));
        cleanParams.height = dp(50);
        root.addView(launchClean, cleanParams);

        recheck = button("Revalidar instalação", false);
        recheck.setOnClickListener(v -> scanTarget());
        LinearLayout.LayoutParams recheckParams = matchWrap(dp(10));
        recheckParams.height = dp(46);
        root.addView(recheck, recheckParams);

        Space spacer = new Space(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, dp(20)));

        TextView footer = text("Unity 6000.0.68f1  •  IL2CPP  •  arm64-v8a  •  CTF target only",
                11f, Color.rgb(103, 115, 132), Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);

        return scroll;
    }

    private void scanTarget() {
        targetGame = null;
        targetBackend = null;
        progress.setVisibility(View.VISIBLE);
        statusBadge.setText("CHECKING");
        statusBadge.setTextColor(MUTED);
        statusBadge.setBackground(roundRect(Color.rgb(30, 37, 50), 999, STROKE, 1));
        details.setText("Validando Fire Zone instalado…");
        launchModded.setEnabled(false);
        launchClean.setEnabled(false);
        recheck.setEnabled(false);

        new Thread(() -> {
            List<InstalledUnityGame> result = InstalledUnityScanner.scan(this);
            runOnUiThread(() -> renderTarget(result));
        }, "firezone-target-scan").start();
    }

    private void renderTarget(List<InstalledUnityGame> result) {
        progress.setVisibility(View.GONE);
        recheck.setEnabled(true);

        if (result.isEmpty()) {
            statusBadge.setText("NOT FOUND");
            statusBadge.setTextColor(Color.rgb(255, 120, 120));
            statusBadge.setBackground(roundRect(Color.rgb(54, 27, 32), 999,
                    Color.rgb(98, 43, 51), 1));
            details.setText("Fire Zone não foi encontrado ou a instalação não parece Unity.\n"
                    + TARGET_PACKAGE);
            return;
        }

        targetGame = result.get(0);
        targetBackend = BackendFactory.forGame(targetGame);
        DetectionResult detection = targetGame.getDetection();

        try {
            Drawable icon = getPackageManager().getApplicationIcon(TARGET_PACKAGE);
            gameIcon.setImageDrawable(icon);
        } catch (Throwable ignored) {
            gameIcon.setImageDrawable(null);
        }

        gameName.setText(targetGame.getLabel());

        String version = "?";
        try {
            @SuppressWarnings("deprecation")
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (info.versionName != null) version = info.versionName;
        } catch (Throwable ignored) {}

        boolean profileOk = GameProfileManager.prepare(this, targetGame, targetBackend);
        boolean supported = detection.getBackend() == DetectionResult.Backend.UNITY_IL2CPP
                && detection.hasLibIl2Cpp();

        statusBadge.setText(supported ? "READY" : "UNSUPPORTED");
        statusBadge.setTextColor(supported ? GREEN : Color.rgb(255, 180, 100));
        statusBadge.setBackground(roundRect(
                supported ? Color.rgb(18, 50, 42) : Color.rgb(55, 42, 25),
                999,
                supported ? Color.rgb(29, 91, 73) : Color.rgb(108, 75, 36),
                1));

        details.setText(String.format(Locale.ROOT,
                "Versão: %s\nBackend: %s\nArquitetura: %s\nSplit APKs: %d\nPerfil do loader: %s\nCore: %s",
                version,
                backendName(detection),
                detection.getArchitectures().isEmpty()
                        ? "não detectada"
                        : String.join(", ", detection.getArchitectures()),
                Math.max(0, targetGame.getApkPaths().size() - 1),
                profileOk ? "pronto" : "erro",
                NativeBridge.coreVersion()));

        launchModded.setEnabled(supported && profileOk);
        launchClean.setEnabled(true);
    }

    private void launchHosted() {
        if (targetGame == null) return;

        boolean infiniteAmmo = infiniteAmmoSwitch.isChecked();
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putBoolean(KEY_INFINITE_AMMO, infiniteAmmo).apply();

        if (!GameProfileManager.prepare(this, targetGame, targetBackend)) {
            Toast.makeText(this, "Falha ao preparar perfil", Toast.LENGTH_LONG).show();
            return;
        }

        // Same process: this watcher survives while GameHostActivity starts Unity.
        // It waits until libil2cpp.so appears, then patches/restores the two verified
        // ammo decrement instructions for this exact CTF build.
        startAmmoPatchWatcher(infiniteAmmo);

        Intent host = new Intent(this, GameHostActivity.class);
        host.putExtra(GameHostActivity.EXTRA_TARGET_PACKAGE, TARGET_PACKAGE);
        startActivity(host);
    }

    private void startAmmoPatchWatcher(boolean enabled) {
        new Thread(() -> {
            String last = "";
            for (int attempt = 0; attempt < 80; attempt++) {
                try {
                    last = NativeBridge.setFireZoneInfiniteAmmo(enabled);
                    Log.i(TAG, "Ammo patch: " + last);
                    if (last.startsWith("OK:") || last.startsWith("ERROR:")) {
                        final String result = last;
                        if (result.startsWith("ERROR:")) {
                            runOnUiThread(() -> Toast.makeText(
                                    this, result, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                    Thread.sleep(150L);
                } catch (Throwable error) {
                    Log.e(TAG, "Ammo patch watcher failed", error);
                    return;
                }
            }

            final String result = last.isEmpty()
                    ? "ERROR: libil2cpp não ficou pronto a tempo"
                    : last;
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        }, "firezone-ammo-patch").start();
    }

    private void launchClean() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "Fire Zone não possui launcher visível", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(roundRect(CARD, 22, STROKE, 1));
        return layout;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(primary ? 15f : 14f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(primary ? Color.WHITE : TEXT);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(primary ? ACCENT : CARD_ALT, 16,
                primary ? ACCENT : STROKE, 1));
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(topMarginDp), 0, 0);
        return params;
    }

    private static String backendName(DetectionResult result) {
        switch (result.getBackend()) {
            case UNITY_IL2CPP: return "Unity IL2CPP";
            case UNITY_MONO: return "Unity Mono";
            case UNITY_UNKNOWN: return "Unity";
            default: return "Não Unity";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
