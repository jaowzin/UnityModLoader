package dev.unitymodloader.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int PICK_APK = 1001;

    private TextView status;
    private Button prepareButton;
    private DetectionResult lastResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
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
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(this);
        subtitle.setText("V0.1 — detector Unity IL2CPP / Mono");
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setPadding(0, dp(8), 0, dp(20));
        root.addView(subtitle);

        Button pick = new Button(this);
        pick.setText("Selecionar APK Unity");
        pick.setOnClickListener(v -> chooseApk());
        root.addView(pick);

        prepareButton = new Button(this);
        prepareButton.setText("Preparar pasta de mods");
        prepareButton.setEnabled(false);
        prepareButton.setOnClickListener(v -> prepareModsDirectory());
        root.addView(prepareButton);

        status = new TextView(this);
        status.setText("Core nativo: " + NativeBridge.coreVersion() +
                "\n\nSelecione um APK para analisar.");
        status.setTextSize(16f);
        status.setPadding(0, dp(20), 0, dp(20));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void chooseApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) return;

        try {
            String name = queryName(uri);
            File selectedApk = new File(getCacheDir(), "selected-game.apk");
            copyUriToFile(uri, selectedApk);
            lastResult = UnityApkDetector.inspect(selectedApk);
            renderResult(name, lastResult);
            prepareButton.setEnabled(lastResult.isUnity());
        } catch (Exception e) {
            prepareButton.setEnabled(false);
            status.setText("Erro ao analisar APK:\n" + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void renderResult(String fileName, DetectionResult result) {
        String backend;
        switch (result.getBackend()) {
            case UNITY_IL2CPP: backend = "Unity IL2CPP"; break;
            case UNITY_MONO: backend = "Unity Mono"; break;
            case UNITY_UNKNOWN: backend = "Unity (backend ainda não identificado)"; break;
            default: backend = "Não parece ser Unity";
        }

        status.setText(String.format(Locale.ROOT,
                "Arquivo: %s\n" +
                "Resultado: %s\n\n" +
                "libunity.so: %s\n" +
                "libil2cpp.so: %s\n" +
                "global-metadata.dat: %s\n" +
                "DLLs Managed: %d\n" +
                "Arquiteturas: %s\n\n" +
                "Próximo backend planejado: carregamento modular IL2CPP/Mono.",
                fileName,
                backend,
                yesNo(result.hasLibUnity()),
                yesNo(result.hasLibIl2Cpp()),
                yesNo(result.hasGlobalMetadata()),
                result.getManagedDllCount(),
                result.getArchitectures().isEmpty() ? "não detectadas" : String.join(", ", result.getArchitectures())
        ));
    }

    private void prepareModsDirectory() {
        if (lastResult == null || !lastResult.isUnity()) return;

        File base = new File(getExternalFilesDir(null), "mods/selected-game");
        File plugins = new File(base, "plugins");
        File config = new File(base, "config");

        boolean ok = plugins.mkdirs() | plugins.isDirectory();
        ok &= config.mkdirs() | config.isDirectory();

        if (ok) {
            Toast.makeText(this, "Pastas de mods preparadas", Toast.LENGTH_SHORT).show();
            status.append("\n\nMods: " + base.getAbsolutePath());
        } else {
            Toast.makeText(this, "Não foi possível criar as pastas", Toast.LENGTH_LONG).show();
        }
    }

    private void copyUriToFile(Uri uri, File out) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new IllegalStateException("Não foi possível abrir o APK");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
        }
    }

    private String queryName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        }
        return "game.apk";
    }

    private static String yesNo(boolean value) {
        return value ? "sim" : "não";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
