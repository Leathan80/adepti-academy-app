#!/usr/bin/env node
/* ============================================================
   Adepti Android — contentbundel bouwen
   ------------------------------------------------------------
   Kopieert de statische sites naar app/src/main/assets/www/,
   snoeit ze voor offline gebruik, en schrijft het manifest dat
   de app gebruikt om OTA-updates te herkennen.

   Gebruik:
     node build-bundle.mjs              bouw alleen
     node build-bundle.mjs --publish    bouw + deploy naar Firebase

   De webbronnen zelf worden NOOIT gewijzigd. Alles wat de app
   anders nodig heeft (lokale fonts) staat in overlay/ en wordt
   er tijdens het bouwen overheen gelegd.
   ============================================================ */

import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/* Waar de websites staan. Dit project ligt bewust NIET in OneDrive: Gradle kan
   niet bouwen tegen bestanden die OneDrive tot "online-only" heeft gemaakt (Java
   ziet zo'n reparse point niet als een gewoon bestand), en build/ + .gradle/
   veranderen te snel voor cloudsync. De lesstof blijft wel in OneDrive staan,
   dus het pad daarheen is expliciet.

   Staat de map ergens anders, zet dan ADEPTI_SITES. */
const ROOT = process.env.ADEPTI_SITES || path.resolve(HERE, "..");

const SITES_ROOT = fs.existsSync(path.join(ROOT, "Adepti"))
  ? ROOT
  : "C:/Users/Weste/OneDrive/Documenten/Claude";
const WWW = path.join(HERE, "app", "src", "main", "assets", "www");
const OVERLAY = path.join(HERE, "overlay");
const PUBLISH_DIR = path.join(SITES_ROOT, "Adepti", "app-content");

const PUBLISH = process.argv.includes("--publish");

/* Harde bovengrens. Loopt de bundel hierboven, dan is er iets
   binnengeslopen dat er niet hoort — meestal media of een taalpak. */
const MAX_BUNDLE_MB = 25;

/* ---------- welke sites gaan mee ---------- */

const SITES = [
  { id: "hub",     from: "Adepti",             only: HUB_FILES },
  { id: "scenario", from: path.join("Adepti", "tools", "scenario-creator") },
  { id: "ew",      from: "ew-leeromgeving",    prune: "ew" },
  { id: "intel",   from: "intel-academy" },
  { id: "vks",     from: "VKS-leeromgeving" },
  { id: "drone",   from: "drone-academy" },
];

/* De hub is een gedeelde map met het forum en de tools erin; we
   nemen alleen de pagina's die de app echt gebruikt. Het forum
   gaat via een Custom Tab en hoeft dus niet mee. */
function HUB_FILES(dir) {
  return ["index.html", "academy.html", "current-intel.html", "tools.html"]
    .map((f) => path.join(dir, f))
    .filter((f) => fs.existsSync(f))
    .concat(walk(path.join(dir, "assets")));
}

const SKIP_DIRS = new Set([
  "media", "translate", "standalone", "node_modules", ".git", ".firebase",
  "forum", "tools", "docs", "app-content", ".claude",
]);
const SKIP_EXT = new Set([".md", ".zip", ".log", ".ps1", ".sh"]);
const SKIP_FILES = new Set(["firebase.json", ".firebaserc", "package.json", "package-lock.json"]);

/* ---------- kleine helpers ---------- */

function walk(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue;
      walk(path.join(dir, e.name), acc);
    } else {
      if (SKIP_EXT.has(path.extname(e.name).toLowerCase())) continue;
      if (SKIP_FILES.has(e.name)) continue;
      acc.push(path.join(dir, e.name));
    }
  }
  return acc;
}

const sha256 = (buf) => createHash("sha256").update(buf).digest("hex");
const posix = (p) => p.split(path.sep).join("/");

function write(dest, data) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.writeFileSync(dest, data);
}

/* Opruimen op Windows, met OneDrive erbij.
   Rechtstreeks verwijderen faalt met EPERM zodra iets nog een handle op de map
   heeft — de OneDrive-sync, een virusscanner, of een webserver die de bundel
   serveert. Hernoemen lukt in die gevallen wél en maakt het pad meteen vrij.
   Het weggooien van de hernoemde map mag daarna rustig mislukken: hij staat
   niet in de weg, heet .trash-* en wordt bij een volgende run alsnog opgeruimd. */
