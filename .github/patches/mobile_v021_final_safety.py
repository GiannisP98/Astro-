from pathlib import Path


def must_replace(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Patch target not found: {label}")
    return text.replace(old, new, 1)

# ---- MobileUpdater.java ----
p = Path("app/src/main/java/com/bridgeastro/mobile/MobileUpdater.java")
s = p.read_text(encoding="utf-8")

s = must_replace(s,
    'static final String VERSION = "ANDROID-0.2.0-OFFICIAL-FIRST";',
    'static final String VERSION = "ANDROID-0.2.1-FINAL-SAFETY";',
    "updater version")

s = s.replace(
    'https://armada.defensa.gob.es/ArmadaPortal/page/Portal/ArmadaEspannola/cienciaihm1/prefLang-es/02ProductosServicios--02NAVAREAS',
    'https://armada.defensa.gob.es/ihm/Aplicaciones/Navareas/Index_radioavisos_en.html')
s = s.replace(
    'https://www.sanho.co.za/',
    'https://sanho.co.za/notices_mariners/navarea_v11_messages.htm?b2=NAVAREA+VII')

old_sea = '''            warnings=removeByMarker(warnings,"SEALAGOM"); text=removeByMarker(text,"SEALAGOM"); statuses=removeStatusMarker(statuses,"SEALAGOM");
            SeaResult sea=updateSeaLagom();
            for(int i=0;i<sea.geometry.length();i++) warnings.put(sea.geometry.optJSONObject(i));
            for(int i=0;i<sea.text.length();i++) text.put(sea.text.optJSONObject(i));
            statuses.put(statusObject("SeaLagom API","https://www.sealagom.com/api/docs/",sea.status,sea.mode,sea.count,sea.message));'''
new_sea = '''            SeaResult sea=updateSeaLagom();
            int seaAccepted=0;
            statuses=removeStatusMarker(statuses,"SEALAGOM");
            if(sea.status!=null && sea.status.startsWith("OK")){
                // Transactional replacement: only replace the last verified SeaLagom snapshot
                // after BOTH requested endpoint families completed successfully.
                warnings=removeByMarker(warnings,"SEALAGOM");
                text=removeByMarker(text,"SEALAGOM");
                for(int i=0;i<sea.geometry.length();i++) warnings.put(sea.geometry.optJSONObject(i));
                for(int i=0;i<sea.text.length();i++) text.put(sea.text.optJSONObject(i));
                seaAccepted=sea.count;
            }else{
                // Never commit a partial page/batch. Keep the previous cached SeaLagom snapshot.
                sea.geometry=new JSONArray(); sea.text=new JSONArray(); sea.count=0;
                sea.message=(sea.message==null?"":sea.message)+" · failed/partial batch discarded; previous cached SeaLagom records retained.";
            }
            statuses.put(statusObject("SeaLagom API","https://www.sealagom.com/api/docs/",sea.status,sea.mode,seaAccepted,sea.message));'''
s = must_replace(s, old_sea, new_sea, "transactional SeaLagom block")

s = must_replace(s,
    '.put("seaLagomRecordsThisRun",sea.count)',
    '.put("seaLagomRecordsThisRun",seaAccepted)',
    "SeaLagom summary count")
s = must_replace(s,
    'officialRecords+sea.count,"Official-first refresh complete:',
    'officialRecords+seaAccepted,"Official-first refresh complete:',
    "SeaLagom result count")
s = must_replace(s,
    '+officialRecords+" official record(s), SeaLagom "+sea.status+" "+sea.count+" record(s), "',
    '+officialRecords+" official record(s), SeaLagom "+sea.status+" "+seaAccepted+" accepted record(s), "',
    "SeaLagom result message")

s = must_replace(s,
    'JSONObject root=new JSONObject(json);JSONArray b=root.optJSONArray("bulletin");if(b==null)throw new Exception("WMO response has no bulletin array");',
    'JSONObject root=new JSONObject(json);JSONArray b=root.optJSONArray("bulletin");if(b==null)return new JSONArray();',
    "WMO empty response handling")

old_final_fail = '''}catch(Exception basicErr){r.status="ERROR_NONFATAL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="Full failed: "+safeMessage(fullErr)+" | Basic failed: "+safeMessage(basicErr);return r;}}}'''
new_final_fail = '''}catch(Exception basicErr){r.geometry=new JSONArray();r.text=new JSONArray();r.count=0;r.status="ERROR_NONFATAL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="Full failed: "+safeMessage(fullErr)+" | Basic failed: "+safeMessage(basicErr);return r;}}}'''
s = must_replace(s, old_final_fail, new_final_fail, "SeaLagom failed batch reset")

old_fingerprint = '''    private String fingerprint(JSONObject o){String num=o.optString("number",inferNumber(o.optString("id","")+" "+o.optString("raw",o.optString("text",""))));String raw=o.optString("raw",o.optString("text",o.optString("subject",""))).toUpperCase(Locale.US).replaceAll("\\\\s+"," ").replaceAll("[^A-Z0-9./ -]","").trim();String base=num.toUpperCase(Locale.US).replaceAll("\\\\s+","")+"|"+(raw.length()>1200?raw.substring(0,1200):raw);return sha256(base);}'''
new_fingerprint = '''    private String canonicalWarningKey(JSONObject o){
        String raw=(o.optString("id","")+" "+o.optString("number","")+" "+o.optString("raw",o.optString("text",o.optString("subject","")))).toUpperCase(Locale.US).replaceAll("\\\\s+"," ");
        Matcher m=Pattern.compile("\\\\bNAVAREA\\\\s+([IVXLC]+|\\\\d{1,2})\\\\s+(\\\\d{1,5})[/\\\\-](\\\\d{2,4})\\\\b").matcher(raw);
        if(m.find())return "NAVAREA|"+roman(m.group(1))+"|"+m.group(2)+"/"+m.group(3);
        m=Pattern.compile("\\\\bAUSCOAST(?:\\\\s+WARNING)?\\\\s+(\\\\d{1,5})[/\\\\-](\\\\d{2,4})\\\\b").matcher(raw);
        if(m.find())return "AUSCOAST|"+m.group(1)+"/"+m.group(2);
        m=Pattern.compile("\\\\b(?:NZ\\\\s+)?COASTAL(?:\\\\s+WARNING)?\\\\s+(\\\\d{1,5})[/\\\\-](\\\\d{2,4})\\\\b").matcher(raw);
        if(m.find())return "NZCOASTAL|"+m.group(1)+"/"+m.group(2);
        return "";
    }
    private String fingerprint(JSONObject o){String canonical=canonicalWarningKey(o);if(!canonical.isEmpty())return sha256("CANON|"+canonical);String num=o.optString("number",inferNumber(o.optString("id","")+" "+o.optString("raw",o.optString("text",""))));String raw=o.optString("raw",o.optString("text",o.optString("subject",""))).toUpperCase(Locale.US).replaceAll("\\\\s+"," ").replaceAll("[^A-Z0-9./ -]","").trim();String base=num.toUpperCase(Locale.US).replaceAll("\\\\s+","")+"|"+(raw.length()>1200?raw.substring(0,1200):raw);return sha256(base);}'''
s = must_replace(s, old_fingerprint, new_fingerprint, "authority-aware canonical dedup")

p.write_text(s, encoding="utf-8")

# ---- MainActivity.java ----
p = Path("app/src/main/java/com/bridgeastro/mobile/MainActivity.java")
s = p.read_text(encoding="utf-8")
s = must_replace(s,
    'if (html.contains("2026.09.15.MSI2.0-OFFICIAL-FIRST")) return;',
    'if (html.contains("2026.09.16.MSI2.3-FINAL-SAFETY-OFFLINE-MAP")) return;',
    "MSI v2.3 imported-engine guard")
s = s.replace('"MSI2.0 / v7.9 SAFE"', '"MSI2.3 / v7.9 SAFE"')
s = s.replace('"Using bundled MSI2.0 engine."', '"Using bundled shell; import the full ASTRO v2.3 engine for operational use."')
p.write_text(s, encoding="utf-8")

# ---- app/build.gradle ----
p = Path("app/build.gradle")
s = p.read_text(encoding="utf-8")
s = must_replace(s, "versionCode 4", "versionCode 5", "versionCode")
s = must_replace(s, "versionName '0.2.0'", "versionName '0.2.1'", "versionName")
p.write_text(s, encoding="utf-8")

print("Applied Bridge Astro Mobile v0.2.1 FINAL-SAFETY patch")
