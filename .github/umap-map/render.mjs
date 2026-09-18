import { chromium } from "playwright";
import fs from "node:fs";
import path from "node:path";

const OUT = path.resolve("rendered-map");
fs.mkdirSync(OUT, { recursive: true });

const STYLE = "https://tiles.openwaters.io/seamap/style.json";
const views = [
  // Wide contextual sections along the voyage corridor.
  {id:"01_nw_australia_to_lombok", name:"NW Australia → Lombok", bbox:[113.8,-21.8,118.9,-7.4], w:4096,h:3072, fmt:"jpeg"},
  {id:"02_lombok_makassar", name:"Lombok → Makassar Strait", bbox:[115.0,-9.4,120.2,-1.0], w:4096,h:3072, fmt:"jpeg"},
  {id:"03_celebes_sibutu", name:"Celebes Sea → Sibutu", bbox:[117.0,-2.2,121.4,6.8], w:4096,h:3072, fmt:"jpeg"},
  {id:"04_sulu_mindoro", name:"Sulu Sea → Mindoro", bbox:[118.0,4.5,122.8,14.8], w:4096,h:3072, fmt:"jpeg"},
  {id:"05_luzon_east_taiwan", name:"Luzon Strait → East Taiwan", bbox:[120.0,13.5,124.7,26.4], w:4096,h:3072, fmt:"jpeg"},
  {id:"06_taiwan_to_shandong", name:"East Taiwan → Shandong", bbox:[119.0,24.0,124.5,36.5], w:4096,h:3072, fmt:"jpeg"},

  // High-detail route choke points.
  {id:"10_lombok_detail", name:"Lombok Strait — Detail", bbox:[115.45,-9.15,116.25,-7.75], w:4096,h:4096, fmt:"png"},
  {id:"11_sibutu_detail", name:"Sibutu Passage / Tawi-Tawi — Detail", bbox:[118.65,4.10,120.20,6.05], w:4096,h:4096, fmt:"png"},
  {id:"12_mindoro_detail", name:"Mindoro / Apo / Cuyo — Detail", bbox:[119.4,9.2,121.8,14.1], w:4096,h:4096, fmt:"jpeg"},
  {id:"13_luzon_taiwan_detail", name:"Luzon Strait / East Taiwan — Detail", bbox:[120.5,19.3,123.4,25.8], w:4096,h:4096, fmt:"jpeg"},

  // Departure: two nested scales.
  {id:"20_port_hedland_outer", name:"Port Hedland — Outer Approaches", bbox:[118.18,-20.55,118.90,-19.72], w:4096,h:4096, fmt:"png"},
  {id:"21_port_hedland_inner", name:"Port Hedland — Harbour / Channel Detail", bbox:[118.46,-20.43,118.68,-20.22], w:4096,h:4096, fmt:"png"},

  // Arrival: two nested scales.
  {id:"30_lanshan_outer", name:"Lanshan / Rizhao — Outer Approaches", bbox:[118.75,34.55,121.05,35.85], w:4096,h:4096, fmt:"png"},
  {id:"31_lanshan_inner", name:"Lanshan — Harbour / Arrival Detail", bbox:[119.22,34.83,119.90,35.36], w:4096,h:4096, fmt:"png"}
];

const browser = await chromium.launch({
  headless: true,
  args: ["--disable-dev-shm-usage","--no-sandbox","--use-gl=angle","--use-angle=swiftshader"]
});

const manifest = [];
const failures = [];

