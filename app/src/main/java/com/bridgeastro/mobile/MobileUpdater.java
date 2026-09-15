package com.bridgeastro.mobile;

import android.content.Context;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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

final class MobileUpdater {
    static final String CACHE_FILE = "world_msi_cache.json";
    static final String VERSION = "ANDROID-0.2.0-OFFICIAL-FIRST";
    private static final int CONNECT_MS = 9000, READ_MS = 16000, MAX_BODY = 5 * 1024 * 1024;
    private final Context context;
    private final TokenStore tokenStore;

    static final class Result {
        final boolean ok, partial; final int added; final String message;
        Result(boolean ok, boolean partial, int added, String message){this.ok=ok;this.partial=partial;this.added=added;this.message=message;}
        JSONObject toJson(){try{return new JSONObject().put("ok",ok).put("partial",partial).put("added",added).put("message",message);}catch(Exception e){return new JSONObject();}}
    }

    private static final class SourceDef {
        final String name,country,area,mode,url,parser,channel;
        SourceDef(String n,String c,String a,String m,String u,String p,String ch){name=n;country=c;area=a;mode=m;url=u;parser=p;channel=ch;}
    }
    private static final class SourceResult {
        final SourceDef def; final String status,message; final JSONArray records; final boolean authoritative;
        SourceResult(SourceDef d,String s,String m,JSONArray r,boolean a){def=d;status=s;message=m;records=r;authoritative=a;}
    }
    private static SourceDef s(String n,String c,String a,String m,String u,String p,String ch){return new SourceDef(n,c,a,m,u,p,ch);}

