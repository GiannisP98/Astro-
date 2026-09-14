package com.bridgeastro.mobile;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

final class AndroidBridge {
    private final MainActivity activity;
    AndroidBridge(MainActivity activity) { this.activity = activity; }

    @JavascriptInterface public void saveBase64(String filename, String mime, String base64) {
        activity.runOnUiThread(() -> {
            try {
                byte[] data = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
                String safe = safeName(filename);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                    cv.put(MediaStore.Downloads.MIME_TYPE, (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BridgeAstro");
                    Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("Android Downloads provider returned no destination.");
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                        if (out == null) throw new Exception("Cannot open Android download destination.");
                        out.write(data);
                    }
                    Toast.makeText(activity, "Saved to Downloads/BridgeAstro/" + safe, Toast.LENGTH_LONG).show();
                } else {
                    File dir = new File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "BridgeAstro");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create export directory.");
                    File f = new File(dir, safe);
                    try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
                    Toast.makeText(activity, "Saved: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                }
            } catch (Exception e) {
                Toast.makeText(activity, "ASTRO export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @JavascriptInterface public void openMobileSettings() {
        activity.runOnUiThread(activity::openSettingsFromBridge);
    }

    private static String safeName(String in) {
        String s = in == null ? "BridgeAstro_export.bin" : in.trim();
        if (s.isEmpty()) s = "BridgeAstro_export.bin";
        s = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return s.length() > 120 ? s.substring(0, 120) : s;
    }
}
