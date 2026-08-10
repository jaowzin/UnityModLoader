package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
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
    private static final int PICK_PLUGIN = 2001;

    private LinearLayout gamesContainer;
    private LinearLayout actionsContainer;
    private TextView status;
    private ProgressBar progress;
    private Button rescanButton;
    private InstalledUnityGame selectedGame;
    private ModBackend selectedBackend;

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
        subtitle.setText("V0.3 — perfis, backends e plugins");
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

        actionsContainer = new LinearLayout(this);
        actionsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(actionsContainer);

        gamesContainer = new LinearLayout(this);
        gamesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesContainer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void scanInstalledGames() {
        selectedGame = null;
        selectedBackend = null;
        gamesContainer.removeAllViews();
        actionsContainer.removeAllViews();
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

        status.setText("Encontrados " + games.size() + " app(s) Unity. Toque em um para selecionar.");

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
        selectedBackend = BackendFactory.forGame(game);
        DetectionResult result = game.getDetection();

        boolean profileOk = GameProfileManager.prepare(this, game, selectedBackend);
        BackendStatus backendStatus = selectedBackend == null
                ? BackendStatus.blocked("Backend ainda não suportado.")
                : selectedBackend.prepare(game, GameProfileManager.root(this, game));

        List<File> plugins = PluginManager.list(this, game);

        StringBuilder paths = new StringBuilder();
        for (String path : game.getApkPaths()) {
            paths.append("\n• ").append(new File(path).getName());
        }

        status.setText(String.format(Locale.ROOT,
                "%s\n%s\n\n" +
                "Backend: %s\n" +
                "Estado: %s\n" +
                "Perfil local: %s\n" +
                "Plugins importados: %d\n\n" +
                "libunity.so: %s\n" +
                "libil2cpp.so: %s\n" +
                "global-metadata.dat: %s\n" +
                "DLLs Managed: %d\n" +
                "Arquiteturas: %s\n" +
                "APKs instalados: %d%s",
                game.getLabel(),
                game.getPackageName(),
                selectedBackend == null ? backendName(result) : selectedBackend.displayName(),
                backendStatus.getMessage(),
                profileOk ? "preparado" : "erro",
                plugins.size(),
                yesNo(result.hasLibUnity()),
                yesNo(result.hasLibIl2Cpp()),
                yesNo(result.hasGlobalMetadata()),
                result.getManagedDllCount(),
                result.getArchitectures().isEmpty() ? "não detectadas" : String.join(", ", result.getArchitectures()),
                game.getApkPaths().size(),
                paths
        ));

        renderActions();
    }

    private void renderActions() {
        actionsContainer.removeAllViews();
        if (selectedGame == null) return;

        Button prepare = new Button(this);
        prepare.setText("Preparar ambiente do jogo");
        prepare.setOnClickListener(v -> prepareSelectedGame());
        actionsContainer.addView(prepare);

        Button importPlugin = new Button(this);
        importPlugin.setText("Importar plugin / mod");
        importPlugin.setEnabled(selectedBackend != null);
        importPlugin.setOnClickListener(v -> choosePlugin());
        actionsContainer.addView(importPlugin);

        Button listPlugins = new Button(this);
        listPlugins.setText("Ver plugins importados");
        listPlugins.setOnClickListener(v -> showPlugins());
        actionsContainer.addView(listPlugins);

        Button launch = new Button(this);
        launch.setText("Abrir jogo");
        launch.setOnClickListener(v -> launchSelectedGame());
        actionsContainer.addView(launch);
    }

    private void prepareSelectedGame() {
        if (selectedGame == null) return;
        boolean ok = GameProfileManager.prepare(this, selectedGame, selectedBackend);
        if (!ok) {
            Toast.makeText(this, "Falha ao preparar ambiente", Toast.LENGTH_LONG).show();
            return;
        }

        BackendStatus result = selectedBackend == null
                ? BackendStatus.blocked("Backend ainda não suportado")
                : selectedBackend.prepare(selectedGame, GameProfileManager.root(this, selectedGame));

        Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
        showGame(selectedGame);
    }

    private void choosePlugin() {
        if (selectedGame == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_PLUGIN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PLUGIN || resultCode != RESULT_OK || data == null || selectedGame == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        try {
            File imported = PluginManager.importPlugin(this, selectedGame, uri, queryName(uri));
            Toast.makeText(this, "Importado: " + imported.getName(), Toast.LENGTH_SHORT).show();
            showGame(selectedGame);
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao importar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPlugins() {
        if (selectedGame == null) return;
        List<File> plugins = PluginManager.list(this, selectedGame);
        if (plugins.isEmpty()) {
            Toast.makeText(this, "Nenhum plugin importado para este jogo", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder text = new StringBuilder("Plugins de ").append(selectedGame.getLabel()).append(":");
        for (File plugin : plugins) {
            text.append("\n• ").append(plugin.getName());
        }
        status.append("\n\n" + text);
    }

    private void launchSelectedGame() {
        if (selectedGame == null) return;
        Intent launch = getPackageManager().getLaunchIntentForPackage(selectedGame.getPackageName());
        if (launch == null) {
            Toast.makeText(this, "O jogo não possui Activity de inicialização visível", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String value = cursor.getString(idx);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        }
        return "plugin.bin";
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
