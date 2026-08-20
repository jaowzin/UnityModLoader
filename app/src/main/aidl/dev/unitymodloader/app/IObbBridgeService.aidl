package dev.unitymodloader.app;

import android.os.ParcelFileDescriptor;

interface IObbBridgeService {
    String listMamoObb();
    String copyMamoObb(String fileName, in ParcelFileDescriptor destination);
    String readLoaderCrashLog(int targetPid, long crashTimestampMs);
}
