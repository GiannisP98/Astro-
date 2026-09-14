package com.bridgeastro.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class MobileUpdater {
    static final String CACHE_FILE = "world_msi_cache.json";
    private final Context context;
    private final TokenStore tokenStore;

    static final class Result {
        final boolean ok; final boolean partial; final int added; final String message;
        Result(boolean ok, boolean partial, int added, String message) {
            this.ok = ok; this.partial = partial; this.added = added; this.message = message;
        }
        JSONObject toJson() {
            try { return new JSONObject().put("ok", ok).put("partial", partial).put("added", added).put("message", message); }
            catch (Exception e) { return new JSONObject(); }
        }
    }

    MobileUpdater(Context context, TokenStore tokenStore) {
        this.context = context.getApplicationContext();
        this.tokenStore = tokenStore;
    }

    Result update() {
        try {
            JSONObject cache = loadCache();
            removeSource(cache, "SEALAGOM API");
            String token = tokenStore.getToken();
            int added = 0;
            JSONArray sources = cache.optJSONArray("sources");
            if (sources == null) { sources = new JSONArray(); cache.put("sources", sources); }
            if (!token.isEmpty()) {
                try {
                    added += fetchSeaLagom(cache, token, "NAVAREA", "https://www.sealagom.com/api/v1/navarea/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true");
                    added += fetchSeaLagom(cache, token, "COASTAL", "https://www.sealagom.com/api/v1/coastal/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true");
                    sources.put(sourceStatus("SeaLagom API", "https://www.sealagom.com/api/docs/", "OK", "SUPPLEMENTAL_API_NATIVE_ANDROID", added, "Native Android HTTPS sync completed."));
                } catch (Exception e) {
                    sources.put(sourceStatus("SeaLagom API", "https://www.sealagom.com/api/docs/", "ERROR_NONFATAL", "SUPPLEMENTAL_API_NATIVE_ANDROID", 0, e.getMessage()));
                }
            } else {
                sources.put(sourceStatus("SeaLagom API", "https://www.sealagom.com/api/docs/", "SKIPPED_NO_TOKEN", "SUPPLEMENTAL_API_NATIVE_ANDROID", 0, "No token stored in Android Keystore."));
            }
            cache.put("builtUtc", isoNow());
            cache.put("updaterVersion", "ANDROID-0.1.1");
            saveCache(cache);
            return new Result(true, true, added, "Native SeaLagom supplemental update complete. Import the latest desktop WORLD MSI cache for full official-source parity in this first mobile build.");
        } catch (Exception e) {
            return new Result(false, true, 0, e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    JSONObject loadCache() throws Exception {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        String text = f.exists() ? readAll(new FileInputStream(f)) : readAll(context.getAssets().open("world_msi_seed.json"));
        return new JSONObject(text);
    }

    void saveCache(JSONObject cache) throws Exception {
        File f = new File(context.getFilesDir(), CACHE_FILE);
        try (FileOutputStream out = new FileOutputStream(f, false)) { out.write(cache.toString().getBytes(StandardCharsets.UTF_8)); }
    }

    void importCache(InputStream input) throws Exception {
        String text = readAll(input);
        JSONObject cache = new JSONObject(text);
        if (!cache.has("warnings") || !cache.has("sources")) throw new IllegalArgumentException("Not a WORLD_MSI_LIVE_CACHE JSON file.");
        saveCache(cache);
    }

    byte[] cacheBytes() throws Exception { return loadCache().toString().getBytes(StandardCharsets.UTF_8); }

    private int fetchSeaLagom(JSONObject cache, String token, String channel, String url) throws Exception {
        String body = httpGet(url, token);
        JSONObject root = new JSONObject(body);
        JSONArray regions = root.optJSONArray("results");
        if (regions == null) regions = root.optJSONArray("data");
        if (regions == null) regions = new JSONArray();
        JSONArray warnings = cache.optJSONArray("warnings");
        JSONArray textWarnings = cache.optJSONArray("textWarnings");
        if (warnings == null) { warnings = new JSONArray(); cache.put("warnings", warnings); }
        if (textWarnings == null) { textWarnings = new JSONArray(); cache.put("textWarnings", textWarnings); }
        int count = 0;
        for (int i = 0; i < regions.length(); i++) {
            JSONObject region = regions.optJSONObject(i); if (region == null) continue;
            String regionTitle = opt(region, "title", opt(region, "name", channel));
            String area = navareaFromTitle(regionTitle);
            JSONArray msgs = region.optJSONArray("active_messages");
            if (msgs == null) msgs = region.optJSONArray("messages");
            if (msgs == null) continue;
            for (int j = 0; j < msgs.length(); j++) {
                JSONObject msg = msgs.optJSONObject(j); if (msg == null) continue;
                String number = opt(msg, "number", String.valueOf(msg.opt("id")));
                String content = opt(msg, "content", opt(msg, "content_text", opt(msg, "message", "")));
                String id = "SEALAGOM " + channel + " " + regionTitle + " " + number;
                String verified = opt(msg, "added_on", isoNow());
                String cancel = opt(msg, "cancel_date", "");
                JSONObject gf = msg.optJSONObject("geo_features");
                JSONArray features = gf == null ? null : gf.optJSONArray("features");
                boolean hadGeom = false;
                if (features != null) {
                    for (int k = 0; k < features.length(); k++) {
                        JSONObject feat = features.optJSONObject(k); if (feat == null) continue;
                        String kind = opt(feat, "kind", "point").toLowerCase(Locale.US);
                        JSONArray pts = normalizePoints(feat.optJSONArray("points"));
                        if (pts.length() == 0) continue;
                        warnings.put(new JSONObject().put("id", id + (features.length() > 1 ? " #" + (k + 1) : ""))
                                .put("source", "SeaLagom API · " + regionTitle).put("area", area)
                                .put("subject", number + " · " + shortText(content)).put("kind", kind).put("points", pts)
                                .put("verifiedAt", verified).put("cancelUtc", cancel).put("channel", channel)
                                .put("trust", "SUPPLEMENTAL").put("sourceUrl", url).put("raw", content));
                        count++; hadGeom = true;
                    }
                }
                if (!hadGeom) {
                    JSONArray pts = coordinatePoints(msg.opt("coordinates"));
                    if (pts.length() > 0) {
                        warnings.put(new JSONObject().put("id", id).put("source", "SeaLagom API · " + regionTitle)
                                .put("area", area).put("subject", number + " · " + shortText(content)).put("kind", "points")
                                .put("points", pts).put("verifiedAt", verified).put("cancelUtc", cancel).put("channel", channel)
                                .put("trust", "SUPPLEMENTAL").put("sourceUrl", url).put("raw", content));
                        count++; hadGeom = true;
                    }
                }
                if (!hadGeom) {
                    textWarnings.put(new JSONObject().put("id", id).put("source", "SeaLagom API · " + regionTitle)
                            .put("area", area).put("subject", number + " · " + shortText(content))
                            .put("reason", "No safe structured geometry in API response.").put("sourceUrl", url)
                            .put("channel", channel).put("trust", "SUPPLEMENTAL").put("verifiedAt", verified)
                            .put("cancelUtc", cancel).put("raw", content));
                    count++;
                }
            }
        }
        return count;
    }

    private static void removeSource(JSONObject cache, String marker) {
        for (String key : new String[]{"warnings", "textWarnings", "sources"}) {
            JSONArray a = cache.optJSONArray(key); if (a == null) continue;
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String hay = (o.optString("source") + " " + o.optString("name")).toUpperCase(Locale.US);
                if (!hay.contains(marker.toUpperCase(Locale.US))) out.put(o);
            }
            try { cache.put(key, out); } catch (Exception ignored) {}
        }
    }

    private static JSONObject sourceStatus(String name, String url, String status, String mode, int count, String message) throws Exception {
        return new JSONObject().put("name", name).put("url", url).put("status", status).put("mode", mode)
                .put("fetchedUtc", isoNow()).put("count", count).put("message", message == null ? "" : message);
    }

    private static JSONArray normalizePoints(JSONArray in) throws Exception {
        JSONArray out = new JSONArray(); if (in == null) return out;
        for (int i = 0; i < in.length(); i++) {
            JSONObject p = in.optJSONObject(i); if (p == null) continue;
            double lat = p.optDouble("lat", Double.NaN), lon = p.optDouble("lon", Double.NaN);
            if (Double.isNaN(lon)) lon = p.optDouble("lng", Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)
                out.put(new JSONObject().put("lat", lat).put("lon", lon));
        }
        return out;
    }

    private static JSONArray coordinatePoints(Object coordinates) throws Exception {
        JSONArray out = new JSONArray();
        if (!(coordinates instanceof JSONObject)) return out;
        JSONArray d = ((JSONObject) coordinates).optJSONArray("decimal_coordinates");
        if (d == null) return out;
        for (int i = 0; i < d.length(); i++) {
            JSONArray p = d.optJSONArray(i); if (p == null || p.length() < 2) continue;
            double lat = p.optDouble(0, Double.NaN), lon = p.optDouble(1, Double.NaN);
            if (!Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)
                out.put(new JSONObject().put("lat", lat).put("lon", lon));
        }
        return out;
    }

    private static String httpGet(String u, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "BridgeAstroMobile/0.1.1");
        c.setRequestProperty("X-API-Token", token);
        int status = c.getResponseCode();
        InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : readAll(in);
        if (status < 200 || status >= 300) throw new Exception("SeaLagom HTTP " + status + (body.isEmpty() ? "" : " · " + shortText(body)));
        return body;
    }

    private static String navareaFromTitle(String title) {
        String u = title == null ? "" : title.toUpperCase(Locale.US); int i = u.indexOf("NAVAREA");
        if (i >= 0) { String tail = u.substring(i + 7).trim(); int sp = tail.indexOf(' '); return sp > 0 ? tail.substring(0, sp) : tail; }
        return "";
    }

    private static String opt(JSONObject o, String key, String def) {
        String v = o.optString(key, ""); return v == null || v.trim().isEmpty() || "null".equalsIgnoreCase(v) ? def : v.trim();
    }

    private static String shortText(String s) {
        if (s == null) return ""; s = s.replaceAll("\\s+", " ").trim(); return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US); f.setTimeZone(TimeZone.getTimeZone("UTC")); return f.format(new Date());
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n; while ((n = src.read(b)) >= 0) out.write(b, 0, n); return out.toString("UTF-8");
        }
    }
}
