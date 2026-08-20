package dev.unitymodloader.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;

import rikka.shizuku.Shizuku;

final class ShizukuObbManager {
    interface Listener {
        void onObbStatus(String message, boolean success);
    }

    private static final String TAG = "UML.ShizukuOBB";
    private static final int REQUEST_PERMISSION = 7190;

    private final Activity activity;
    private final Listener listener;
    private IObbBridgeService service;
    private boolean pendingImport;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_PERMISSION) return;
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindAndImport();
                } else {
                    pendingImport = false;
                    report("Permissao do Shizuku negada.", false);
                }
            };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IObbBridgeService.Stub.asInterface(binder);
            Log.i(TAG, "UserService connected: " + name);
            if (pendingImport) importNow();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            Log.w(TAG, "UserService disconnected: " + name);
        }
    };

    ShizukuObbManager(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    void attach() {
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    void detach() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
    }

    boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    String shortStatus() {
        try {
            if (!Shizuku.pingBinder()) return "Shizuku: parado/nao encontrado";
            if (Shizuku.isPreV11()) return "Shizuku: versao antiga";
            boolean granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            return "Shizuku: ativo uid=" + Shizuku.getUid()
                    + (granted ? " • autorizado" : " • sem permissao");
        } catch (Throwable error) {
            return "Shizuku: indisponivel";
        }
    }

    void importMamoObb() {
        pendingImport = true;
        try {
            if (!Shizuku.pingBinder()) {
                pendingImport = false;
                report("Shizuku nao esta ativo. Inicie o Shizuku e tente novamente.", false);
                return;
            }
            if (Shizuku.isPreV11()) {
                pendingImport = false;
                report("Esta versao do Shizuku e antiga demais para UserService.", false);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                report("Solicitando permissao do Shizuku…", false);
                Shizuku.requestPermission(REQUEST_PERMISSION);
                return;
            }
            bindAndImport();
        } catch (Throwable error) {
            pendingImport = false;
            report("Falha ao acessar Shizuku: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()), false);
        }
    }

    private Shizuku.UserServiceArgs userServiceArgs() {
        return new Shizuku.UserServiceArgs(
                new ComponentName(activity, ShizukuObbService.class)
        ).daemon(false)
                .processNameSuffix("mamo_obb")
                .tag("mamoball-obb")
                .version(1);
    }

    private void bindAndImport() {
        if (service != null) {
            importNow();
            return;
        }
        try {
            report("Abrindo ponte Shizuku para o OBB…", false);
            Shizuku.bindUserService(userServiceArgs(), serviceConnection);
        } catch (Throwable error) {
            pendingImport = false;
            report("Falha ao iniciar UserService: " + error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()), false);
        }
    }

    private void importNow() {
        pendingImport = false;
        final IObbBridgeService active = service;
        if (active == null) {
            report("Ponte Shizuku desconectada.", false);
            return;
        }

        report("Lendo Android/obb/com.alberun.mamoball via Shizuku…", false);
        new Thread(() -> {
            try {
                String listing = active.listMamoObb();
                if (listing == null || listing.isEmpty()) {
                    report("Shizuku nao encontrou arquivos OBB.", false);
                    return;
                }
                if (listing.startsWith("ERROR\t") || listing.startsWith("EMPTY\t")) {
                    report("OBB: " + listing.substring(listing.indexOf('\t') + 1), false);
                    return;
                }

                File mirror = activity.getApplicationContext().getObbDir();
                if (mirror == null) {
                    report("Android nao retornou o diretorio OBB do loader.", false);
                    return;
                }
                if (!mirror.isDirectory() && !mirror.mkdirs()) {
                    report("Nao foi possivel criar " + mirror.getAbsolutePath(), false);
                    return;
                }

                long totalCopied = 0L;
                int copiedFiles = 0;
                String[] rows = listing.split("\\n");
                for (String row : rows) {
                    String[] columns = row.split("\\t", 2);
                    if (columns.length == 0) continue;
                    String name = columns[0];
                    if (!safeObbName(name)) continue;

                    report("Importando " + name + "…", false);
                    File temp = new File(mirror, name + ".part");
                    File destination = new File(mirror, name);
                    if (temp.exists()) temp.delete();

                    String result;
                    try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                            temp,
                            ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE
                                    | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                        result = active.copyMamoObb(name, pfd);
                    }

                    if (result == null || !result.startsWith("OK\t")) {
                        temp.delete();
                        report("Falha ao copiar " + name + ": " + result, false);
                        return;
                    }

                    if (destination.exists() && !destination.delete()) {
                        temp.delete();
                        report("Nao foi possivel substituir " + destination.getName(), false);
                        return;
                    }
                    if (!temp.renameTo(destination)) {
                        temp.delete();
                        report("Falha ao finalizar " + destination.getName(), false);
                        return;
                    }

                    totalCopied += destination.length();
                    copiedFiles++;
                }

                if (copiedFiles == 0) {
                    report("Nenhum OBB valido foi importado.", false);
                    return;
                }

                report("OBB importado: " + copiedFiles + " arquivo(s), "
                        + String.format(java.util.Locale.ROOT, "%.1f MB", totalCopied / 1048576.0)
                        + "\nMirror: " + mirror.getAbsolutePath(), true);
            } catch (Throwable error) {
                report("Erro ao importar OBB: " + error.getClass().getSimpleName()
                        + ": " + String.valueOf(error.getMessage()), false);
            }
        }, "mamoball-shizuku-obb").start();
    }

    private void report(String message, boolean success) {
        Log.i(TAG, message);
        activity.runOnUiThread(() -> listener.onObbStatus(message, success));
    }

    private static boolean safeObbName(String value) {
        return value != null
                && value.endsWith(".obb")
                && !value.contains("/")
                && !value.contains("\\")
                && !value.contains("..")
                && !value.isEmpty();
    }
}
