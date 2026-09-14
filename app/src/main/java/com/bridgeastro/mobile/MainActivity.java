package com.bridgeastro.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_FILE_CHOOSER = 9001;
    private static final int REQ_IMPORT_ENGINE = 9002;
    private static final int REQ_IMPORT_CACHE = 9003;
    private static final String ENGINE_FILE = "astro_engine.html";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private TokenStore tokenStore;
    private MobileUpdater updater;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokenStore = new TokenStore(this);
        updater = new MobileUpdater(this, tokenStore);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button menu = new Button(this);
        menu.setText("⋮"); menu.setTextSize(23); menu.setTextColor(Color.WHITE);
        menu.setBackgroundColor(Color.argb(220, 7, 19, 29)); menu.setPadding(0,0,0,4);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(48), dp(48));
        mp.gravity = Gravity.TOP | Gravity.END; mp.setMargins(0, dp(10), dp(10), 0);
        root.addView(menu, mp); menu.setOnClickListener(v -> showMobileMenu());
        setContentView(root);

        configureWebView();
        webView.loadUrl("https://astro.local/index.html");
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setSupportZoom(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " BridgeAstroAndroid/0.1.1");
        if (android.os.Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.addJavascriptInterface(new AndroidBridge(this), "BridgeAndroid");
        webView.setWebViewClient(new AstroClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = params.createIntent(); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_FILE_CHOOSER); return true; }
                catch (Exception e) { fileCallback = null; Toast.makeText(MainActivity.this, "Cannot open Android file picker.", Toast.LENGTH_LONG).show(); return false; }
            }
        });
    }

    private final class AstroClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri u = request.getUrl();
            if (!"astro.local".equalsIgnoreCase(u.getHost())) return super.shouldInterceptRequest(view, request);
            try {
                String path = u.getPath() == null ? "/" : u.getPath();
                if ("/".equals(path) || "/index.html".equals(path)) {
                    File engine = new File(getFilesDir(), ENGINE_FILE);
                    InputStream in = engine.exists() ? new FileInputStream(engine) : getAssets().open("bootstrap.html");
                    return response("text/html", in, 200, "OK");
                }
                if ("/api/status".equals(path)) {
                    JSONObject j = new JSONObject().put("ok", true).put("platform", "ANDROID").put("mobileVersion", "0.1.1")
                            .put("tokenConfigured", tokenStore.hasToken()).put("engineImported", engineExists()).put("cacheExists", cacheExists())
                            .put("officialParserParity", false);
                    return response("application/json", bytes(j.toString()), 200, "OK");
                }
                if ("/api/cache".equals(path)) return response("application/json", new ByteArrayInputStream(updater.cacheBytes()), 200, "OK");
                if ("/api/update".equals(path)) {
                    MobileUpdater.Result r = updater.update();
                    return response("application/json", bytes(r.toJson().toString()), r.ok ? 200 : 500, r.ok ? "OK" : "Update Failed");
                }
                if ("/api/mobile-info".equals(path)) {
                    JSONObject j = new JSONObject().put("mode", "HYBRID_NATIVE_IMPORT").put("nativeSeaLagom", true)
                            .put("nativeOfficialV79", false).put("engineImported", engineExists());
                    return response("application/json", bytes(j.toString()), 200, "OK");
                }
                return response("text/plain", bytes("Not found"), 404, "Not Found");
            } catch (Exception e) {
                return response("application/json", bytes("{\"ok\":false,\"error\":\"" + jsonEscape(e.getMessage()) + "\"}"), 500, "Internal Error");
            }
        }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if ("astro.local".equalsIgnoreCase(uri.getHost())) return false;
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            return false;
        }
    }

    void openSettingsFromBridge() { showMobileMenu(); }

    private void showMobileMenu() {
        String engineState = engineExists() ? "✓ ASTRO engine installed" : "Import ASTRO engine HTML";
        String tokenState = tokenStore.hasToken() ? "✓ SeaLagom token configured" : "Configure SeaLagom token";
        String[] items = new String[]{engineState, tokenState, "Import WORLD MSI cache", "Reload ASTRO", "Remove imported engine"};
        new AlertDialog.Builder(this).setTitle("Bridge Astro Mobile").setItems(items, (d, which) -> {
            if (which == 0) chooseEngine();
            else if (which == 1) showTokenDialog();
            else if (which == 2) chooseCache();
            else if (which == 3) webView.reload();
            else if (which == 4) removeEngine();
        }).setNegativeButton("Close", null).show();
    }

    private void chooseEngine() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("text/html");
        startActivityForResult(i, REQ_IMPORT_ENGINE);
    }

    private void chooseCache() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/json");
        startActivityForResult(i, REQ_IMPORT_CACHE);
    }

    private void showTokenDialog() {
        EditText input = new EditText(this); input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(tokenStore.hasToken() ? "Token already configured — paste a replacement" : "SeaLagom X-API-Token");
        int pad = dp(20); FrameLayout box = new FrameLayout(this); box.setPadding(pad,0,pad,0); box.addView(input);
        new AlertDialog.Builder(this).setTitle("SeaLagom API token").setView(box)
                .setPositiveButton("Save", (d,w) -> { try { tokenStore.setToken(input.getText().toString()); Toast.makeText(this,"Token saved in Android Keystore.",Toast.LENGTH_LONG).show(); } catch(Exception e){ Toast.makeText(this,"Token save failed: "+e.getMessage(),Toast.LENGTH_LONG).show(); } })
                .setNeutralButton("Clear", (d,w) -> { tokenStore.clear(); Toast.makeText(this,"Token cleared.",Toast.LENGTH_SHORT).show(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void removeEngine() {
        File f = new File(getFilesDir(), ENGINE_FILE);
        if (f.exists() && !f.delete()) { Toast.makeText(this,"Could not remove engine.",Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(this,"Imported engine removed.",Toast.LENGTH_SHORT).show(); webView.loadUrl("https://astro.local/index.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback == null) return;
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result); fileCallback = null; return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_ENGINE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Cannot open selected HTML.");
                byte[] bytes = readAllBytes(in);
                String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8).toLowerCase();
                if (!head.contains("<html") && !head.contains("<!doctype")) throw new Exception("Selected file does not look like HTML.");
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), ENGINE_FILE), false)) { out.write(bytes); }
                Toast.makeText(this, "ASTRO engine imported: " + (bytes.length / 1024 / 1024) + " MB", Toast.LENGTH_LONG).show();
                webView.loadUrl("https://astro.local/index.html");
            } catch (Exception e) { Toast.makeText(this, "Engine import failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        } else if (requestCode == REQ_IMPORT_CACHE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("Cannot open selected cache."); updater.importCache(in);
                Toast.makeText(this, "WORLD MSI cache imported.", Toast.LENGTH_LONG).show(); webView.reload();
            } catch (Exception e) { Toast.makeText(this, "Cache import failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }

    private boolean engineExists() { return new File(getFilesDir(), ENGINE_FILE).exists(); }
    private boolean cacheExists() { return new File(getFilesDir(), MobileUpdater.CACHE_FILE).exists(); }

    private WebResourceResponse response(String mime, InputStream in, int status, String reason) {
        Map<String,String> headers = new HashMap<>(); headers.put("Access-Control-Allow-Origin", "*"); headers.put("Cache-Control", "no-store");
        return new WebResourceResponse(mime, "UTF-8", status, reason, headers, in);
    }
    private ByteArrayInputStream bytes(String s) { return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)); }
    private static String jsonEscape(String s) { if (s == null) return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r"," ").replace("\n"," "); }
    private static byte[] readAllBytes(InputStream in) throws Exception { try (ByteArrayOutputStream out = new ByteArrayOutputStream()) { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) out.write(b,0,n); return out.toByteArray(); } }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
