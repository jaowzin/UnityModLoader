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

/** Dedicated launcher for the authorized Mamo Ball CTF target. */
public final class MainActivity extends Activity {
    private static final String TAG = "MamoBall.ModCore";
    private static final String TARGET_PACKAGE = InstalledUnityScanner.TARGET_PACKAGE;
    private static final String SUPPORTED_VERSION = "4.6.15";
    private static final String PREFS = "mamoball_mod_prefs";
    private static final String KEY_SUPER_KICK = "super_kick";
    private static final String KEY_SUPER_SPEED = "super_speed";

    private static final int BG = Color.rgb(7, 10, 17);
    private static final int CARD = Color.rgb(16, 23, 35);
    private static final int CARD_ALT = Color.rgb(21, 30, 45);
    private static final int TEXT = Color.rgb(244, 248, 255);
    private static final int MUTED = Color.rgb(144, 159, 180);
    private static final int ACCENT = Color.rgb(57, 189, 248);
    private static final int GREEN = Color.rgb(58, 218, 157);
    private static final int STROKE = Color.rgb(41, 55, 76);

    private ImageView gameIcon;
    private TextView gameName;
    private TextView statusBadge;
    private TextView details;
    private ProgressBar progress;
    private Switch superKickSwitch;
    private Switch superSpeedSwitch;
    private Button launchModded;
    private Button launchClean;
    private Button recheck;