    private static final SourceDef[] OFFICIAL = new SourceDef[]{
        s("UKHO NAVAREA I / UK Coastal","United Kingdom","I","AUTO_OFFICIAL_WEB","https://msi.admiralty.co.uk/RadioNavigationalWarnings","UKHO","EGC/NAVTEX WEB EQUIVALENT"),
        s("NAVAREA II - France","France","II","AUTO_OFFICIAL_WEB","https://diffusion.shom.fr/pro/navarea-en-vigueur","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA III - Spain","Spain","III","AUTO_OFFICIAL_WEB","https://armada.defensa.gob.es/ArmadaPortal/page/Portal/ArmadaEspannola/cienciaihm1/prefLang-es/02ProductosServicios--02NAVAREAS","GENERIC","EGC WEB EQUIVALENT"),
        s("NGA NAVAREA IV / XII active warnings","United States","","AUTO_NGA_OFFICIAL_API","https://msi.nga.mil/api/publications/broadcast-warn?status=active&output=json","NGA","EGC/NAVAREA"),
        s("NAVAREA V - Brazil","Brazil","V","AUTO_OFFICIAL_WEB","https://www.marinha.mil.br/chm/dados-do-segnav-aviso-radio-nautico-tela/radio-navigational-warnings-and-sar-warnings","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VI - Argentina","Argentina","VI","AUTO_OFFICIAL_WEB","https://www.hidro.gov.ar/nautica/cnv.asp","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VII - South Africa","South Africa","VII","AUTO_OFFICIAL_WEB","https://www.sanho.co.za/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA VIII - India / NHO","India","VIII","AUTO_PARSED_CURRENT","https://hydrobharat.gov.in/navarea-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("Pakistan NAVAREA IX","Pakistan","IX","AUTO_OFFICIAL_WEB","https://hydrography.paknavy.gov.pk/navarea-ix-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("AMSA NAVAREA X / AUSCOAST","Australia","X","AUTO_PARSED","https://www.operations.amsa.gov.au/AMSA.Web.MSIPublication/","AMSA","EGC/NAVTEX WEB EQUIVALENT"),
        s("Japan JCG NAVAREA XI","Japan","XI","AUTO_OFFICIAL_WEB","https://www1.kaiho.mlit.go.jp/TUHO/keiho/navarea11_en.html","JCG","EGC/NAVAREA"),
        s("NAVAREA XIII - Russian Federation","Russian Federation","XIII","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XIV - New Zealand / Maritime NZ","New Zealand","XIV","AUTO_PARSED_CURRENT","https://www.maritimenz.govt.nz/navigational-warnings/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XV - Chile","Chile","XV","AUTO_OFFICIAL_WEB","https://www.shoa.mil.cl/","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XVI - Peru / DIHIDRONAV","Peru","XVI","AUTO_PARSED_CURRENT","https://www.dhn.mil.pe/portal/navarea/radioavisos-warnings","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XVII - Canada","Canada","XVII","AUTO_OFFICIAL_WEB","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("NAVAREA XVIII - Canada","Canada","XVIII","AUTO_OFFICIAL_WEB","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("NAVAREA XIX - Norway / Kystverket","Norway","XIX","AUTO_PARSED_CURRENT","https://kyvreports.kystverket.no/NavcoReport/navareaxixvarsler.aspx","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XX - Russian Federation","Russian Federation","XX","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("NAVAREA XXI - Russian Federation","Russian Federation","XXI","AUTO_OFFICIAL_WEB","https://structure.mil.ru/structure/forces/hydrographic/info/notices.htm","GENERIC","EGC WEB EQUIVALENT"),
        s("China MSA navigational warnings","China","XI","AUTO_OFFICIAL_NATIONAL","https://www.msa.gov.cn/page/outter/weather.jsp","CHINA","COASTAL/NAVWARN"),
        s("Canada NAVWARN published messages","Canada","","AUTO_OFFICIAL_NATIONAL","https://nis.ccg-gcc.gc.ca/public/rest/messages/en/search?maxHits=100&status=PUBLISHED&sortBy=DATE","CANADA","COASTAL/NAVWARN"),
        s("India NHO NAVTEX in-force PDF","India","VIII","AUTO_OFFICIAL_PDF","https://hydrobharat.gov.in/documents/d/guest/navtex-warnings-indian-coast","PDF","NAVTEX"),
        s("Philippines NAMRIA NAVPHIL","Philippines","XI","AUTO_OFFICIAL_PDF","https://namria.gov.ph/downloads.aspx","PDF_INDEX","COASTAL/NAVWARN")
    };

    MobileUpdater(Context context,TokenStore tokenStore){this.context=context.getApplicationContext();this.tokenStore=tokenStore;}

    Result update(){
        try{
            JSONObject cache=loadCache();
            JSONArray statuses=optArray(cache,"sources"), warnings=optArray(cache,"warnings"), text=optArray(cache,"textWarnings");
            int officialRecords=0, officialOk=0, officialErrors=0;
            for(SourceResult r:runOfficialParallel()){
                if(r.authoritative){warnings=removeBySource(warnings,r.def);text=removeBySource(text,r.def);}
                append(text,r.records);officialRecords+=r.records.length();
                if(r.status.startsWith("OK"))officialOk++;else if(r.status.contains("ERROR"))officialErrors++;
                statuses=replaceStatus(statuses,status(r.def.name,r.def.url,r.status,r.def.mode,r.records.length(),r.message));
            }
            for(SourceResult r:runWmoParallel()){
                if(r.authoritative){warnings=removeBySource(warnings,r.def);text=removeBySource(text,r.def);}
                append(text,r.records);officialRecords+=r.records.length();
                if(r.status.startsWith("OK"))officialOk++;else if(r.status.contains("ERROR"))officialErrors++;
                statuses=replaceStatus(statuses,status(r.def.name,r.def.url,r.status,r.def.mode,r.records.length(),r.message));
            }

            warnings=removeMarker(warnings,"SEALAGOM"); text=removeMarker(text,"SEALAGOM"); statuses=removeStatusMarker(statuses,"SEALAGOM");
            SeaResult sea=seaLagom(); append(warnings,sea.geometry); append(text,sea.text);
            statuses.put(status("SeaLagom API","https://www.sealagom.com/api/docs/",sea.status,sea.mode,sea.count,sea.message));

            JSONObject clean=cleanup(warnings,text,cache.optJSONArray("expiredWarnings"));
            cache.put("warnings",clean.getJSONArray("warnings"));cache.put("textWarnings",clean.getJSONArray("textWarnings"));cache.put("expiredWarnings",clean.getJSONArray("expiredWarnings"));cache.put("sources",statuses);
            cache.put("builtUtc",isoNow()).put("updaterVersion",VERSION);
            cache.put("summary",new JSONObject().put("officialRecordsThisRun",officialRecords).put("officialSourcesHealthy",officialOk).put("officialSourcesErrors",officialErrors).put("seaLagomRecordsThisRun",sea.count).put("duplicatesSuppressed",clean.getInt("duplicatesSuppressed")).put("expiredSuppressed",clean.getInt("expiredSuppressed")).put("activeRecords",clean.getJSONArray("warnings").length()+clean.getJSONArray("textWarnings").length()));
            saveCache(cache);
            boolean partial=officialErrors>0||!sea.status.startsWith("OK");
            return new Result(true,partial,officialRecords+sea.count,"Official-first refresh complete: "+officialOk+" official source(s) healthy, "+officialErrors+" error(s), "+officialRecords+" official record(s), SeaLagom "+sea.status+" "+sea.count+" record(s), "+clean.getInt("duplicatesSuppressed")+" duplicate(s) suppressed, "+clean.getInt("expiredSuppressed")+" expired record(s) excluded.");
        }catch(Exception e){return new Result(false,true,0,safe(e));}
    }

    private List<SourceResult> runOfficialParallel()throws Exception{
        ExecutorService ex=Executors.newFixedThreadPool(6);List<Future<SourceResult>> f=new ArrayList<>();
        for(final SourceDef d:OFFICIAL)f.add(ex.submit(new Callable<SourceResult>(){public SourceResult call(){return fetchOfficial(d);}}));
        List<SourceResult> out=new ArrayList<>();for(Future<SourceResult> x:f)try{out.add(x.get());}catch(Exception ignored){}ex.shutdownNow();return out;
    }
    private List<SourceResult> runWmoParallel()throws Exception{
        String[] a={"1","2","3","4","5","6","7","8N","8S","9","10","11","12","13","14","15","16","17","18","19","20","21"};
        ExecutorService ex=Executors.newFixedThreadPool(6);List<Future<SourceResult>> f=new ArrayList<>();
        for(final String t:a){final String area=roman(t),url="https://wwmiws.wmo.int/index.php/metareas/bulletinset_download/"+t+"/json";final SourceDef d=s("WMO WWMIWS METAREA "+area,"WMO/IMO",area,"WMO_OFFICIAL_BULLETINSET_JSON",url,"WMO","EGC/METAREA");f.add(ex.submit(new Callable<SourceResult>(){public SourceResult call(){return fetchOfficial(d);}}));}
        List<SourceResult> out=new ArrayList<>();for(Future<SourceResult> x:f)try{out.add(x.get());}catch(Exception ignored){}ex.shutdownNow();return out;
    }

    private SourceResult fetchOfficial(SourceDef d){
        try{
            HttpResult h=httpGet(d.url,null,"application/json,text/html,text/plain,*/*");JSONArray r;boolean auth=false;
            if("NGA".equals(d.parser)){r=parseNga(h.body,d);auth=true;}
            else if("WMO".equals(d.parser)){r=parseWmo(h.body,d);auth=true;}
            else if("UKHO".equals(d.parser)){r=parseUkho(h.body,d);auth=r.length()>0||h.body.contains("Radio Navigation Warnings");}
            else if("AMSA".equals(d.parser)){r=parseAmsa(h.body,d);auth=r.length()>0||h.body.contains("Maritime Safety Information current at");}
            else if("JCG".equals(d.parser)){r=parseJcg(h.body,d);auth=r.length()>0;}
            else if("CHINA".equals(d.parser)){r=parseChina(h.body,d);auth=r.length()>0;}
            else if("CANADA".equals(d.parser)){r=parseCanada(h.body,d);auth=r.length()>0;}
            else if("PDF".equals(d.parser))return new SourceResult(d,"FETCHED_PDF_REFERENCE","Official PDF endpoint reached ("+h.bytes+" bytes); previous verified v7.9 PDF-derived cache is retained rather than inventing unverified PDF text.",new JSONArray(),false);
            else if("PDF_INDEX".equals(d.parser))return new SourceResult(d,"FETCHED_INDEX_REFERENCE","Official NAMRIA download page reached; previous verified cache is retained when current PDFs cannot be safely extracted natively.",new JSONArray(),false);
            else {r=parseGeneric(h.body,d);auth=r.length()>0;}
            String st=r.length()>0?"OK":(auth?"OK_EMPTY":"FETCHED_NO_SAFE_MESSAGES");
            return new SourceResult(d,st,"Official source reached by Android HTTPS; "+r.length()+" current warning text record(s) parsed conservatively.",r,auth);
        }catch(Exception e){return new SourceResult(d,"ERROR","Official fetch failed: "+safe(e)+" · previous verified cache retained.",new JSONArray(),false);}
    }

    private JSONArray parseGeneric(String body,SourceDef d)throws Exception{
        JSONArray out=new JSONArray();if(d.area.isEmpty())return out;String t=plain(body);Pattern p=Pattern.compile("(?i)\\bNAVAREA\\s+"+Pattern.quote(d.area)+"\\s*(?:WARNING|WARNINGS|NW)?\\s*(?:NO\\.?|NR\\.?|NUMBER)?\\s*[:#-]?\\s*(\\d{1,4})[/\\-](\\d{2,4})\\b");Matcher m=p.matcher(t);List<Integer> st=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id="NAVAREA "+d.area+" "+m.group(1)+"/"+m.group(2);if(!ids.contains(id)){ids.add(id);st.add(m.start());}}
        for(int i=0;i<st.size();i++){int a=st.get(i),b=i+1<st.size()?st.get(i+1):Math.min(t.length(),a+8000);out.put(record(ids.get(i),d,t.substring(a,b),false));}return out;
    }
    private JSONArray parseUkho(String html,SourceDef d)throws Exception{
        JSONArray out=new JSONArray();Matcher m=Pattern.compile("(?is)<h2[^>]*id=\\\"Details_Reference_[^\\\"]*\\\"[^>]*>(.*?)</h2>.*?<pre[^>]*id=\\\"Details_Description_[^\\\"]*\\\"[^>]*>(.*?)</pre>").matcher(html);while(m.find()){String id=plain(m.group(1)).replaceAll("\\s+"," ").trim().toUpperCase(Locale.US),desc=plain(m.group(2));if(id.matches("^(NAVAREA\\s+I\\s+\\d{1,4}/\\d{2,4}|WZ\\s+\\d{1,4}/\\d{2,4})$"))out.put(record(id,d,id+"\n"+desc,false));}return out;
    }
    private JSONArray parseAmsa(String html,SourceDef d)throws Exception{
        String t=plain(html);JSONArray out=new JSONArray();Matcher m=Pattern.compile("(?i)\\b(NAVAREA\\s+X\\s+\\d{1,4}/\\d{2,4}|AUSCOAST\\s+WARNING\\s+\\d{1,4}/\\d{2,4})\\b").matcher(t);List<Integer> st=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id=m.group(1).toUpperCase(Locale.US).replaceAll("\\s+"," ");if(!ids.contains(id)){ids.add(id);st.add(m.start());}}for(int i=0;i<st.size();i++){int a=st.get(i),b=i+1<st.size()?st.get(i+1):Math.min(t.length(),a+10000);out.put(record(ids.get(i),d,t.substring(a,b),false));}return out;
    }
    private JSONArray parseJcg(String html,SourceDef d)throws Exception{
        JSONArray out=new JSONArray();Matcher m=Pattern.compile("(?i)TANA(?:;)?=(\\d{6})").matcher(html);List<String> ids=new ArrayList<>();while(m.find())if(!ids.contains(m.group(1)))ids.add(m.group(1));for(int i=0;i<Math.min(120,ids.size());i++){String x=ids.get(i),u="https://www1.kaiho.mlit.go.jp/TUHO/keiho/cgi/disp_warnings.cgi?TYPE=NAVAREA11&TANA="+x+"&LANG=EG";try{String t=plain(httpGet(u,null,"text/html,*/*").body);if(t.length()>20){SourceDef q=s(d.name,d.country,d.area,d.mode,u,d.parser,d.channel);out.put(record("NAVAREA XI "+x.substring(2)+"/"+x.substring(0,2),q,t,false));}}catch(Exception ignored){}}return out;
    }
    private JSONArray parseChina(String html,SourceDef d)throws Exception{
        String t=plain(html);JSONArray out=new JSONArray();Matcher m=Pattern.compile("(?i)\\b((?:ZJ|CE|SH|FJ|LYG|JS|GD|GX|LN|HB)\\s*\\d{1,4}/\\d{2})\\b").matcher(t);List<Integer> st=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id=m.group(1).replaceAll("\\s+","").toUpperCase(Locale.US);if(!ids.contains(id)){ids.add(id);st.add(m.start());}}for(int i=0;i<st.size();i++){int a=st.get(i),b=i+1<st.size()?st.get(i+1):Math.min(t.length(),a+8000);out.put(record(ids.get(i),d,t.substring(a,b),false));}return out;
    }
    private JSONArray parseCanada(String body,SourceDef d)throws Exception{
        String t=plain(body);JSONArray out=new JSONArray();Matcher m=Pattern.compile("(?i)\\b(NW-[A-Z]+-\\d{3,5}-\\d{2})\\b").matcher(t);List<Integer> st=new ArrayList<>();List<String> ids=new ArrayList<>();while(m.find()){String id=m.group(1).toUpperCase(Locale.US);if(!ids.contains(id)){ids.add(id);st.add(m.start());}}for(int i=0;i<st.size();i++){int a=st.get(i),b=i+1<st.size()?st.get(i+1):Math.min(t.length(),a+9000);out.put(record(ids.get(i),d,t.substring(a,b),false));}return out;
    }
    private JSONArray parseNga(String json,SourceDef d)throws Exception{
        JSONArray out=new JSONArray();Object root=new JSONTokener(json).nextValue();JSONArray a;if(root instanceof JSONObject){JSONObject o=(JSONObject)root;a=o.optJSONArray("broadcast-warn");if(a==null){a=new JSONArray();Object x=o.opt("broadcast-warn");if(x instanceof JSONObject)a.put(x);}}else a=(JSONArray)root;
        for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String st=x.optString("status","").toUpperCase(Locale.US);if(!st.isEmpty()&&!st.equals("A")&&!st.equals("ACTIVE"))continue;String txt=x.optString("text","");if(txt.trim().isEmpty())continue;String raw=x.optString("navArea",x.optString("area","")),area=roman(raw),num=x.optString("msgNumber",x.optString("number",String.valueOf(i+1))),yr=x.optString("msgYear",x.optString("year",yearNow())),bt=raw.equalsIgnoreCase("A")?"HYDROLANT":raw.equalsIgnoreCase("P")?"HYDROPAC":raw.equalsIgnoreCase("C")?"HYDROARC":"NAVAREA "+area;SourceDef q=s(d.name,d.country,area,d.mode,d.url,d.parser,d.channel);JSONObject r=record(bt+" "+num+"/"+yr,q,txt,false);r.put("issuedUtc",x.optString("issueDate",isoNow())).put("apiStatus",st).put("broadcastType",bt);out.put(r);}return out;
    }
    private JSONArray parseWmo(String json,SourceDef d)throws Exception{
        JSONObject o=new JSONObject(json);JSONArray b=o.optJSONArray("bulletin");if(b==null)throw new Exception("WMO response has no bulletin array");String issued=o.optString("date",isoNow());JSONArray out=new JSONArray();for(int i=0;i<b.length();i++){JSONObject x=b.optJSONObject(i);if(x==null)continue;String label=x.optString("label","WMO BULLETIN"),txt=flatten(x.opt("content"));if(txt.trim().isEmpty())continue;List<String> sec=wmoSections(label,txt);int n=0;for(String q:sec){if(!wmoHazard(label,q))continue;n++;JSONObject r=record("METAREA "+d.area+" "+(i+1)+" SEG "+n,d,q,false);r.put("issuedUtc",issued).put("bulletinLabel",label).put("reason","Official WMO warning section isolated by v7.9 safety policy; only explicit clean geometry may be promoted.");out.put(r);}if(n==0){JSONObject r=record("METAREA "+d.area+" "+(i+1),d,label+"\n"+txt,true);r.put("issuedUtc",issued).put("bulletinLabel",label).put("reason","Official WMO bulletin retained in full; forecast/general coverage is force-review-only.");out.put(r);}}return out;
    }

    private static List<String> wmoSections(String label,String text){List<String> out=new ArrayList<>();boolean direct=(Pattern.compile("(?i)\\b(?:WARNING|ADVISORY)\\b").matcher(label).find()&&!Pattern.compile("(?i)\\bFORECAST\\b").matcher(label).find())||Pattern.compile("(?i)TROPICAL\\s+(?:CYCLONE|STORM)|HURRICANE|TYPHOON|VOLCANIC\\s+ASH|TSUNAMI").matcher(label).find();if(direct)return splitWmo(text);Matcher m=Pattern.compile("(?is)(?:^|\\r?\\n)\\s*PART\\s*[- ]?(?:1|I)\\b(.*?)(?=(?:\\r?\\n\\s*PARTS?\\s*[- ]?(?:2|II)\\b)|\\z)").matcher(text);if(!m.find())m=Pattern.compile("(?is)(?:^|\\r?\\n)\\s*(?:GALE\\s+FORCE\\s+)?WARNINGS?\\s*[:=](.*?)(?=(?:\\r?\\n\\s*(?:SYNOPTIC|AREA\\s+FORECAST|FORECAST\\s+VALID|PARTS?\\s*[- ]?(?:2|II))\\b)|\\z)").matcher(text);if(m.find())return splitWmo(m.group(1));return out;}
    private static List<String> splitWmo(String s){List<String> out=new ArrayList<>();String c=s.replaceAll("(?im)^.*\\b(?:WARNINGS?|STORM\\s+WARNING|GALE\\s+WARNING)\\b\\s*[:=-]?\\s*(?:NIL|NONE|NO\\s+WARNINGS?)\\b.*$","");if(c.trim().isEmpty())return out;Matcher m=Pattern.compile("(?im)^\\s*(?:WARNING\\s+(?:NR\\.?\\s*)?\\d{1,4}(?:/\\d{2,4})?|(?:GALE|STORM|HURRICANE|TYPHOON|TSUNAMI)\\s+WARNING\\b)").matcher(c);List<Integer> st=new ArrayList<>();while(m.find())st.add(m.start());if(st.isEmpty()){out.add(c.trim());return out;}for(int i=0;i<st.size();i++){String q=c.substring(st.get(i),i+1<st.size()?st.get(i+1):c.length()).trim();if(!q.isEmpty())out.add(q);}return out;}
    private static boolean wmoHazard(String label,String text){if(Pattern.compile("(?i)\\bFORECAST\\b").matcher(label).find()&&Pattern.compile("(?is)\\b(?:WARNINGS?|STORM\\s+WARNING|GALE\\s+WARNING)\\b\\s*[:=-]?\\s*(?:NIL|NONE|NO\\s+WARNINGS?)\\b").matcher(text).find())return false;if(Pattern.compile("(?is)\\b(?:DANGER\\s+)?AREA\\s+(?:BOUND|BOUNDED|DEFINED|ENCLOSED|BETWEEN|WITHIN)|\\bBOUNDAR(?:Y|IES)\\b").matcher(text).find())return true;boolean sp=Pattern.compile("(?i)TROPICAL\\s+(?:CYCLONE|STORM)|HURRICANE|TYPHOON|VOLCANIC\\s+ASH|TSUNAMI").matcher(label).find(),co=Pattern.compile("(?i)\\b\\d{1,2}(?:[- °]\\d{1,2}(?:\\.\\d+)?)?\\s*[NS].{0,8}\\d{1,3}(?:[- °]\\d{1,2}(?:\\.\\d+)?)?\\s*[EW]\\b").matcher(text).find();return sp&&co;}

    private JSONObject record(String id,SourceDef d,String raw,boolean force)throws Exception{return new JSONObject().put("id",id).put("number",inferNumber(id+" "+raw)).put("source",d.name).put("country",d.country).put("area",d.area).put("subject",subject(raw,id)).put("raw",raw.length()>50000?raw.substring(0,50000):raw).put("sourceUrl",d.url).put("channel",d.channel).put("trust","OFFICIAL").put("verifiedAt",isoNow()).put("cancelUtc",cancelUtc(raw)).put("forceReviewOnly",force).put("reason",force?"Reference-only official source; geometry suppressed by policy.":"Official warning text retained; ASTRO safe geometry parser may promote only explicit clean coordinates.");}

    private static final class SeaResult{String status,mode,message;int count;JSONArray geometry=new JSONArray(),text=new JSONArray();}
    private SeaResult seaLagom(){SeaResult r=new SeaResult();String token=tokenStore.getToken();if(token==null||token.trim().isEmpty()){r.status="SKIPPED_NO_TOKEN";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="No token stored in Android Keystore.";return r;}String[] full={"https://www.sealagom.com/api/v1/navarea/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true","https://www.sealagom.com/api/v1/coastal/?include_messages=true&include_geo_features=true&include_coordinates=true&include_all=true"},basic={"https://www.sealagom.com/api/v1/navarea/?include_messages=true","https://www.sealagom.com/api/v1/coastal/?include_messages=true"};try{for(int i=0;i<2;i++)seaPaged(full[i],token,i==0?"NAVAREA":"COASTAL",r);r.status="OK_FULL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID_FULL";r.message="Full API sync completed with structured geometry when supplied.";return r;}catch(Exception a){r.geometry=new JSONArray();r.text=new JSONArray();r.count=0;try{for(int i=0;i<2;i++)seaPaged(basic[i],token,i==0?"NAVAREA":"COASTAL",r);r.status="OK_BASIC";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID_BASIC";r.message="Basic fallback succeeded; MSI2 safe text parser will promote explicit clean geometry. Full request unavailable: "+safe(a);return r;}catch(Exception b){r.status="ERROR_NONFATAL";r.mode="SUPPLEMENTAL_API_NATIVE_ANDROID";r.message="Full failed: "+safe(a)+" | Basic failed: "+safe(b);return r;}}}
    private void seaPaged(String first,String token,String channel,SeaResult out)throws Exception{String next=first;int guard=0;while(next!=null&&!next.isEmpty()&&guard<25){JSONObject root=new JSONObject(httpGet(next,token,"application/json").body);seaRoot(root,channel,first,out);String n=root.optString("next","");if(n==null||n.trim().isEmpty()||"null".equalsIgnoreCase(n.trim()))break;if(n.startsWith("/"))n="https://www.sealagom.com"+n;next=n;guard++;}}
    private void seaRoot(JSONObject root,String channel,String sourceUrl,SeaResult out)throws Exception{JSONArray regions=root.optJSONArray("results");if(regions==null)regions=root.optJSONArray("data");if(regions==null)return;for(int i=0;i<regions.length();i++){JSONObject region=regions.optJSONObject(i);if(region==null)continue;String title=opt(region,"title",opt(region,"name",channel)),area=navarea(title);JSONArray msgs=region.optJSONArray("active_messages");if(msgs==null)msgs=region.optJSONArray("messages");if(msgs==null)continue;for(int j=0;j<msgs.length();j++){JSONObject msg=msgs.optJSONObject(j);if(msg==null)continue;String number=opt(msg,"number",String.valueOf(msg.opt("id"))),content=opt(msg,"content",opt(msg,"content_text",opt(msg,"message",""))),id="SEALAGOM "+channel+" "+title+" "+number,verified=opt(msg,"added_on",isoNow()),cancel=opt(msg,"cancel_date","");boolean had=false;JSONObject gf=msg.optJSONObject("geo_features");JSONArray features=gf==null?null:gf.optJSONArray("features");if(features!=null)for(int k=0;k<features.length();k++){JSONObject f=features.optJSONObject(k);if(f==null)continue;JSONArray pts=points(f.optJSONArray("points"));if(pts.length()==0)continue;out.geometry.put(new JSONObject().put("id",id+(features.length()>1?" #"+(k+1):"")).put("source","SeaLagom API · "+title).put("area",area).put("subject",number+" · "+shortText(content)).put("kind",opt(f,"kind","point")).put("points",pts).put("verifiedAt",verified).put("cancelUtc",cancel).put("channel",channel).put("trust","SUPPLEMENTAL").put("sourceUrl",sourceUrl).put("raw",content));out.count++;had=true;}if(!had){JSONArray pts=coordPoints(msg.opt("coordinates"));if(pts.length()>0){out.geometry.put(new JSONObject().put("id",id).put("source","SeaLagom API · "+title).put("area",area).put("subject",number+" · "+shortText(content)).put("kind",pts.length()>=3?"area":pts.length()>=2?"line":"point").put("points",pts).put("verifiedAt",verified).put("cancelUtc",cancel).put("channel",channel).put("trust","SUPPLEMENTAL").put("sourceUrl",sourceUrl).put("raw",content));out.count++;had=true;}}if(!had){out.text.put(new JSONObject().put("id",id).put("number",number).put("source","SeaLagom API · "+title).put("country",title).put("area",area).put("subject",number+" · "+shortText(content)).put("raw",content).put("sourceUrl",sourceUrl).put("channel",channel).put("trust","SUPPLEMENTAL").put("verifiedAt",verified).put("cancelUtc",cancel).put("forceReviewOnly",false).put("reason","No structured API geometry; MSI2 parser may promote only explicit clean geometry."));out.count++;}}}}

    private JSONObject cleanup(JSONArray warnings,JSONArray text,JSONArray oldExpired)throws Exception{LinkedHashMap<String,JSONObject> map=new LinkedHashMap<>();Map<String,Boolean> geom=new HashMap<>();int dup=0,exp=0;JSONArray expired=oldExpired==null?new JSONArray():oldExpired;for(int pass=0;pass<2;pass++){JSONArray a=pass==0?warnings:text;for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if(expired(o)){exp++;if(expired.length()<1000)expired.put(o);continue;}String k=fingerprint(o);JSONObject old=map.get(k);boolean g=pass==0;if(old==null){map.put(k,o);geom.put(k,g);}else{dup++;if(rank(o)+(g?3:0)>rank(old)+(Boolean.TRUE.equals(geom.get(k))?3:0)){map.put(k,o);geom.put(k,g);}}}}JSONArray w=new JSONArray(),t=new JSONArray();for(Map.Entry<String,JSONObject> e:map.entrySet()){if(Boolean.TRUE.equals(geom.get(e.getKey())))w.put(e.getValue());else t.put(e.getValue());}return new JSONObject().put("warnings",w).put("textWarnings",t).put("expiredWarnings",expired).put("duplicatesSuppressed",dup).put("expiredSuppressed",exp);}
    private int rank(JSONObject o){String t=o.optString("trust","").toUpperCase(Locale.US);return t.startsWith("OFFICIAL")?4:t.contains("MANUAL")?3:t.contains("SUPPLEMENTAL")?2:1;}
    private boolean expired(JSONObject o){String c=o.optString("cancelUtc",o.optString("cancelDate",""));if(c.trim().isEmpty())return false;try{return java.time.Instant.parse(c).toEpochMilli()<=System.currentTimeMillis();}catch(Exception e){return false;}}
    private String fingerprint(JSONObject o){String num=o.optString("number",inferNumber(o.optString("id","")+" "+o.optString("raw",o.optString("text",""))));String raw=o.optString("raw",o.optString("text",o.optString("subject",""))).toUpperCase(Locale.US).replaceAll("\\s+"," ").replaceAll("[^A-Z0-9./ -]","").trim();return sha(num.toUpperCase(Locale.US).replaceAll("\\s+","")+"|"+(raw.length()>1200?raw.substring(0,1200):raw));}

    JSONObject loadCache()throws Exception{File f=new File(context.getFilesDir(),CACHE_FILE);String x=f.exists()?readAll(new FileInputStream(f)):readAll(context.getAssets().open("world_msi_seed.json"));return new JSONObject(x);}
    void saveCache(JSONObject c)throws Exception{try(FileOutputStream o=new FileOutputStream(new File(context.getFilesDir(),CACHE_FILE),false)){o.write(c.toString().getBytes(StandardCharsets.UTF_8));}}
    void importCache(InputStream in)throws Exception{JSONObject c=new JSONObject(readAll(in));if(!c.has("warnings")||!c.has("sources"))throw new IllegalArgumentException("Not a WORLD MSI cache JSON file.");saveCache(c);}
    byte[] cacheBytes()throws Exception{return loadCache().toString().getBytes(StandardCharsets.UTF_8);}

    private JSONArray removeBySource(JSONArray a,SourceDef d)throws Exception{JSONArray out=new JSONArray();String u=clean(d.url);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o==null)continue;if((!u.isEmpty()&&u.equals(clean(o.optString("sourceUrl",""))))||o.optString("source","").equalsIgnoreCase(d.name))continue;out.put(o);}return out;}
    private JSONArray removeMarker(JSONArray a,String m)throws Exception{JSONArray out=new JSONArray();String q=m.toUpperCase(Locale.US);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!((o.optString("source","")+" "+o.optString("id","")).toUpperCase(Locale.US).contains(q)))out.put(o);}return out;}
    private JSONArray replaceStatus(JSONArray a,JSONObject f)throws Exception{JSONArray out=new JSONArray();String n=f.optString("name","");for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name","").equalsIgnoreCase(n))out.put(o);}out.put(f);return out;}
    private JSONArray removeStatusMarker(JSONArray a,String m)throws Exception{JSONArray out=new JSONArray();String q=m.toUpperCase(Locale.US);for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&!o.optString("name","").toUpperCase(Locale.US).contains(q))out.put(o);}return out;}
    private static void append(JSONArray a,JSONArray b){for(int i=0;i<b.length();i++)a.put(b.opt(i));}
    private static JSONArray optArray(JSONObject o,String k){JSONArray a=o.optJSONArray(k);return a==null?new JSONArray():a;}
    private JSONObject status(String n,String u,String s,String m,int c,String msg)throws Exception{return new JSONObject().put("name",n).put("url",u).put("status",s).put("mode",m).put("fetchedUtc",isoNow()).put("count",c).put("message",msg==null?"":msg);}

    private static final class HttpResult{final String body;final int bytes;HttpResult(String b,int n){body=b;bytes=n;}}
    private static HttpResult httpGet(String u,String token,String accept)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(CONNECT_MS);c.setReadTimeout(READ_MS);c.setInstanceFollowRedirects(true);c.setRequestProperty("Accept",accept);c.setRequestProperty("User-Agent","BridgeAstroMobile/0.2.0");if(token!=null&&!token.isEmpty())c.setRequestProperty("X-API-Token",token);int st=c.getResponseCode();InputStream in=st>=200&&st<300?c.getInputStream():c.getErrorStream();byte[] b=in==null?new byte[0]:readBytes(in,MAX_BODY);String body=new String(b,StandardCharsets.UTF_8);if(st<200||st>=300)throw new Exception("HTTP "+st+(body.isEmpty()?"":" · "+shortText(body)));return new HttpResult(body,b.length);}
    private static String plain(String h){if(h==null)return"";String x=h.replaceAll("(?is)<script[^>]*>.*?</script>"," ").replaceAll("(?is)<style[^>]*>.*?</style>"," ").replaceAll("(?i)<br\\s*/?>","\n").replaceAll("(?i)</(?:p|div|tr|li|h[1-6]|pre)>","\n").replaceAll("(?s)<[^>]+>"," ");try{x=Html.fromHtml(x,Html.FROM_HTML_MODE_LEGACY).toString();}catch(Throwable ignored){}return x.replace('\u00a0',' ').replaceAll("[\\t ]+"," ").replaceAll("(?:\\r?\\n){3,}","\n\n").trim();}
    private static String subject(String raw,String id){for(String q:raw.split("\\r?\\n")){String l=q.trim();if(l.length()<8)continue;if(l.toUpperCase(Locale.US).contains(id.toUpperCase(Locale.US)))continue;if(l.matches("(?i)^(SECURITE|ZCZC|NNNN|FM\\s|DTG\\s).*$"))continue;return l.length()>220?l.substring(0,220):l;}return"NAV WARNING";}
    private static String inferNumber(String s){Matcher m=Pattern.compile("(?i)\\b(?:NAVAREA\\s+[IVXLC0-9]+\\s+|NAVWARN\\s+|WARNING\\s+|AUSCOAST\\s+WARNING\\s+)?([A-Z]?\\d{1,5}[/\\-]\\d{2,4})\\b").matcher(s);return m.find()?m.group(1).replace('-','/'):"";}
    private static String cancelUtc(String s){Matcher m=Pattern.compile("(?i)\\bCANCEL(?:\\s+THIS)?\\s+(?:MSG|MESSAGE|WARNING)?(?:\\s+DTG)?\\s*(\\d{2})(\\d{2})(\\d{2})\\s*(?:UTC|Z)?\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)(?:\\s+(\\d{2,4}))?\\b").matcher(s);if(!m.find())return"";try{String[] ms={"JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC"};int mon=0;for(;mon<12;mon++)if(ms[mon].equalsIgnoreCase(m.group(4)))break;java.util.Calendar c=java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));int y=c.get(java.util.Calendar.YEAR);if(m.group(5)!=null){y=Integer.parseInt(m.group(5));if(y<100)y+=2000;}c.clear();c.set(y,mon,Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)),0);SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(c.getTime());}catch(Exception e){return"";}}
    private static String flatten(Object v){if(v==null||v==JSONObject.NULL)return"";if(v instanceof String)return((String)v).trim();if(v instanceof JSONArray){StringBuilder b=new StringBuilder();JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++)b.append(flatten(a.opt(i))).append('\n');return b.toString().trim();}if(v instanceof JSONObject){JSONObject o=(JSONObject)v;List<String> k=new ArrayList<>();java.util.Iterator<String> it=o.keys();while(it.hasNext())k.add(it.next());Collections.sort(k);StringBuilder b=new StringBuilder();for(String q:k)b.append(flatten(o.opt(q))).append('\n');return b.toString().trim();}return String.valueOf(v);}
    private static JSONArray points(JSONArray in)throws Exception{JSONArray out=new JSONArray();if(in==null)return out;for(int i=0;i<in.length();i++){JSONObject p=in.optJSONObject(i);if(p==null)continue;double la=p.optDouble("lat",Double.NaN),lo=p.optDouble("lon",Double.NaN);if(Double.isNaN(lo))lo=p.optDouble("lng",Double.NaN);if(!Double.isNaN(la)&&!Double.isNaN(lo)&&Math.abs(la)<=90&&Math.abs(lo)<=180)out.put(new JSONObject().put("lat",la).put("lon",lo));}return out;}
    private static JSONArray coordPoints(Object c)throws Exception{JSONArray out=new JSONArray();if(!(c instanceof JSONObject))return out;JSONArray d=((JSONObject)c).optJSONArray("decimal_coordinates");if(d==null)return out;for(int i=0;i<d.length();i++){JSONArray p=d.optJSONArray(i);if(p==null||p.length()<2)continue;double la=p.optDouble(0,Double.NaN),lo=p.optDouble(1,Double.NaN);if(!Double.isNaN(la)&&!Double.isNaN(lo)&&Math.abs(la)<=90&&Math.abs(lo)<=180)out.put(new JSONObject().put("lat",la).put("lon",lo));}return out;}
    private static String navarea(String t){Matcher m=Pattern.compile("(?i)NAVAREA\\s+([IVXLC]+|\\d{1,2})").matcher(t);return m.find()?roman(m.group(1)):"";}
    private static String roman(String x){if(x==null)return"";String u=x.toUpperCase(Locale.US).replace("NAVAREA","").replace("METAREA","").trim(),s="";if(u.endsWith("N")||u.endsWith("S")){s="-"+u.substring(u.length()-1);u=u.substring(0,u.length()-1);}try{int n=Integer.parseInt(u);String[] r={"","I","II","III","IV","V","VI","VII","VIII","IX","X","XI","XII","XIII","XIV","XV","XVI","XVII","XVIII","XIX","XX","XXI"};return n>0&&n<r.length?r[n]+s:x;}catch(Exception e){return u+s;}}
    private static String yearNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static String isoNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static String opt(JSONObject o,String k,String d){String v=o.optString(k,"");return v==null||v.trim().isEmpty()||"null".equalsIgnoreCase(v.trim())?d:v.trim();}
    private static String shortText(String s){if(s==null)return"";s=s.replaceAll("\\s+"," ").trim();return s.length()>180?s.substring(0,177)+"…":s;}
    private static String safe(Throwable e){String m=e==null?"unknown error":e.getMessage();if(m==null||m.trim().isEmpty())m=String.valueOf(e);return shortText(m);}
    private static String clean(String s){return s==null?"":s.replaceAll("[?#].*$","").replaceAll("/$","");}
    private static String sha(String s){try{MessageDigest m=MessageDigest.getInstance("SHA-256");byte[] b=m.digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder x=new StringBuilder();for(byte q:b)x.append(String.format(Locale.US,"%02x",q));return x.toString();}catch(Exception e){return s;}}
    private static byte[] readBytes(InputStream in,int max)throws Exception{try(ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))>=0){total+=n;if(total>max)throw new Exception("Response exceeds "+max+" bytes");out.write(b,0,n);}return out.toByteArray();}}
    private static String readAll(InputStream in)throws Exception{return new String(readBytes(in,MAX_BODY),StandardCharsets.UTF_8);}
}
