package dev.unitymodloader.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private LinearLayout gamesContainer;
    private TextView status;
    private ProgressBar progress;
    private Button rescanButton;
    private InstalledUnityGame selectedGame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        scanInstalledGames();
    }

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Unity Mod Loader");
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("V0.2 — jogos Unity instalados");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(8), 0, dp(16));
        root.addView(subtitle);

        rescanButton = new Button(this);
        rescanButton.setText("Procurar jogos Unity instalados");
        rescanButton.setOnClickListener(v -> scanInstalledGames());
        root.addView(rescanButton);

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        status = new TextView(this);
        status.setText("Core nativo: " + NativeBridge.coreVersion());
        status.setTextSize(15f);
        status.setPadding(0, dp(16), 0, dp(10));
        root.addView(status);

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesContainer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void scanInstalledGames() {
        selectedGame = null;
        gamesContainer.removeAllViews();
        progress.setVisibility(View.VISIBLE);
        rescanButton.setEnabled(false);
        status.setText("Analisando apps instalados, incluindo APK base e split APKs...");

        new Thread(() -> {
            List<InstalledUnityGame> games = InstalledUnityScanner.scan(this);
            runOnUiThread(() -> renderGames(games));
        }, "unity-app-scanner").start();
    }

    private void renderGames(List<InstalledUnityGame> games) {
        progress.setVisibility(View.GONE);
        rescanButton.setEnabled(true);

        if (games.isEmpty()) {
            status.setText("Nenhum aplicativo Unity visível foi encontrado.");
            return;
        }

        status.setText("Encontrados " + games.size() + " app(s) Unity. Toque em um para ver os detalhes.");

        for (InstalledUnityGame game : games) {
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText(game.getLabel() + "\n" + game.getPackageName() + " • " + backendName(game.getDetection()));
            item.setOnClickListener(v -> showGame(game));
            gamesContainer.addView(item);
        }
    }

    private void showGame(InstalledUnityGame game) {
        selectedGame = game;
        DetectionResult result = game.getDetection();

        StringBuilder paths = new StringBuilder();
        for (String path : game.getApkPaths()) {
            paths.append("\n• ").append(new File(path).getName());
        }

        status.setText(String.format(Locale.ROOT,
                "%s\n%s\n\n" +
                "Backend: %s\n" +
                "libunity.so: %s\n" +
                "libil2cpp.so: %s\n" +
                "global-metadata.dat: %s\n" +
                "DLLs Managed: %d\n" +
                "Arquiteturas: %s\n" +
                "APKs instalados: %d%s",
                game.getLabel(),
                game.getPackageName(),
                backendName(result),
                yesNo(result.hasLibUnity()),
                yesNo(result.hasLibIl2Cpp()),
                yesNo(result.hasGlobalMetadata()),
                result.getManagedDllCount(),
                result.getArchitectures().isEmpty() ? "não detectadas" : String.join(", ", result.getArchitectures()),
                game.getApkPaths().size(),
                paths
        ));

        prepareModsDirectory(game);
    }

    private void prepareModsDirectory(InstalledUnityGame game) {
        File base = new File(getExternalFilesDir(null), "mods/" + game.getPackageName());
        File plugins = new File(base, "plugins");
        File config = new File(base, "config");

        boolean ok = (plugins.mkdirs() || plugins.isDirectory()) &&
                     (config.mkdirs() || config.isDirectory());

        if (!ok) {
            Toast.makeText(this, "Não foi possível preparar a pasta do jogo", Toast.LENGTH_SHORT).show();
        }
    }

    private static String backendName(DetectionResult result) {
        switch (result.getBackend()) {
            case UNITY_IL2CPP: return "Unity IL2CPP";
            case UNITY_MONO: return "Unity Mono";
            case UNITY_UNKNOWN: return "Unity";
            default: return "Não Unity";
        }
    }

    private static String yesNo(boolean value) {
        return value ? "sim" : "não";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