async function renderView(v) {
  const page = await browser.newPage({viewport:{width:v.w,height:v.h}, deviceScaleFactor:1});
  const errors = [];
  page.on("console", msg => {
    if (msg.type() === "error") errors.push(msg.text());
  });
  page.on("pageerror", e => errors.push(String(e)));

  await page.setContent(\`<!doctype html>
<html><head><meta charset="utf-8">
<style>
html,body,#map{margin:0;width:100%;height:100%;overflow:hidden;background:#dcebf0}
.maplibregl-ctrl-logo,.maplibregl-ctrl-attrib{display:none!important}
#mark{position:absolute;right:12px;bottom:10px;z-index:999;background:rgba(255,255,255,.86);
color:#1d2830;border:1px solid rgba(0,0,0,.28);padding:5px 9px;border-radius:3px;
font:600 13px/1.25 Arial,sans-serif;letter-spacing:.2px}
</style></head>
<body><div id="map"></div><div id="mark">REFERENCE ONLY · NOT FOR NAVIGATION · NOT AN ENC<br>
<span style="font-weight:400">© Open Waters: Seamap · © OpenStreetMap contributors</span></div></body></html>\`);

  await page.addStyleTag({path:"node_modules/maplibre-gl/dist/maplibre-gl.css"});
  await page.addScriptTag({path:"node_modules/maplibre-gl/dist/maplibre-gl.js"});

  const result = await page.evaluate(async ({style,bbox}) => {
    return await new Promise((resolve,reject) => {
      const map = new maplibregl.Map({
        container:"map",
        style,
        attributionControl:false,
        preserveDrawingBuffer:true,
        fadeDuration:0,
        pitch:0,
        bearing:0
      });
      window.__map = map;
      let done = false;
      const finish = () => {
        if(done) return; done=true;
        const b = map.getBounds();
        resolve({
          bbox:[b.getWest(),b.getSouth(),b.getEast(),b.getNorth()],
          zoom:map.getZoom(),
          center:[map.getCenter().lng,map.getCenter().lat],
          styleName:map.getStyle()?.name || ""
        });
      };
      map.on("error", ev => {
        // Individual missing tiles should not abort the whole render.
        console.error("MAP:", ev?.error?.message || ev?.error || ev);
      });
      map.on("load", () => {
        map.fitBounds([[bbox[0],bbox[1]],[bbox[2],bbox[3]]], {padding:0, duration:0});
        map.resize();
        map.once("idle", () => setTimeout(finish, 2500));
        setTimeout(finish, 30000);
      });
      setTimeout(() => reject(new Error("map load timeout")), 60000);
    });
  }, {style:STYLE,bbox:v.bbox});

  // Give glyphs/sprites one last frame after idle.
  await page.waitForTimeout(1000);
  const ext = v.fmt === "png" ? "png" : "jpg";
  const file = \`\${v.id}.\${ext}\`;
  const out = path.join(OUT,file);
  if(v.fmt === "png") {
    await page.screenshot({path:out,type:"png"});
  } else {
    await page.screenshot({path:out,type:"jpeg",quality:95});
  }
  const stat = fs.statSync(out);
  manifest.push({
    id:v.id,name:v.name,file,
    bbox:result.bbox,requested_bbox:v.bbox,
    center:result.center,zoom:result.zoom,
    width:v.w,height:v.h,format:v.fmt,
    bytes:stat.size,console_errors:errors.slice(0,20)
  });
  console.log(\`\${v.id}: \${(stat.size/1048576).toFixed(2)} MiB z=\${result.zoom.toFixed(2)}\`);
  await page.close();
}

for (const v of views) {
  try { await renderView(v); }
  catch (e) {
    console.error("FAILED",v.id,e);
    failures.push({id:v.id,error:String(e)});
  }
}
await browser.close();

fs.writeFileSync(path.join(OUT,"tiles.json"), JSON.stringify({
  created:new Date().toISOString(),
  source_style:STYLE,
  source:"Open Waters: Seamap + OpenStreetMap contributors + Seascape bathymetry",
  disclaimer:"REFERENCE ONLY — NOT FOR NAVIGATION — NOT AN ENC",
  tiles:manifest,
  failures
},null,2));

fs.writeFileSync(path.join(OUT,"SOURCE_INFO.txt"),
\`PH → LANSHAN FULL NAUTICAL REFERENCE RASTER SET
Generated from: Open Waters: Seamap
Source style: \${STYLE}

Map content is rendered from open marine datasets used by Open Waters:
- OpenStreetMap / OpenSeaMap-tagged features for coastline, harbours and seamarks
- Seascape for bathymetric shading, contours and soundings
- Open Waters nautical symbology

IMPORTANT:
REFERENCE ONLY — NOT FOR NAVIGATION — NOT AN ENC.
Crowd-sourced/open bathymetric and seamark data may be incomplete, outdated or wrong.
Official corrected ENCs and current MSI/T&P/NAVTEX remain controlling onboard.

Attribution:
© Open Waters: Seamap — https://openwaters.io/charts/seamap
© OpenStreetMap contributors — ODbL
See upstream source/license information for additional source terms.
\`);

if (failures.length) {
  console.error("Some renders failed:", failures);
  process.exitCode = 2;
}