    private InstalledUnityGame targetGame;
    private ModBackend targetBackend;
    private String installedVersion = "?";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);

        setContentView(buildUi());

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        superKickSwitch.setChecked(prefs.getBoolean(KEY_SUPER_KICK, true));
        superSpeedSwitch.setChecked(prefs.getBoolean(KEY_SUPER_SPEED, true));
        superKickSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_SUPER_KICK, checked).apply());
        superSpeedSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_SUPER_SPEED, checked).apply());

        scanTarget();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(30));
        root.setBackgroundColor(BG);
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView overline = text("MAMO BALL // CTF MOD CORE", 12f, ACCENT, Typeface.BOLD);
        overline.setLetterSpacing(0.14f);
        root.addView(overline);

        TextView title = text("Match Lab", 32f, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);

        TextView subtitle = text("Loader IL2CPP dedicado • v0.7.2", 14f, MUTED, Typeface.NORMAL);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        LinearLayout targetCard = card();
        targetCard.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetCard, matchWrap(dp(12)));

        LinearLayout gameTop = new LinearLayout(this);
        gameTop.setOrientation(LinearLayout.HORIZONTAL);
        gameTop.setGravity(Gravity.CENTER_VERTICAL);
        targetCard.addView(gameTop);

        gameIcon = new ImageView(this);
        gameIcon.setBackground(roundRect(CARD_ALT, 18, STROKE, 1));
        gameIcon.setPadding(dp(8), dp(8), dp(8), dp(8));
        gameTop.addView(gameIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout gameText = new LinearLayout(this);
        gameText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gameTextParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gameTextParams.setMargins(dp(14), 0, dp(8), 0);
        gameTop.addView(gameText, gameTextParams);

        gameName = text("Mamo Ball", 22f, TEXT, Typeface.BOLD);
        gameText.addView(gameName);

        TextView packageLine = text(TARGET_PACKAGE, 12f, MUTED, Typeface.NORMAL);
        packageLine.setPadding(0, dp(4), 0, 0);
        gameText.addView(packageLine);

        statusBadge = text("CHECKING", 11f, MUTED, Typeface.BOLD);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(dp(10), dp(6), dp(10), dp(6));
        statusBadge.setBackground(roundRect(Color.rgb(27, 36, 51), 999, STROKE, 1));
        gameTop.addView(statusBadge);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(18), 0, dp(2));
        targetCard.addView(progress, progressParams);

        details = text("Validando Mamo Ball 4.6.15…", 13f, MUTED, Typeface.NORMAL);
        details.setLineSpacing(0f, 1.18f);
        details.setPadding(0, dp(14), 0, 0);
        targetCard.addView(details);

        TextView modsHeader = text("MODS AUTORIZADOS DO CTF", 12f, MUTED, Typeface.BOLD);
        modsHeader.setLetterSpacing(0.11f);
        modsHeader.setPadding(0, dp(24), 0, dp(10));
        root.addView(modsHeader);

        LinearLayout modsCard = card();
        modsCard.setOrientation(LinearLayout.VERTICAL);
        modsCard.setBackground(roundRect(CARD_ALT, 22, STROKE, 1));
        root.addView(modsCard, matchWrap(0));

        superKickSwitch = addModRow(
                modsCard,
                "Super Chute ×2",
                "Duplica a força entregue ao BallController.Kick sem alterar a direção do chute.",
                "BallController.Kick • RVA 0x2C9AC24",
                false);

        superSpeedSwitch = addModRow(
                modsCard,
                "Super Velocidade ×2",
                "Duplica playerSpeed e sprintSpeed depois da leitura ObscuredFloat.",
                "PlayerController.ApplyJoystickState • RVA 0x2CCAFC4",
                true);

        TextView safety = text(
                "Bootstrap CTF: se o loader não tiver o token privado do app original, o LoginFragment entra automaticamente como Guest. As assinaturas ARM64 são verificadas antes dos patches.",
                12f, MUTED, Typeface.NORMAL);
        safety.setPadding(0, dp(18), 0, 0);
        root.addView(safety);

        launchModded = button("INICIAR COM MODS", true);
        launchModded.setEnabled(false);
        launchModded.setOnClickListener(v -> launchHosted());
        LinearLayout.LayoutParams launchParams = matchWrap(dp(20));
        launchParams.height = dp(58);
        root.addView(launchModded, launchParams);

        launchClean = button("Abrir Mamo Ball original", false);
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

        TextView footer = text("Unity 6000.0.59f2 • IL2CPP • arm64-v8a • alvo CTF 4.6.15",
                11f, Color.rgb(96, 111, 132), Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer);

        return scroll;
    }

    private Switch addModRow(LinearLayout card, String titleValue, String description,
                             String signature, boolean separated) {
        if (separated) {
            View divider = new View(this);
            divider.setBackgroundColor(STROKE);
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            dividerParams.setMargins(0, dp(18), 0, dp(18));
            card.addView(divider, dividerParams);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text(titleValue, 18f, TEXT, Typeface.BOLD);
        copy.addView(title);

        TextView desc = text(description, 13f, MUTED, Typeface.NORMAL);
        desc.setPadding(0, dp(4), dp(12), 0);
        copy.addView(desc);

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        row.addView(toggle);

        TextView sig = text("Patch verificado • " + signature, 11f, MUTED, Typeface.NORMAL);
        sig.setPadding(0, dp(12), 0, 0);
        card.addView(sig);
        return toggle;
    }

    private void scanTarget() {
        targetGame = null;
        targetBackend = null;
        installedVersion = "?";
        progress.setVisibility(View.VISIBLE);
        statusBadge.setText("CHECKING");
        statusBadge.setTextColor(MUTED);
        statusBadge.setBackground(roundRect(Color.rgb(27, 36, 51), 999, STROKE, 1));
        details.setText("Validando Mamo Ball instalado…");
        launchModded.setEnabled(false);
        launchClean.setEnabled(false);
        recheck.setEnabled(false);

        new Thread(() -> {
            List<InstalledUnityGame> result = InstalledUnityScanner.scan(this);
            runOnUiThread(() -> renderTarget(result));
        }, "mamoball-target-scan").start();
    }

    private void renderTarget(List<InstalledUnityGame> result) {
        progress.setVisibility(View.GONE);
        recheck.setEnabled(true);

        if (result.isEmpty()) {
            statusBadge.setText("NOT FOUND");
            statusBadge.setTextColor(Color.rgb(255, 125, 125));
            statusBadge.setBackground(roundRect(Color.rgb(54, 27, 32), 999,
                    Color.rgb(98, 43, 51), 1));
            details.setText("Mamo Ball não foi encontrado ou a instalação não parece Unity.\n"
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

        try {
            @SuppressWarnings("deprecation")
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (info.versionName != null) installedVersion = info.versionName;
        } catch (Throwable ignored) {}

        boolean profileOk = GameProfileManager.prepare(this, targetGame, targetBackend);
        boolean backendOk = detection.getBackend() == DetectionResult.Backend.UNITY_IL2CPP
                && detection.hasLibIl2Cpp()
                && detection.getArchitectures().contains("arm64-v8a");
        boolean versionOk = SUPPORTED_VERSION.equals(installedVersion);
        boolean supported = backendOk && versionOk;

        statusBadge.setText(supported ? "READY" : versionOk ? "UNSUPPORTED" : "VERSION");
        statusBadge.setTextColor(supported ? GREEN : Color.rgb(255, 184, 105));
        statusBadge.setBackground(roundRect(
                supported ? Color.rgb(17, 50, 42) : Color.rgb(58, 43, 24),
                999,
                supported ? Color.rgb(30, 93, 75) : Color.rgb(112, 78, 36),
                1));

        details.setText(String.format(Locale.ROOT,
                "Versão instalada: %s%s\nBackend: %s\nArquitetura: %s\nAPKs: %d\nPerfil do loader: %s\nCore: %s",
                installedVersion,
                versionOk ? " ✓" : " • esperado " + SUPPORTED_VERSION,
                backendName(detection),
                detection.getArchitectures().isEmpty()
                        ? "não detectada"
                        : String.join(", ", detection.getArchitectures()),
                targetGame.getApkPaths().size(),
                profileOk ? "pronto" : "erro",
                NativeBridge.coreVersion()));

        launchModded.setEnabled(supported && profileOk);
        launchClean.setEnabled(true);
    }

    private void launchHosted() {
        if (targetGame == null) return;
        if (!SUPPORTED_VERSION.equals(installedVersion)) {
            Toast.makeText(this,
                    "Esta build aceita somente Mamo Ball " + SUPPORTED_VERSION,
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean superKick = superKickSwitch.isChecked();
        boolean superSpeed = superSpeedSwitch.isChecked();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SUPER_KICK, superKick)
                .putBoolean(KEY_SUPER_SPEED, superSpeed)
                .apply();

        if (!GameProfileManager.prepare(this, targetGame, targetBackend)) {
            Toast.makeText(this, "Falha ao preparar perfil", Toast.LENGTH_LONG).show();
            return;
        }

        // Must be armed before the hosted Unity reaches SplashActivity/LoginFragment.
        startGuestBootstrapWatcher();
        startPatchWatcher("Kick", superKick, true);
        startPatchWatcher("Speed", superSpeed, false);

        Intent host = new Intent(this, GameHostActivity.class);
        host.putExtra(GameHostActivity.EXTRA_TARGET_PACKAGE, TARGET_PACKAGE);
        startActivity(host);
    }

    private void startGuestBootstrapWatcher() {
        new Thread(() -> {
            String last = "";
            for (int attempt = 0; attempt < 120; attempt++) {
                try {
                    last = NativeBridge.setMamoBallGuestBootstrap(true);
                    Log.i(TAG, "Guest bootstrap: " + last);
                    if (last.startsWith("OK:") || last.startsWith("ERROR:")) {
                        final String result = last;
                        if (result.startsWith("ERROR:")) {
                            runOnUiThread(() -> Toast.makeText(
                                    this, result, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                    Thread.sleep(80L);
                } catch (Throwable error) {
                    Log.e(TAG, "Guest bootstrap watcher failed", error);
                    return;
                }
            }

            final String result = last.isEmpty()
                    ? "ERROR: bootstrap não encontrou libil2cpp a tempo"
                    : last;
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        }, "mamoball-guest-bootstrap").start();
    }

    private void startPatchWatcher(String label, boolean enabled, boolean kick) {
        new Thread(() -> {
            String last = "";
            for (int attempt = 0; attempt < 100; attempt++) {
                try {
                    last = kick
                            ? NativeBridge.setMamoBallSuperKick(enabled)
                            : NativeBridge.setMamoBallSuperSpeed(enabled);
                    Log.i(TAG, label + " patch: " + last);
                    if (last.startsWith("OK:") || last.startsWith("ERROR:")) {
                        final String result = last;
                        if (result.startsWith("ERROR:")) {
                            runOnUiThread(() -> Toast.makeText(
                                    this, result, Toast.LENGTH_LONG).show());
                        }
                        return;
                    }
                    Thread.sleep(120L);
                } catch (Throwable error) {
                    Log.e(TAG, label + " patch watcher failed", error);
                    return;
                }
            }

            final String result = last.isEmpty()
                    ? "ERROR: libil2cpp não ficou pronto a tempo"
                    : last;
            runOnUiThread(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
        }, kick ? "mamoball-kick-patch" : "mamoball-speed-patch").start();
    }

    private void launchClean() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        if (launch == null) {
            Toast.makeText(this, "Mamo Ball não possui launcher visível", Toast.LENGTH_LONG).show();
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
        button.setTextColor(primary ? Color.rgb(6, 18, 27) : TEXT);
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