function rmrf(p) {
  if (!fs.existsSync(p)) return;
  const grave = path.join(path.dirname(p), `.trash-${path.basename(p)}-${Date.now()}`);
  try {
    fs.renameSync(p, grave);
  } catch (e) {
    // hernoemen lukte niet; dan maar rechtstreeks, met herpogingen
    fs.rmSync(p, { recursive: true, force: true, maxRetries: 10, retryDelay: 200 });
    return;
  }
  try {
    fs.rmSync(grave, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  } catch (e) {
    console.warn(`  (kon ${path.basename(grave)} niet verwijderen; blijft staan)`);
  }
}

/* Restanten van eerdere runs die toen niet weg konden. */
function sweepTrash(dir) {
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    if (!name.startsWith(".trash-")) continue;
    try {
      fs.rmSync(path.join(dir, name), { recursive: true, force: true, maxRetries: 3 });
    } catch (e) { /* volgende keer beter */ }
  }
}

/* ---------- HTML omschrijven voor offline gebruik ---------- */

/* Google Fonts eruit, lokale fonts erin. De diepte van het pad
   bepaalt hoeveel ../ er voor fonts/ moet. */
function localiseFonts(html, depth) {
  const up = "../".repeat(depth);
  const hasPlex = /IBM\+Plex/.test(html);
  const hasCinzel = /Cinzel/.test(html);
  if (!hasPlex && !hasCinzel) return html;

  return html
    .replace(/\s*<link rel="preconnect" href="https:\/\/fonts\.(googleapis|gstatic)\.com"[^>]*>/g, "")
    .replace(
      /\s*<link href="https:\/\/fonts\.googleapis\.com\/css2[^"]*" rel="stylesheet">/,
      `\n  <link rel="stylesheet" href="${up}fonts/${hasCinzel ? "adepti" : "plex"}.css">`
    );
}

/* Het Cloudflare-beacon heeft in een offline app geen functie en
   levert alleen een mislukt verzoek op. */
function stripBeacon(html) {
  return html.replace(
    /\s*<!-- Cloudflare Web Analytics[\s\S]*?<!-- End Cloudflare Web Analytics -->/,
    ""
  );
}

/* EW laadt zijn talenlijst normaal via fetch("content/langs.json").
   Door EW_AVAILABLE vooraf te zetten wordt die fetch overgeslagen.

   Let op de volgorde: EW_REGISTER_LANG wordt pas in app.js gedefinieerd
   (js/app.js:119), dus lang-en.js daarvóór laden zou een undefined
   aanroepen. De buffer-shim vangt de registratie op in __ewlang, en
   app.js:130 leegt die buffer alsnog. Zo doet standalone/index.html het
   ook — dit is geen nieuwe truc. */
function pinEwLanguages(html) {
  if (/EW_AVAILABLE/.test(html)) return html;
  return html.replace(
    '<script src="js/app.js"></script>',
    '<!-- Offline: talen vastgezet op Engels (Nederlands is de brontaal). -->\n' +
      '<script>window.EW_REGISTER_LANG = function (c, p) { (window.__ewlang = window.__ewlang || {})[c] = p; };</script>\n' +
      '<script src="content/lang-en.js"></script>\n' +
      '<script>window.EW_AVAILABLE = ["en"];</script>\n' +
      '<script src="js/app.js"></script>'
  );
}

/* De sites verwijzen naar elkaar met absolute https-URL's — niet alleen de
   hub, maar ook de academies onderling: in hun topbalk én in kruisverwijzingen
   middenin de lesstof. In de app staan ze allemaal naast elkaar in dezelfde
   bundel, dus die links moeten relatief worden, anders werken ze offline niet.

   De twee dashboards en de externe tools blijven wél absoluut: die worden live
   geladen respectievelijk door ExternalLinks in een Custom Tab geopend. */
const LINK_MAP = {
  "https://ew-academy.web.app": "../ew/index.html",
  "https://ew-leeromgeving.web.app": "../ew/index.html",
  "https://intel-academy.web.app": "../intel/index.html",
  "https://airdefense-academy.web.app": "../vks/index.html",
  "https://drone-academy.web.app": "../drone/index.html",
};

/* Alle sites zitten op www/<site>/, dus "../<site>/index.html" klopt vanuit
   elk van hen. Ook vanuit de lesstof-JS: relatieve URL's in een href worden
   opgelost tegen het dócument, niet tegen het scriptbestand. */
