package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private static final int REQUEST_MANUAL_OBB = 7191;

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
    private Button importObb;
    private Button importObbManual;
    private Button recheck;

    private InstalledUnityGame targetGame;
    private ModBackend targetBackend;
    private String installedVersion = "?";
    private ShizukuObbManager shizukuObbManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        setContentView(buildUi());

        shizukuObbManager = new ShizukuObbManager(this, (message, success) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (success) scanTarget();
            else if (details != null) details.append("\n" + message);
        });
        shizukuObbManager.attach();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        superKickSwitch.setChecked(prefs.getBoolean(KEY_SUPER_KICK, false));
        superSpeedSwitch.setChecked(prefs.getBoolean(KEY_SUPER_SPEED, false));
        superKickSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_SUPER_KICK, checked).apply());
        superSpeedSwitch.setOnCheckedChangeListener((buttonView, checked) ->
                prefs.edit().putBoolean(KEY_SUPER_SPEED, checked).apply());

        scanTarget();
    }

    @Override
    protected void onDestroy() {
        if (shizukuObbManager != null) shizukuObbManager.detach();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(30));
        root.setBackgroundColor(BG);
        scroll.addView(root);

        TextView overline = text("MAMO BALL // CTF MOD CORE", 12f, ACCENT, Typeface.BOLD);
        overline.setLetterSpacing(0.14f);
        root.addView(overline);

        TextView title = text("Match Lab", 32f, TEXT, Typeface.BOLD);
        title.setPadding(0, dp(4), 0, 0);
        root.addView(title);

        TextView subtitle = text("Loader IL2CPP dedicado • v0.7.9 Shizuku OBB", 14f, MUTED, Typeface.NORMAL);
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
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.setMargins(0, dp(18), 0, dp(2));
        targetCard.addView(progress, progressParams);

        details = text("Validando Mamo Ball 4.6.15 e mirror OBB…", 13f, MUTED, Typeface.NORMAL);
        details.setLineSpacing(0f, 1.18f);
        details.setPadding(0, dp(14), 0, 0);
        targetCard.addView(details);

        importObb = button("IMPORTAR OBB COM SHIZUKU", false);
        importObb.setEnabled(false);
        importObb.setOnClickListener(v -> importWithShizuku());
        LinearLayout.LayoutParams importParams = matchWrap(dp(14));
        importParams.height = dp(50);
        root.addView(importObb, importParams);

        importObbManual = button("Selecionar OBB manualmente", false);
        importObbManual.setEnabled(false);
        importObbManual.setOnClickListener(v -> chooseManualObb());
        LinearLayout.LayoutParams manualParams = matchWrap(dp(8));
        manualParams.height = dp(46);
        root.addView(importObbManual, manualParams);

        TextView modsHeader = text("MODS AUTORIZADOS DO CTF", 12f, MUTED, Typeface.BOLD);
        modsHeader.setLetterSpacing(0.11f);
        modsHeader.setPadding(0, dp(24), 0, dp(10));
        root.addView(modsHeader);

        LinearLayout modsCard = card();
        modsCard.setOrientation(LinearLayout.VERTICAL);
        modsCard.setBackground(roundRect(CARD_ALT, 22, STROKE, 1));
        root.addView(modsCard);

        superKickSwitch = addModRow(
                modsCard,
                "Super Chute ×2",
                "Duplica a força entregue ao BallController.Kick.",
                "BallController.Kick • RVA 0x2C9AC24",
                false);

        superSpeedSwitch = addModRow(
                modsCard,
                "Super Velocidade ×2",
                "Duplica playerSpeed e sprintSpeed após a leitura do valor do jogo.",
                "PlayerController.ApplyJoystickState • RVA 0x2CCAFC4",
                true);

        TextView safety = text(
                "Fluxo original preservado: sem guest-force, sem skip-auth e sem spoof de assinatura. Shizuku e usado somente para ler o OBB do Mamo e copiar para o mirror do loader.",
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

        recheck = button("Revalidar APK + OBB", false);
        recheck.setOnClickListener(v -> scanTarget());
        LinearLayout.LayoutParams recheckParams = matchWrap(dp(10));
        recheckParams.height = dp(46);
        root.addView(recheck, recheckParams);

        TextView footer = text("Unity 6000.0.59f2 • IL2CPP • arm64-v8a • alvo CTF 4.6.15",
                11f, Color.rgb(96, 111, 132), Typeface.NORMAL);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, 0);
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

        copy.addView(text(titleValue, 18f, TEXT, Typeface.BOLD));
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
        details.setText("Validando Mamo Ball instalado e mirror OBB…");
        launchModded.setEnabled(false);
        launchClean.setEnabled(false);
        importObb.setEnabled(false);
        importObbManual.setEnabled(false);
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
        } catch (Throwable ignored) {
        }

        boolean profileOk = GameProfileManager.prepare(this, targetGame, targetBackend);
        boolean backendOk = detection.getBackend() == DetectionResult.Backend.UNITY_IL2CPP
                && detection.hasLibIl2Cpp()
                && detection.getArchitectures().contains("arm64-v8a");
        boolean versionOk = SUPPORTED_VERSION.equals(installedVersion);
        boolean supported = backendOk && versionOk;
        boolean obbReady = hasImportedObb();
        String obbStatus = inspectImportedObb();
        String shizukuStatus = shizukuObbManager == null
                ? "Shizuku: inicializando"
                : shizukuObbManager.shortStatus();

        if (!supported) {
            statusBadge.setText(versionOk ? "UNSUPPORTED" : "VERSION");
        } else if (!obbReady) {
            statusBadge.setText("NEED OBB");
        } else {
            statusBadge.setText("READY");
        }
        boolean ready = supported && obbReady;
        statusBadge.setTextColor(ready ? GREEN : Color.rgb(255, 184, 105));
        statusBadge.setBackground(roundRect(
                ready ? Color.rgb(17, 50, 42) : Color.rgb(58, 43, 24),
                999,
                ready ? Color.rgb(30, 93, 75) : Color.rgb(112, 78, 36),
                1));

        details.setText(String.format(Locale.ROOT,
                "Versão instalada: %s%s\nBackend: %s\nArquitetura: %s\nAPKs: %d\n%s\n%s\nPerfil: %s\nCore: %s",
                installedVersion,
                versionOk ? " ✓" : " • esperado " + SUPPORTED_VERSION,
                backendName(detection),
                detection.getArchitectures().isEmpty()
                        ? "não detectada"
                        : String.join(", ", detection.getArchitectures()),
                targetGame.getApkPaths().size(),
                obbStatus,
                shizukuStatus,
                profileOk ? "pronto" : "erro",
                NativeBridge.coreVersion()));

        launchModded.setEnabled(ready && profileOk);
        launchClean.setEnabled(true);
        importObb.setEnabled(supported);
        importObbManual.setEnabled(supported);
    }

    private File importedObbDir() {
        return getApplicationContext().getObbDir();
    }

    private boolean hasImportedObb() {
        File dir = importedObbDir();
        if (dir == null) return false;
        File[] files = dir.listFiles((parent, name) -> name != null && name.endsWith(".obb"));
        return files != null && files.length > 0;
    }

    private String inspectImportedObb() {
        try {
            File dir = importedObbDir();
            if (dir == null) return "OBB mirror: diretório indisponível";
            File[] files = dir.listFiles((parent, name) -> name != null && name.endsWith(".obb"));
            if (files == null || files.length == 0) {
                return "OBB mirror: vazio\n" + dir.getAbsolutePath();
            }
            long total = 0L;
            for (File file : files) total += Math.max(0L, file.length());
            return "OBB mirror: " + files.length + " arquivo(s), "
                    + String.format(Locale.ROOT, "%.1f MB", total / 1048576.0)
                    + "\n" + dir.getAbsolutePath();
        } catch (Throwable error) {
            return "OBB mirror: erro (" + error.getClass().getSimpleName() + ")";
        }
    }

    private void importWithShizuku() {
        if (shizukuObbManager == null) {
            Toast.makeText(this, "Shizuku ainda não inicializou", Toast.LENGTH_LONG).show();
            return;
        }
        shizukuObbManager.importMamoObb();
    }

    private void chooseManualObb() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_MANUAL_OBB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MANUAL_OBB || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        importManualObb(uri);
    }

    private void importManualObb(Uri uri) {
        final String fileName = queryDisplayName(uri);
        if (!safeObbName(fileName)) {
            Toast.makeText(this, "Selecione um arquivo .obb válido", Toast.LENGTH_LONG).show();
            return;
        }

        progress.setVisibility(View.VISIBLE);
        details.append("\nImportando manualmente " + fileName + "…");
        new Thread(() -> {
            File dir = importedObbDir();
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Não foi possível criar o mirror OBB", Toast.LENGTH_LONG).show();
                });
                return;
            }

            File temp = new File(dir, fileName + ".part");
            File destination = new File(dir, fileName);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(temp, false)) {
                if (input == null) throw new IllegalStateException("stream nulo");
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();

                if (destination.exists() && !destination.delete()) {
                    throw new IllegalStateException("não foi possível substituir OBB antigo");
                }
                if (!temp.renameTo(destination)) {
                    throw new IllegalStateException("rename final falhou");
                }

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "OBB importado para o mirror do loader", Toast.LENGTH_LONG).show();
                    scanTarget();
                });
            } catch (Throwable error) {
                temp.delete();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this,
                            "Falha ao importar OBB: " + error.getClass().getSimpleName()
                                    + ": " + String.valueOf(error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "mamoball-manual-obb").start();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        if (last != null && last.endsWith(".obb")) return new File(last).getName();
        return "";
    }

    private static boolean safeObbName(String value) {
        return value != null
                && value.endsWith(".obb")
                && !value.contains("/")
                && !value.contains("\\")
                && !value.contains("..")
                && !value.isEmpty();
    }

    private void launchHosted() {
        if (targetGame == null) return;
        if (!SUPPORTED_VERSION.equals(installedVersion)) {
            Toast.makeText(this,
                    "Esta build aceita somente Mamo Ball " + SUPPORTED_VERSION,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasImportedObb()) {
            Toast.makeText(this, "Importe o OBB antes de iniciar o hosted mode", Toast.LENGTH_LONG).show();
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

        startPatchWatcher("Kick", superKick, true);
        startPatchWatcher("Speed", superSpeed, false);

        Intent host = new Intent(this, GameHostActivity.class);
        host.putExtra(GameHostActivity.EXTRA_TARGET_PACKAGE, TARGET_PACKAGE);
        startActivity(host);
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
