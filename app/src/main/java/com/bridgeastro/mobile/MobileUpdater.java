package com.bridgeastro.mobile;

import android.content.Context;
import android.text.Html;

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
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class MobileUpdater {
    static final String CACHE_FILE = "world_msi_cache.json";
    static final String VERSION = "ANDROID-0.2.0-OFFICIAL-FIRST";
    private final Context context;
    private final TokenStore tokenStore;

    private static final int CONNECT_MS = 9000;
    private static final int READ_MS = 16000;
    private static final int MAX_BODY = 5 * 1024 * 1024;

    static final class Result {
        final boolean ok; final boolean partial; final int added; final String message;
        Result(boolean ok, boolean partial, int added, String message) { this.ok=ok; this.partial=partial; this.added=added; this.message=message; }
        JSONObject toJson() {
            try { return new JSONObject().put("ok",ok).put("partial",partial).put("added",added).put("message",message); }
            catch(Exception e){ return new JSONObject(); }
        }
    }

    private static final class SourceDef {
        final String name, coverage, country, area, mode, url, parser, channel;
        SourceDef(String name,String coverage,String country,String area,String mode,String url,String parser,String channel){
            this.name=name; this.coverage=coverage; this.country=country; this.area=area; this.mode=mode; this.url=url; this.parser=parser; this.channel=channel;
        }
    }

    private static final class SourceResult {
        final SourceDef def; final String status, message; final JSONArray records; final boolean authoritative;
        SourceResult(SourceDef d,String st,String msg,JSONArray rec,boolean auth){def=d;status=st;message=msg;records=rec;authoritative=auth;}
    }

    private static SourceDef s(String name,String coverage,String country,String area,String mode,String url,String parser,String channel){
        return new SourceDef(name,coverage,country,area,mode,url,parser,channel);
    }

    private static final SourceDef[] OFFICIAL = new SourceDef[]{
        s("UKHO NAVAREA I / UK Coastal","NAVAREA I","United Kingdom","I","AUTO_OFFICIAL_WEB","https://msi.admiralty.co.uk/RadioNavigationalWarnings","UKHO","EGC/NAVTEX WEB EQUIVALENT"),
        s("NAVAREA II - France","NAVAREA II","France","II","AUTO_OFFICIAL_WEB","https://diffusion.shom.fr/pro/navarea-en-vigueur","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA III - Spain","NAVAREA III","Spain","III","AUTO_OFFICIAL_WEB","https://armada.defensa.gob.es/ArmadaPortal/page/Portal/ArmadaEspannola/cienciaihm1/prefLang-es/02ProductosServicios--02NAVAREAS","GENERIC","EGC WEB EQUIVALENT"),
        s("NGA NAVAREA IV / XII active warnings","NAVAREA IV / XII","United States","","AUTO_NGA_OFFICIAL_API","https://msi.nga.mil/api/publications/broadcast-warn?status=active&output=json","NGA","EGC/NAVAREA"),
        s("NAVAREA V - Brazil","NAVAREA V","Brazil","V","AUTO_OFFICIAL_WEB","https://www.marinha.mil.br/chm/dados-do-segnav-aviso-radio-nautico-tela/radio-navigational-warnings-and-sar-warnings","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VI - Argentina","NAVAREA VI","Argentina","VI","AUTO_OFFICIAL_WEB","https://www.hidro.gov.ar/nautica/cnv.asp","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VII - South Africa","NAVAREA VII","South Africa","VII","AUTO_OFFICIAL_WEB","https://www.sanho.co.za/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VIII - India / NHO","NAVAREA VIII","India","VIII","AUTO_PARSED_CURRENT","https://hydrobharat.gov.in/navarea-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("Pakistan NAVAREA IX","NAVAREA IX","Pakistan","IX","AUTO_OFFICIAL_WEB","https://hydrography.paknavy.gov.pk/navarea-ix-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("AMSA NAVAREA X / AUSCOAST","NAVAREA X","Australia","X","AUTO_PARSED","https://www.operations.amsa.gov.au/AMSA.Web.MSIPublication/","AMSA","EGC/NAVTEX WEB EQUIVALENT"),
        s("Japan JCG NAVAREA XI","NAVAREA XI","Japan","XI","AUTO_OFFICIAL_WEB","https://www1.kaiho.mlit.go.jp/TUHO/keiho/navarea11_en.html","JCG","EGC/NAVAREA"),
        s("NAVAREA XIII - Russian Federation","NAVAREA XIII","Russian Federation","XIII","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XIV - New Zealand / Maritime NZ","NAVAREA XIV","New Zealand","XIV","AUTO_PARSED_CURRENT","https://www.maritimenz.govt.nz/navigational-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XV - Chile","NAVAREA XV","Chile","XV","AUTO_OFFICIAL_WEB","https://www.shoa.mil.cl/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XVI - Peru / DIHIDRONAV","NAVAREA XVI","Peru","XVI","AUTO_PARSED_CURRENT","https://www.dhn.mil.pe/portal/navarea/radioavisos-warnings","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XVII - Canada","NAVAREA XVII","Canada","XVII","AUTO_OFFICIAL_WEB","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("NAVAREA XVIII - Canada","NAVAREA XVIII","Canada","XVIII","AUTO_OFFICIAL_WEB","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("NAVAREA XIX - Norway / Kystverket","NAVAREA XIX","Norway","XIX","AUTO_PARSED_CURRENT","https://kyvreports.kystverket.no/NavcoReport/navareaxixvarsler.aspx","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XX - Russian Federation","NAVAREA XX","Russian Federation","XX","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XXI - Russian Federation","NAVAREA XXI","Russian Federation","XXI","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("China MSA navigational warnings","NATIONAL - China","China","XI","AUTO_OFFICIAL_NATIONAL","https://www.msa.gov.cn/page/outter/weather.jsp","CHINA","COASTAL/NAVWARN"),
        s("Canada NAVWARN published messages","NATIONAL - Canada","Canada","","AUTO_OFFICIAL_NATIONAL","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("India NHO NAVTEX in-force PDF","NATIONAL - India","India","VIII","AUTO_OFFICIAL_PDF","https://hydrobharat.gov.in/documents/d/guest/navtex-warnings-indian-coast","PDF_PROBE","NAVTEX"),
        s("Philippines NAMRIA NAVPHIL","NATIONAL - Philippines","Philippines","XI","AUTO_OFFICIAL_PDF","https://namria.gov.ph/downloads.aspx","PDF_INDEX","COASTAL/NAVWARN")
    };

    MobileUpdater(Context context, TokenStore tokenStore){this.context=context.getApplicationContext();this.tokenStore=tokenStore;}

    Result update(){
        try{
            JSONObject cache=loadCache();
            JSONArray statuses=cache.optJSONArray("sources"); if(statuses==null) statuses=new JSONArray();
            JSONArray warnings=cache.optJSONArray("warnings"); if(warnings==null) warnings=new JSONArray();
            JSONArray text=cache.optJSONArray("textWarnings"); if(text==null) text=new JSONArray();

            List<SourceResult> results=runOfficialParallel();
            int officialRecords=0, officialOk=0, officialErrors=0;
            for(SourceResult r:results){
                if(r.authoritative){ warnings=removeBySource(warnings,r.def); text=removeBySource(text,r.def); }
                for(int i=0;i<r.records.length();i++) text.put(r.records.optJSONObject(i));
                officialRecords+=r.records.length();
                if(r.status.startsWith("OK")) officialOk++; else if(r.status.contains("ERROR")) officialErrors++;
                statuses=replaceStatus(statuses,statusObject(r.def.name,r.def.url,r.status,r.def.mode,r.records.length(),r.message));
            }

            List<SourceResult> wmo=runWmoParallel();
            for(SourceResult r:wmo){
                if(r.authoritative) text=removeBySource(text,r.def);
                for(int i=0;i<r.records.length();i++) text.put(r.records.optJSONObject(i));
                officialRecords+=r.records.length();
                if(r.status.startsWith("OK")) officialOk++; else if(r.status.contains("ERROR")) officialErrors++;
                statuses=replaceStatus(statuses,statusObject(r.def.name,r.def.url,r.status,r.def.mode,r.records.length(),r.message));
            }

            warnings=removeByMarker(warnings,"SEALAGOM"); text=removeByMarker(text,"SEALAGOM"); statuses=removeStatusMarker(statuses,"SEALAGOM");
            SeaResult sea=updateSeaLagom();
            for(int i=0;i<sea.geometry.length();i++) warnings.put(sea.geometry.optJSONObject(i));
            for(int i=0;i<sea.text.length();i++) text.put(sea.text.optJSONObject(i));
            statuses.put(statusObject("SeaLagom API","https://www.sealagom.com/api/docs/",sea.status,sea.mode,sea.count,sea.message));

            JSONObject cleaned=cleanup(warnings,text,cache.optJSONArray("expiredWarnings"));
            warnings=cleaned.getJSONArray("warnings"); text=cleaned.getJSONArray("textWarnings");
            JSONArray expired=cleaned.getJSONArray("expiredWarnings"); int dup=cleaned.optInt("duplicatesSuppressed"), exp=cleaned.optInt("expiredSuppressed");

            cache.put("warnings",warnings).put("textWarnings",text).put("expiredWarnings",expired).put("sources",statuses);
            cache.put("builtUtc",isoNow()).put("updaterVersion",VERSION);
            cache.put("summary",new JSONObject().put("officialRecordsThisRun",officialRecords).put("officialSourcesHealthy",officialOk).put("officialSourcesErrors",officialErrors).put("seaLagomRecordsThisRun",sea.count).put("duplicatesSuppressed",dup).put("expiredSuppressed",exp).put("activeWarnings",warnings.length()+text.length()).put("geometrySeedRecords",warnings.length()).put("textReviewRecords",text.length()));
            saveCache(cache);
            boolean partial=officialErrors>0 || !sea.status.startsWith("OK");
            return new Result(true,partial,officialRecords+sea.count,"Official-first refresh complete: "+officialOk+" official source(s) healthy, "+officialErrors+" error(s), "+officialRecords+" official record(s), SeaLagom "+sea.status+" "+sea.count+" record(s), "+dup+" duplicate(s) suppressed, "+exp+" expired record(s) excluded from active cache.");
        }catch(Exception e){return new Result(false,true,0,safeMessage(e));}
    }

    private List<SourceResult> runOfficialParallel() throws Exception{
        ExecutorService ex=Executors.newFixedThreadPool(6); List<Future<SourceResult>> fs=new ArrayList<>();
        for(SourceDef d:OFFICIAL) fs.add(ex.submit(new Callable<SourceResult>(){public SourceResult call(){return fetchOfficial(d);}}));
        List<SourceResult> out=new ArrayList<>(); for(Future<SourceResult> f:fs){try{out.add(f.get());}catch(Exception e){}} ex.shutdownNow(); return out;
    }

    private List<SourceResult> runWmoParallel() throws Exception{
        String[] toks={"1","2","3","4","5","6","7","8N","8S","9","10","11","12","13","14","15","16","17","18","19","20","21"};
        ExecutorService ex=Executors.newFixedThreadPool(6); List<Future<SourceResult>> fs=new ArrayList<>();
        for(String t:toks){
            final String area=roman(t), url="https://wwmiws.wmo.int/index.php/metareas/bulletinset_download/"+t+"/json";
            final SourceDef d=s("WMO WWMIWS METAREA "+area,"METAREA "+area,"WMO/IMO",area,"WMO_OFFICIAL_BULLETINSET_JSON",url,"WMO","EGC/METAREA");
            fs.add(ex.submit(new Callable<SourceResult>(){public SourceResult call(){return fetchOfficial(d);}}));
        }
        List<SourceResult> out=new ArrayList<>(); for(Future<SourceResult> f:fs){try{out.add(f.get());}catch(Exception e){}} ex.shutdownNow(); return out;
    }

    private SourceResult fetchOfficial(SourceDef d){
        try{
            HttpResult h=httpGet(d.url,null,"application/json,text/html,text/plain,*/*"); JSONArray rec; boolean authoritative=false;
            if("NGA".equals(d.parser)){rec=parseNga(h.body,d); authoritative=true;}
            else if("WMO".equals(d.parser)){rec=parseWmo(h.body,d); authoritative=true;}
            else if("UKHO".equals(d.parser)){rec=parseUkho(h.body,d); authoritative=rec.length()>0 || h.body.contains("Radio Navigation Warnings");}
            else if("AMSA".equals(d.parser)){rec=parseAmsa(h.body,d); authoritative=rec.length()>0 || h.body.contains("Maritime Safety Information current at");}
            else if("JCG".equals(d.parser)){rec=parseJcgStatic(h.body,d); authoritative=rec.length()>0;}
            else if("CHINA".equals(d.parser)){rec=parseChina(h.body,d); authoritative=rec.length()>0;}
            else if("CANADA".equals(d.parser)){rec=parseCanada(h.body,d); authoritative=rec.length()>0;}
            else if("PDF_PROBE".equals(d.parser)){return new SourceResult(d,"FETCHED_PDF_REFERENCE","Official PDF endpoint reached ("+h.bytes+" bytes). Android keeps the previous verified v7.9 PDF-derived cache because no unverified PDF text is auto-plotted.",new JSONArray(),false);}
            else if("PDF_INDEX".equals(d.parser)){return new SourceResult(d,"FETCHED_INDEX_REFERENCE","Official download/index page reached. Existing verified v7.9 NAVPHIL cache is retained; PDF discovery is not guessed from ambiguous links.",new JSONArray(),false);}
            else {rec=parseGeneric(h.body,d); authoritative=rec.length()>0;}
            String status=rec.length()>0?"OK":(authoritative?"OK_EMPTY":"FETCHED_NO_SAFE_MESSAGES");
            return new SourceResult(d,status,"Official source reached via Android HTTPS; "+rec.length()+" warning text record(s) parsed conservatively.",rec,authoritative);
        }catch(Exception e){return new SourceResult(d,"ERROR","Official fetch failed: "+safeMessage(e)+" · previous verified cache retained.",new JSONArray(),false);}
    }

    private JSONArray parseGeneric(String body,SourceDef d)throws Exception{
        String t=plain(body); JSONArray out=new JSONArray(); if(d.area==null||d.area.isEmpty()) return out;
        Pattern p=Pattern.compile("(?i)\\bNAVAREA\\s+"+Pattern.quote(d.area)+"\\s*(?:WARNING|WARNINGS|NW)?\\s*(?:NO\\.?|NR\\.?|NUMBER)?\\s*[:#-]?\\s*(\\d{1,4})[/\\-](\\d{2,4})\\b");
        Matcher m=p.matcher(t); List<int[]> pos=new ArrayList<>(); List<String> ids=new ArrayList<>();
        while(m.find()){String id="NAVAREA "+d.area+" "+m.group(1)+"/"+m.group(2); if(!ids.contains(id)){ids.add(id);pos.add(new int[]{m.start(),m.end()});}}
        for(int i=0;i<pos.size();i++){int st=pos.get(i)[0], en=i+1<pos.size()?pos.get(i+1)[0]:Math.min(t.length(),st+8000);out.put(textRecord(ids.get(i),d,t.substring(st,en),false));}
        return out;
    }

    private JSONArray parseUkho(String html,SourceDef d)throws Exception{
        JSONArray out=new JSONArray(); Pattern p=Pattern.compile("(?is)<h2[^>]*id=\\\"Details_Reference_[^\\\"]*\\\"[^>]*>(.*?)</h2>.*?<pre[^>]*id=\\\"Details_Description_[^\\\"]*\\\"[^>]*>(.*?)</pre>");
        Matcher m=p.matcher(html); while(m.find()){String id=plain(m.group(1)).replaceAll("\\s+"," ").trim().toUpperCase(Locale.US);String desc=plain(m.group(2));if(!id.matches("^(NAVAREA\\s+I\\s+\\d{1,4}/\\d{2,4}|WZ\\s+\\d{1,4}/\\d{2,4})$"))continue;out.put(textRecord(id,d,id+"\n"+desc,false));} return out;
    }

    private JSONArray parseAmsa(String html,SourceDef d)throws Exception{
        String t=plain(html); JSONArray out=new JSONArray(); Pattern p=Pattern.compile("(?i)\\b(NAVAREA\\s+X\\s+(\\d{1,4}/\\d{2,4})|AUSCOAST\\s+WARNING\\s+(\\d{1,4}/\\d{2,4}))\\b"); Matcher m=p.matcher(t);
        List<Integer> starts=new ArrayList<>(); List<String> ids=new ArrayList<>(); while(m.find()){String id=m.group(1).toUpperCase(Locale.US).replaceAll("\\s+"," "); if(!ids.contains(id)){ids.add(id);starts.add(m.start());}}
        for(int i=0;i<starts.size();i++){int st=starts.get(i),en=i+1<starts.size()?starts.get(i+1):Math.min(t.length(),st+10000);out.put(textRecord(ids.get(i),d,t.substring(st,en),false));} return out;
    }

    private JSONArray parseJcgStatic(String html,SourceDef d)throws Exception{
        JSONArray out=new JSONArray(); Matcher m=Pattern.compile("(?i)TANA(?:;)?=(\\d{6})").matcher(html); List<String> tana=new ArrayList<>(); while(m.find())if(!tana.contains(m.group(1)))tana.add(m.group(1));
        for(int i=0;i<Math.min(120,tana.size());i++){String t=tana.get(i),u="https://www1.kaiho.mlit.go.jp/TUHO/keiho/cgi/disp_warnings.cgi?TYPE=NAVAREA11&TANA="+t+"&LANG=EG";try{HttpResult h=httpGet(u,null,"text/html,*/*");String tx=plain(h.body);if(tx.length()>20){SourceDef q=s(d.name,d.coverage,d.country,d.area,d.mode,u,d.parser,d.channel);out.put(textRecord("NAVAREA XI "+t.substring(2)+"/"+t.substring(0,2),q,tx,false));}}catch(Exception ignored){}}
        return out;
    }

    private JSONArray parseChina(String html,SourceDef d)throws Exception{
        String t=plain(html); JSONArray out=new JSONArray(); Pattern p=Pattern.compile("(?i)\\b((?:ZJ|CE|SH|FJ|LYG|JS|GD|GX|LN|HB)\\s*\\d{1,4}/\\d{2})\\b"); Matcher m=p.matcher(t);List<Integer> starts=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id=m.group(1).replaceAll("\\s+","").toUpperCase(Locale.US);if(!ids.contains(id)){ids.add(id);starts.add(m.start());}}for(int i=0;i<starts.size();i++){int st=starts.get(i),en=i+1<starts.size()?starts.get(i+1):Math.min(t.length(),st+8000);out.put(textRecord(ids.get(i),d,t.substring(st,en),false));}return out;
    }

    private JSONArray parseCanada(String body,SourceDef d)throws Exception{
        String t=plain(body);JSONArray out=new JSONArray();Pattern p=Pattern.compile("(?i)\\b(NW-[A-Z]+-\\d{3,5}-\\d{2})\\b");Matcher m=p.matcher(t);List<Integer> starts=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id=m.group(1).toUpperCase(Locale.US);if(!ids.contains(id)){ids.add(id);starts.add(m.start());}}for(int i=0;i<starts.size();i++){int st=starts.get(i),en=i+1<starts.size()?starts.get(i+1):Math.min(t.length(),st+9000);out.put(textRecord(ids.get(i),d,t.substring(st,en),false));}return out;
    }

    private JSONArray parseNga(String json,SourceDef d)throws Exception{
        JSONArray out=new JSONArray();Object root=new org.json.JSONTokener(json).nextValue();JSONArray a;if(root instanceof JSONObject){JSONObject o=(JSONObject)root;a=o.optJSONArray("broadcast-warn");if(a==null){Object x=o.opt("broadcast-warn");a=new JSONArray();if(x instanceof JSONObject)a.put(x);}}else a=(JSONArray)root;
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String status=x.optString("status","").toUpperCase(Locale.US);if(!status.isEmpty()&&!status.equals("A")&&!status.equals("ACTIVE"))continue;String text=x.optString("text","");if(text.trim().isEmpty())continue;String rawArea=x.optString("navArea",x.optString("area","")),area=roman(rawArea),num=x.optString("msgNumber",x.optString("number",String.valueOf(i+1))),year=x.optString("msgYear",x.optString("year",yearNow())),bt=rawArea.equalsIgnoreCase("A")?"HYDROLANT":rawArea.equalsIgnoreCase("P")?"HYDROPAC":rawArea.equalsIgnoreCase("C")?"HYDROARC":"NAVAREA "+area;SourceDef q=s(d.name,d.coverage,d.country,area,d.mode,d.url,d.parser,d.channel);JSONObject r=textRecord(bt+" "+num+"/"+year,q,text,false);r.put("issuedUtc",x.optString("issueDate",isoNow())).put("apiStatus",status).put("broadcastType",bt);out.put(r);}return out;
    }

    private JSONArray parseWmo(String json,SourceDef d)throws Exception{
        JSONObject root=new JSONObject(json);JSONArray b=root.optJSONArray("bulletin");if(b==null)throw new Exception("WMO response has no bulletin array");String issued=root.optString("date",isoNow());JSONArray out=new JSONArray();
        for(int i=0;i<b.length();i++){JSONObject x=b.optJSONObject(i);if(x==null)continue;String label=x.optString("label","WMO BULLETIN"),txt=flattenJsonValue(x.opt("content"));if(txt.trim().isEmpty())continue;List<String> sections=wmoHazardSections(label,txt);int seg=0;for(String sec:sections){if(!wmoHazardGeometry(label,sec))continue;seg++;JSONObject r=textRecord("METAREA "+d.area+" "+(i+1)+" SEG "+seg,d,sec,false);r.put("issuedUtc",issued).put("bulletinLabel",label).put("reason","Official WMO/IMO warning section isolated by v7.9 safety policy; ASTRO MSI2 may promote only explicit clean geometry.");out.put(r);}if(seg==0){JSONObject r=textRecord("METAREA "+d.area+" "+(i+1),d,label+"\n"+txt,true);r.put("issuedUtc",issued).put("bulletinLabel",label).put("reason","Official WMO/IMO bulletin retained in full. Forecast/general coverage text is force-review-only; no navigation geometry is invented.");out.put(r);}}
        return out;
    }

    private static List<String> wmoHazardSections(String label,String text){
        List<String> out=new ArrayList<>();String L=label==null?"":label,T=text==null?"":text;
        boolean direct=(Pattern.compile("(?i)\\b(?:WARNING|ADVISORY)\\b").matcher(L).find()&&!Pattern.compile("(?i)\\bFORECAST\\b").matcher(L).find())||Pattern.compile("(?i)TROPICAL\\s+(?:CYCLONE|STORM)|HURRICANE|TYPHOON|VOLCANIC\\s+ASH|TSUNAMI").matcher(L).find();
        if(direct)return splitWmoItems(T);
        Matcher m=Pattern.compile("(?is)(?:^|\\r?\\n)\\s*PART\\s*[- ]?(?:1|I)\\b(.*?)(?=(?:\\r?\\n\\s*PARTS?\\s*[- ]?(?:2|II)\\b)|\\z)").matcher(T);if(m.find())return splitWmoItems(m.group(1));
        m=Pattern.compile("(?is)(?:^|\\r?\\n)\\s*(?:GALE\\s+FORCE\\s+)?WARNINGS?\\s*[:=](.*?)(?=(?:\\r?\\n\\s*(?:SYNOPTIC|AREA\\s+FORECAST|FORECAST\\s+VALID|PARTS?\\s*[- ]?(?:2|II))\\b)|\\z)").matcher(T);if(m.find())return splitWmoItems(m.group(1));return out;
    }

    private static List<String> splitWmoItems(String s){List<String> out=new ArrayList<>();if(s==null)return out;String clean=s.replaceAll("(?im)^.*\\b(?:WARNINGS?|STORM\\s+WARNING|GALE\\s+WARNING)\\b\\s*[:=-]?\\s*(?:NIL|NONE|NO\\s+WARNINGS?)\\b.*$","");if(clean.trim().isEmpty())return out;Pattern p=Pattern.compile("(?im)^\\s*(?:WARNING\\s+(?:NR\\.?\\s*)?\\d{1,4}(?:/\\d{2,4})?|(?:GALE|STORM|HURRICANE|TYPHOON|TSUNAMI)\\s+WARNING\\b)");Matcher m=p.matcher(clean);List<Integer> starts=new ArrayList<>();while(m.find())starts.add(m.start());if(starts.isEmpty()){out.add(clean.trim());return out;}for(int i=0;i<starts.size();i++){int a=starts.get(i),bb=i+1<starts.size()?starts.get(i+1):clean.length();String q=clean.substring(a,bb).trim();if(!q.isEmpty())out.add(q);}return out;}
    private static boolean wmoHazardGeometry(String label,String text){if(text==null||text.trim().isEmpty())return false;String L=label==null?"":label;if(Pattern.compile("(?i)\\bFORECAST\\b").matcher(L).find()&&Pattern.compile("(?is)\\b(?:WARNINGS?|STORM\\s+WARNING|GALE\\s+WARNING)\\b\\s*[:=-]?\\s*(?:NIL|NONE|NO\\s+WARNINGS?)\\b").matcher(text).find())return false;if(Pattern.compile("(?is)\\b(?:DANGER\\s+)?AREA\\s+(?:BOUND|BOUNDED|DEFINED|ENCLOSED|BETWEEN|WITHIN)|\\bBOUNDAR(?:Y|IES)\\b").matcher(text).find())return true;boolean special=Pattern.compile("(?i)TROPICAL\\s+(?:CYCLONE|STORM)|HURRICANE|TYPHOON|VOLCANIC\\s+ASH|TSUNAMI").matcher(L).find();boolean coord=Pattern.compile("(?i)\\b\\d{1,2}(?:[- °]\\d{1,2}(?:\\.\\d+)?)?\\s*[NS].{0,8}\\d{1,3}(?:[- °]\\d{1,2}(?:\\.\\d+)?)?\\s*[EW]\\b").matcher(text).find();return special&&coord;}

    private JSONObject textRecord(String id,SourceDef d,String raw,boolean forceReviewOnly)throws Exception{String cancel=parseCancelUtc(raw);return new JSONObject().put("id",id).put("number",inferNumber(id+" "+raw)).put("source",d.name).put("country",d.country).put("area",d.area).put("subject",subject(raw,id)).put("raw",raw.length()>50000?raw.substring(0,50000):raw).put("sourceUrl",d.url).put("channel",d.channel).put("trust","OFFICIAL").put("verifiedAt",isoNow()).put("cancelUtc",cancel).put("forceReviewOnly",forceReviewOnly).put("reason",forceReviewOnly?"Reference-only official source; geometry suppressed by policy.":"Official warning text retained; ASTRO safe geometry parser may promote only explicit clean coordinates.");}

    private static final class SeaResult {String status,mode,message;int count;JSONArray geometry=new JSONArray(),text=new JSONArray();}
    private SeaResult updateSeaLagom(){SeaResult r=new SeaResult();String token=tokenStore.getToken();if(token==null||token.trim().isEmpty()){r.status="SKIPPED_NO_TOKEN";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="No token stored in Android Keystore.";return r;}String[] full={"https://www.sealagom.com/api/v1/navarea/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true","https://www.sealagom.com/api/v1/coastal/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true"};String[] basic={"https://www.sealagom.com/api/v1/navarea/?include_messages=true","https://www.sealagom.com/api/v1/coastal/?include_messages=true"};try{for(int i=0;i<2;i++)parseSeaPaged(full[i],token,i==0?"NAVAREA":"COASTAL",r);r.status="OK_FULL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID_FULL";r.message="Full API sync completed with structured geometry when supplied.";return r;}catch(Exception fullErr){r.geometry=new JSONArray();r.text=new JSONArray();r.count=0;try{for(int i=0;i<2;i++)parseSeaPaged(basic[i],token,i==0?"NAVAREA":"COASTAL",r);r.status="OK_BASIC";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID_BASIC";r.message="Basic live-message fallback succeeded. Full geometry unavailable: "+safeMessage(fullErr);return r;}catch(Exception basicErr){r.status="ERROR_NONFATAL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="Full failed: "+safeMessage(fullErr)+" | Basic failed: "+safeMessage(basicErr);return r;}}}
    private void parseSeaPaged(String first,String token,String channel,SeaResult out)throws Exception{String next=first;int guard=0;while(next!=null&&!next.isEmpty()&&guard<25){HttpResult h=httpGet(next,token,"application/json");JSONObject root=new JSONObject(h.body);parseSeaRoot(root,channel,first,out);String n=root.optString("next","");if(n==null||n.trim().isEmpty()||"null".equalsIgnoreCase(n.trim()))break;if(n.startsWith("/"))n="https://www.sealagom.com"+n;next=n;guard++;}}
    private void parseSeaRoot(JSONObject root,String channel,String sourceUrl,SeaResult out)throws Exception{JSONArray regions=root.optJSONArray("results");if(regions==null)regions=root.optJSONArray("data");if(regions==null)return;for(int i=0;i<regions.length();i++){JSONObject region=regions.optJSONObject(i);if(region==null)continue;String title=opt(region,"title",opt(region,"name",channel)),area=navareaFromTitle(title);JSONArray msgs=region.optJSONArray("active_messages");if(msgs==null)msgs=region.optJSONArray("messages");if(msgs==null)continue;for(int j=0;j<msgs.length();j++){JSONObject msg=msgs.optJSONObject(j);if(msg==null)continue;String number=opt(msg,"number",String.valueOf(msg.opt("id"))),content=opt(msg,"content",opt(msg,"content_text",opt(msg,"message",""))),id="SEALAGOM "+channel+" "+title+" "+number,verified=opt(msg,"added_on",isoNow()),cancel=opt(msg,"cancel_date","");boolean had=false;JSONObject gf=msg.optJSONObject("geo_features");JSONArray features=gf==null?null:gf.optJSONArray("features");if(features!=null)for(int k=0;k<features.length();k++){JSONObject f=features.optJSONObject(k);if(f==null)continue;JSONArray pts=normalizePoints(f.optJSONArray("points"));if(pts.length()==0)continue;out.geometry.put(new JSONObject().put("id",id+(features.length()>1?" #"+(k+1):"")).put("source","SeaLagom API · "+title).put("area",area).put("subject",number+" · "+shortText(content)).put("kind",opt(f,"kind","point")).put("points",pts).put("verifiedAt",verified).put("cancelUtc",cancel).put("channel",channel).put("trust","SUPPLEMENTAL").put("sourceUrl",sourceUrl).put("raw",content));out.count++;had=true;}if(!had){JSONArray pts=coordinatePoints(msg.opt("coordinates"));if(pts.length()>0){out.geometry.put(new JSONObject().put("id",id).put("source","SeaLagom API · "+title).put("area",area).put("subject",number+" · "+shortText(content)).put("kind",pts.length()>=3?"area":pts.length()>=2?"line":"point").put("points",pts).put("verifiedAt",verified).put("cancelUtc",cancel).put("channel",channel).put("trust","SUPPLEMENTAL").put("sourceUrl",sourceUrl).put("raw",content));out.count++;had=true;}}if(!had){out.text.put(new JSONObject().put("id",id).put("number",number).put("source","SeaLagom API · "+title).put("area",area).put("subject",number+" · "+shortText(content)).put("raw",content).put("reason","No safe structured geometry in API response. ASTRO MSI2 safe text parser may promote only explicit clean geometry.").put("sourceUrl",sourceUrl).put("channel",channel).put("trust","SUPPLEMENTAL").put("verifiedAt",verified).put("cancelUtc",cancel).put("country",title).put("forceReviewOnly",false));out.count++;}}}}

    private JSONObject cleanup(JSONArray warnings,JSONArray text,JSONArray oldExpired)throws Exception{LinkedHashMap<String,JSONObject> map=new LinkedHashMap<>();Map<String,Boolean> geom=new HashMap<>();int dup=0,exp=0;JSONArray expired=oldExpired==null?new JSONArray():oldExpired;for(int pass=0;pass<2;pass++){JSONArray a=pass==0?warnings:text;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(isExpired(o)){exp++;if(expired.length()<1000)expired.put(o);continue;}String k=fingerprint(o);JSONObject old=map.get(k);boolean g=pass==0;if(old==null){map.put(k,o);geom.put(k,g);}else{dup++;if(rank(o)+(g?3:0)>rank(old)+(Boolean.TRUE.equals(geom.get(k))?3:0)){map.put(k,o);geom.put(k,g);}}}}JSONArray w=new JSONArray(),t=new JSONArray();for(Map.Entry<String,JSONObject> e:map.entrySet()){if(Boolean.TRUE.equals(geom.get(e.getKey())))w.put(e.getValue());else t.put(e.getValue());}return new JSONObject().put("warnings",w).put("textWarnings",t).put("expiredWarnings",expired).put("duplicatesSuppressed",dup).put("expiredSuppressed",exp);}
    private int rank(JSONObject o){String t=o.optString("trust","").toUpperCase(Locale.US);return t.startsWith("OFFICIAL")?4:t.contains("MANUAL")?3:t.contains("SUPPLEMENTAL")?2:1;}
    private boolean isExpired(JSONObject o){String c=o.optString("cancelUtc",o.optString("cancelDate",""));if(c.trim().isEmpty())return false;try{return java.time.Instant.parse(c).toEpochMilli()<=System.currentTimeMillis();}catch(Throwable e){try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.parse(c).getTime()<=System.currentTimeMillis();}catch(Exception ignored){return false;}}}
    private String fingerprint(JSONObject o){String num=o.optString("number",inferNumber(o.optString("id","")+" "+o.optString("raw",o.optString("text",""))));String raw=o.optString("raw",o.optString("text",o.optString("subject",""))).toUpperCase(Locale.US).replaceAll("\\s+"," ").replaceAll("[^A-Z0-9./ -]","").trim();String base=num.toUpperCase(Locale.US).replaceAll("\\s+","")+"|"+(raw.length()>1200?raw.substring(0,1200):raw);return sha256(base);}
    private static String sha256(String s){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(byte q:b)x.append(String.format(Locale.US,"%02x",q));return x.toString();}catch(Exception e){return s;}}

    JSONObject loadCache() throws Exception {
        JSONObject seed=loadBundledSeed(); File f=new File(context.getFilesDir(),CACHE_FILE); if(!f.exists()) return seed;
        JSONObject local=new JSONObject(readAll(new FileInputStream(f))); if(local.optBoolean("v79SeedMerged",false)) return local;
        JSONArray lw=local.optJSONArray("warnings");if(lw==null){lw=new JSONArray();local.put("warnings",lw);}JSONArray lt=local.optJSONArray("textWarnings");if(lt==null){lt=new JSONArray();local.put("textWarnings",lt);}JSONArray ls=local.optJSONArray("sources");if(ls==null){ls=new JSONArray();local.put("sources",ls);}
        JSONArray sw=seed.optJSONArray("warnings"),st=seed.optJSONArray("textWarnings"),ss=seed.optJSONArray("sources");
        if(sw!=null)for(int i=0;i<sw.length();i++){JSONObject o=sw.optJSONObject(i);if(o!=null&&o.optString("trust","").toUpperCase(Locale.US).startsWith("OFFICIAL"))lw.put(o);}if(st!=null)for(int i=0;i<st.length();i++){JSONObject o=st.optJSONObject(i);if(o!=null&&o.optString("trust","").toUpperCase(Locale.US).startsWith("OFFICIAL"))lt.put(o);}if(ss!=null)for(int i=0;i<ss.length();i++){JSONObject o=ss.optJSONObject(i);if(o!=null)ls=replaceStatus(ls,o);}
        local.put("sources",ls).put("v79SeedMerged",true).put("v79SeedMergedUtc",isoNow());JSONObject cleaned=cleanup(lw,lt,local.optJSONArray("expiredWarnings"));local.put("warnings",cleaned.getJSONArray("warnings")).put("textWarnings",cleaned.getJSONArray("textWarnings")).put("expiredWarnings",cleaned.getJSONArray("expiredWarnings"));saveCache(local);return local;
    }
    private JSONObject loadBundledSeed() throws Exception {byte[] ascii=readBytes(context.getAssets().open("world_msi_seed.json.gz.b64"),2*1024*1024);byte[] gz=android.util.Base64.decode(ascii,android.util.Base64.DEFAULT);try(GZIPInputStream zin=new GZIPInputStream(new java.io.ByteArrayInputStream(gz))){return new JSONObject(readAll(zin));}}
    void saveCache(JSONObject cache)throws Exception{try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),CACHE_FILE),false)){out.write(cache.toString().getBytes(StandardCharsets.UTF_8));}}
    void importCache(InputStream in)throws Exception{JSONObject c=new JSONObject(readAll(in));if(!c.has("warnings")||!c.has("sources"))throw new IllegalArgumentException("Not a WORLD MSI cache JSON file.");saveCache(c);}
    byte[] cacheBytes()throws Exception{return loadCache().toString().getBytes(StandardCharsets.UTF_8);}

    private JSONArray removeBySource(JSONArray a,SourceDef d)throws Exception{JSONArray out=new JSONArray();String cu=cleanUrl(d.url);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String u=cleanUrl(o.optString("sourceUrl","")),ss=o.optString("source","");if((!cu.isEmpty()&&cu.equals(u))||ss.equalsIgnoreCase(d.name))continue;out.put(o);}return out;}
    private JSONArray removeByMarker(JSONArray a,String marker)throws Exception{JSONArray out=new JSONArray();String m=marker.toUpperCase(Locale.US);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;String h=(o.optString("source","")+" "+o.optString("id","")).toUpperCase(Locale.US);if(!h.contains(m))out.put(o);}return out;}
    private JSONArray replaceStatus(JSONArray a,JSONObject fresh)throws Exception{JSONArray out=new JSONArray();String name=fresh.optString("name","");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name","").equalsIgnoreCase(name))out.put(o);}out.put(fresh);return out;}
    private JSONArray removeStatusMarker(JSONArray a,String marker)throws Exception{JSONArray out=new JSONArray();String m=marker.toUpperCase(Locale.US);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name","").toUpperCase(Locale.US).contains(m))out.put(o);}return out;}
    private JSONObject statusObject(String name,String url,String status,String mode,int count,String msg)throws Exception{return new JSONObject().put("name",name).put("url",url).put("status",status).put("mode",mode).put("fetchedUtc",isoNow()).put("count",count).put("message",msg==null?"":msg);}

    private static final class HttpResult{String body;int status,bytes;HttpResult(String b,int s,int n){body=b;status=s;bytes=n;}}
    private static HttpResult httpGet(String u,String token,String accept)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(CONNECT_MS);c.setReadTimeout(READ_MS);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept",accept);c.setRequestProperty("User-Agent","BridgeAstroMobile/0.2.0");if(token!=null&&!token.isEmpty())c.setRequestProperty("X-API-Token",token);int st=c.getResponseCode();InputStream in=st>=200&&st<300?c.getInputStream():c.getErrorStream();byte[] b=in==null?new byte[0]:readBytes(in,MAX_BODY);String body=new String(b,StandardCharsets.UTF_8);if(st<200||st>=300)throw new Exception("HTTP "+st+(body.isEmpty()?"":" · "+shortText(body)));return new HttpResult(body,st,b.length);}
    private static String plain(String html){if(html==null)return"";String x=html.replaceAll("(?is)<script[^>]*>.*?</script>"," ").replaceAll("(?is)<style[^>]*>.*?</style>"," ").replaceAll("(?i)<br\\s*/?>","\n").replaceAll("(?i)</(?:p|div|tr|li|h[1-6]|pre)>","\n").replaceAll("(?s)<[^>]+>"," ");try{x=Html.fromHtml(x,Html.FROM_HTML_MODE_LEGACY).toString();}catch(Throwable ignored){}return x.replace('\u00a0',' ').replaceAll("[\\t ]+"," ").replaceAll("(?:\\r?\\n){3,}","\n\n").trim();}
    private static String subject(String raw,String id){String[] ls=raw.split("\\r?\\n");for(String q:ls){String l=q.trim();if(l.length()<8)continue;if(l.toUpperCase(Locale.US).contains(id.toUpperCase(Locale.US)))continue;if(l.matches("(?i)^(SECURITE|ZCZC|NNNN|FM\\s|DTG\\s).*$"))continue;return l.length()>220?l.substring(0,220):l;}return"NAV WARNING";}
    private static String inferNumber(String s){Matcher m=Pattern.compile("(?i)\\b(?:NAVAREA\\s+[IVXLC0-9]+\\s+|NAVWARN\\s+|WARNING\\s+|AUSCOAST\\s+WARNING\\s+)?([A-Z]?\\d{1,5}[/\\-]\\d{2,4})\\b").matcher(s);return m.find()?m.group(1).replace('-','/'):"";}
    private static String parseCancelUtc(String s){Matcher m=Pattern.compile("(?i)\\bCANCEL(?:\\s+THIS)?\\s+(?:MSG|MESSAGE|WARNING)?(?:\\s+DTG)?\\s*(\\d{2})(\\d{2})(\\d{2})\\s*(?:UTC|Z)?\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(?:\\s+(\\d{2,4}))?\\b").matcher(s);if(!m.find())return"";try{Map<String,Integer> mo=new HashMap<>();String[] ms={"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};for(int i=0;i<12;i++)mo.put(ms[i],i);java.util.Calendar c=java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));int y=c.get(java.util.Calendar.YEAR),month=mo.get(m.group(4).toUpperCase(Locale.US));if(m.group(5)!=null){y=Integer.parseInt(m.group(5));if(y<100)y+=2000;}c.clear();c.set(y,month,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),0);SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(c.getTime());}catch(Exception e){return"";}}
    private static String flattenJsonValue(Object v){if(v==null||v==JSONObject.NULL)return"";if(v instanceof String)return((String)v).trim();if(v instanceof JSONArray){StringBuilder b=new StringBuilder();JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)b.append(flattenJsonValue(a.opt(i))).append('\n');return b.toString().trim();}if(v instanceof JSONObject){JSONObject o=(JSONObject)v;List<String> ks=new ArrayList<>();java.util.Iterator<String> it=o.keys();while(it.hasNext())ks.add(it.next());Collections.sort(ks);StringBuilder b=new StringBuilder();for(String k:ks)b.append(flattenJsonValue(o.opt(k))).append('\n');return b.toString().trim();}return String.valueOf(v);}
    private static JSONArray normalizePoints(JSONArray in)throws Exception{JSONArray out=new JSONArray();if(in==null)return out;for(int i=0;i<in.length();i++){JSONObject p=in.optJSONObject(i);if(p==null)continue;double lat=p.optDouble("lat",Double.NaN),lon=p.optDouble("lon",Double.NaN);if(Double.isNaN(lon))lon=p.optDouble("lng",Double.NaN);if(!Double.isNaN(lat)&&!Double.isNaN(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180)out.put(new JSONObject().put("lat",lat).put("lon",lon));}return out;}
    private static JSONArray coordinatePoints(Object c)throws Exception{JSONArray out=new JSONArray();if(!(c instanceof JSONObject))return out;JSONArray d=((JSONObject)c).optJSONArray("decimal_coordinates");if(d==null)return out;for(int i=0;i<d.length();i++){JSONArray p=d.optJSONArray(i);if(p==null||p.length()<2)continue;double lat=p.optDouble(0,Double.NaN),lon=p.optDouble(1,Double.NaN);if(!Double.isNaN(lat)&&!Double.isNaN(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180)out.put(new JSONObject().put("lat",lat).put("lon",lon));}return out;}
    private static String navareaFromTitle(String t){Matcher m=Pattern.compile("(?i)NAVAREA\\s+([IVXLC]+|\\d{1,2})").matcher(t);return m.find()?roman(m.group(1)):"";}
    private static String roman(String x){if(x==null)return"";String u=x.toUpperCase(Locale.US).replace("NAVAREA","").replace("METAREA","").trim();String suffix="";if(u.endsWith("N")||u.endsWith("S")){suffix="-"+u.substring(u.length()-1);u=u.substring(0,u.length()-1);}try{int n=Integer.parseInt(u);String[] r={"","I","II","III","IV","V","VI","VII","VIII","IX","X","XI","XII","XIII","XIV","XV","XVI","XVII","XVIII","XIX","XX","XXI"};return n>=1&&n<r.length?r[n]+suffix:x;}catch(Exception e){return u+suffix;}}
    private static String yearNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static String isoNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static String opt(JSONObject o,String k,String d){String v=o.optString(k,"");return v==null||v.trim().isEmpty()||"null".equalsIgnoreCase(v.trim())?d:v.trim();}
    private static String shortText(String s){if(s==null)return"";s=s.replaceAll("\\s+"," ").trim();return s.length()>180?s.substring(0,177)+"…":s;}
    private static String safeMessage(Throwable e){if(e==null)return"unknown error";String m=e.getMessage();if(m==null||m.trim().isEmpty())m=e.toString();return shortText(m);}
    private static String cleanUrl(String s){return s==null?"":s.replaceAll("[?#].*$","").replaceAll("/$","");}
    private static byte[] readBytes(InputStream in,int max)throws Exception{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))>=0){total+=n;if(total>max)throw new Exception("Response exceeds "+max+" bytes");out.write(b,0,n);}return out.toByteArray();}}
    private static String readAll(InputStream in)throws Exception{return new String(readBytes(in,MAX_BODY),StandardCharsets.UTF_8);}
}