function relinkInternal(text) {
  for (const [from, to] of Object.entries(LINK_MAP)) {
    // eerst de diepe vorm (…web.app/#/5.12), anders blijft "/#/" hangen
    text = text.split(`${from}/#/`).join(`${to}#/`);
    text = text.split(`${from}/`).join(to);
    text = text.split(from).join(to);
  }
  // tools.html verwijst naar de scenario-creator, die naast de hub staat
  text = text.split('"tools/scenario-creator/"').join('"../scenario/index.html"');

  /* target="_blank" moet eraf op alles wat nu binnen de bundel wijst. Een
     WebView opent uit zichzelf geen nieuw venster; zonder deze stap doet een
     klik op een academie-tegel niets. Externe links houden hun target — die
     vangt ExternalLinks op. (MainActivity zet daarnaast expliciet
     setSupportMultipleWindows(false), voor de gevallen waarin een anker in
     JS aan elkaar geplakt wordt en deze regex er dus niet bij kan.) */
  return text.replace(
    /<a([^>]*\bhref="\.\.\/[^"]*"[^>]*)>/g,
    (m, attrs) => `<a${attrs.replace(/\s*target="_blank"/, "").replace(/\s*rel="noopener"/, "")}>`
  );
}

/* De mediadetectie van EW probeert per hoofdstuk vijf audio- en vier
   videoformaten met een HEAD-verzoek (js/app.js:518). Op het web is dat
   prima, maar in de bundel zit geen media, dus offline levert het 125
   mislukte verzoeken per laadbeurt op — traag en luidruchtig. Omdat we
   bij het bouwen zéker weten dat er geen media is, leggen we de probe stil.

   De ankerstring wordt hard gecontroleerd: verandert app.js, dan faalt de
   build in plaats van dat deze patch er stilzwijgend af glijdt. */
const PROBE_ANCHOR = 'function probeFile(url, onExists) {';

function silenceMediaProbe(js) {
  if (!js.includes(PROBE_ANCHOR)) {
    throw new Error(
      "ew: probeFile() niet gevonden in js/app.js — de mediapatch past niet meer. " +
        "Controleer de functie rond app.js:518 en werk PROBE_ANCHOR bij."
    );
  }
  return js.replace(
    PROBE_ANCHOR,
    PROBE_ANCHOR + "\n    return; // offline bundel: geen media aanwezig, niet naar het netwerk"
  );
}

/* ---------- controle: staat elke academie op Engels? ---------- */

function assertEnglishDefault(siteDir, id) {
  const appJs = path.join(siteDir, "js", "app.js");
  if (!fs.existsSync(appJs)) return;
  const src = fs.readFileSync(appJs, "utf8");
  const m = src.match(/localStorage\.getItem\(\s*"(\w+_lang)"\s*\)\s*\|\|\s*"(\w\w)"/);
  if (m && m[2] !== "en") {
    throw new Error(
      `${id}: standaardtaal staat op "${m[2]}" in js/app.js (verwacht "en"). ` +
        `Zet de fallback van ${m[1]} terug op "en" voordat je de bundel bouwt.`
    );
  }
}

/* ---------- bouwen ---------- */

function build() {
  console.log("Adepti Android — contentbundel bouwen\n");
  sweepTrash(path.dirname(WWW));
  rmrf(WWW);

  const manifest = { bundleVersion: new Date().toISOString(), files: {} };
  let totalBytes = 0;

  for (const site of SITES) {
    const src = path.isAbsolute(site.from) ? site.from : path.join(SITES_ROOT, site.from);
    if (!fs.existsSync(src)) throw new Error(`Bronmap ontbreekt: ${src}`);

    if (site.prune === "ew" || ["intel", "vks", "drone"].includes(site.id)) {
      assertEnglishDefault(src, site.id);
    }

    const files = site.only ? site.only(src) : walk(src);
    let count = 0;

    for (const abs of files) {
      const rel = path.relative(src, abs);

      // EW: alleen het Engelse taalpak, de overige 18 vallen af.
      if (site.prune === "ew" && /^content[\\/]lang-/.test(rel) && rel !== path.join("content", "lang-en.js")) continue;
      if (site.prune === "ew" && rel === path.join("content", "langs.json")) continue;

      let data = fs.readFileSync(abs);

      if (site.prune === "ew" && rel === path.join("js", "app.js")) {
        data = Buffer.from(silenceMediaProbe(data.toString("utf8")), "utf8");
      }

      const ext = path.extname(abs).toLowerCase();
      if (ext === ".html") {
        const depth = rel.split(/[\\/]/).length; // www/<site>/<rel> → naar www/
        let html = data.toString("utf8");
        html = stripBeacon(html);
        html = localiseFonts(html, depth);
        if (site.prune === "ew") html = pinEwLanguages(html);
        data = Buffer.from(relinkInternal(html), "utf8");
      } else if (ext === ".js") {
        // kruisverwijzingen in de lesstof wijzen ook naar de andere academies
        data = Buffer.from(relinkInternal(data.toString("utf8")), "utf8");
      }

      const outRel = posix(path.join(site.id, rel));
      write(path.join(WWW, outRel), data);
      manifest.files[outRel] = { sha256: sha256(data), size: data.length };
      totalBytes += data.length;
      count++;
    }
    console.log(`  ${site.id.padEnd(9)} ${String(count).padStart(4)} bestanden`);
  }

  /* overlay/: lokale fonts + de bijbehorende css, gedeeld door alle sites */
  for (const abs of walk(OVERLAY)) {
    const outRel = posix(path.relative(OVERLAY, abs));
    const data = fs.readFileSync(abs);
    write(path.join(WWW, outRel), data);
    manifest.files[outRel] = { sha256: sha256(data), size: data.length };
    totalBytes += data.length;
  }
  console.log(`  overlay   ${String(walk(OVERLAY).length).padStart(4)} bestanden`);

  /* AAPT gooit stilzwijgend elke assets-map weg die met een underscore begint
     (zijn standaard ignore-patroon bevat "<dir>_*"). De bundel lijkt dan compleet,
     maar de bestanden ontbreken in de APK — een fout die je pas ziet als de app
     draait. Vandaar deze controle. */
  const hidden = Object.keys(manifest.files).filter((p) =>
    p.split("/").slice(0, -1).some((seg) => seg.startsWith("_"))
  );
  if (hidden.length) {
    const lijst = hidden.slice(0, 5).map((h) => `    ${h}`).join(String.fromCharCode(10));
    const meer = hidden.length > 5 ? `${String.fromCharCode(10)}    (+${hidden.length - 5} meer)` : "";
    throw new Error(
      [
        "Mappen die met een underscore beginnen worden door AAPT uit de APK geweerd.",
        "Hernoem ze. Betreft:",
        lijst + meer,
      ].join(String.fromCharCode(10))
    );
  }

  write(path.join(WWW, "manifest.json"), JSON.stringify(manifest, null, 2));

  const mb = totalBytes / 1024 / 1024;
  console.log(`\n  totaal    ${Object.keys(manifest.files).length} bestanden, ${mb.toFixed(1)} MB`);
  if (mb > MAX_BUNDLE_MB) {
    throw new Error(`Bundel is ${mb.toFixed(1)} MB, boven de grens van ${MAX_BUNDLE_MB} MB.`);
  }
  return manifest;
}

/* ---------- syntaxcontrole op de gekopieerde JS ---------- */

function checkJs() {
  const js = walk(WWW).filter((f) => f.endsWith(".js"));
  const bad = [];
  for (const f of js) {
    try {
      execFileSync(process.execPath, ["--check", f], { stdio: "pipe" });
    } catch (e) {
      // ES-modules en bestanden met import/export falen op --check; die overslaan.
      const src = fs.readFileSync(f, "utf8");
      if (!/^\s*(import|export)\s/m.test(src)) bad.push(path.relative(WWW, f));
    }
  }
  if (bad.length) throw new Error(`Syntaxfout in:\n  ${bad.join("\n  ")}`);
  console.log(`  syntax    ${js.length} JS-bestanden gecontroleerd`);
}

/* ---------- publiceren ---------- */

function publish() {
  console.log("\nPubliceren naar Firebase Hosting…");
  rmrf(PUBLISH_DIR);
  for (const abs of walk(WWW).concat([path.join(WWW, "manifest.json")])) {
    const rel = path.relative(WWW, abs);
    fs.mkdirSync(path.dirname(path.join(PUBLISH_DIR, rel)), { recursive: true });
    fs.copyFileSync(abs, path.join(PUBLISH_DIR, rel));
  }
  execFileSync("npx", ["firebase-tools", "deploy", "--only", "hosting:adepti"], {
    cwd: path.join(SITES_ROOT, "Adepti"),
    stdio: "inherit",
    shell: process.platform === "win32",
  });
}

try {
  build();
  checkJs();
  if (PUBLISH) publish();
  console.log("\nKlaar.");
} catch (e) {
  console.error(`\nFOUT: ${e.message}`);
  process.exit(1);
}
