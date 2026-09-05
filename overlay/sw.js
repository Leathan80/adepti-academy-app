/* Service worker voor de Adepti-webapp.
 *
 * Zet de hele bundel op het toestel, zodat de leeromgevingen zonder netwerk
 * werken. Dit is de webversie van wat de Android-app met UpdateManager.kt doet,
 * en het leunt op precies dezelfde bron van waarheid: manifest.json, de lijst
 * met paden, SHA-256 en groottes die build-bundle.mjs genereert.
 *
 * Gevolg daarvan: één `node build-bundle.mjs --publish` werkt de Android-app
 * én de webapp bij. Er is geen tweede publicatiestap en geen tweede lijst die
 * uit de pas kan lopen.
 *
 * De cachenaam is bewust vast. Bij een nieuwe bundel gooien we de cache niet
 * weg om hem daarna opnieuw te vullen - dat zou elke gebruiker 13,8 MB kosten
 * voor een gewijzigde regel lesstof. In plaats daarvan vergelijken we de
 * hashes en halen we alleen op wat werkelijk anders is.
 *
 * Patronen overgenomen uit ru-mil-tracker/public/sw.js, dat deze valkuilen al
 * had gevonden: altijd een échte Response teruggeven (respondWith(undefined)
 * laat een subresource hard falen, waarna de pagina zonder CSS en JS
 * overblijft), en zelfherstel voor het geval het toestel de cache opruimt.
 */

const CACHE = "adepti-bundle";

/* De opgeslagen kopie van het manifest. Dit pad bestaat niet op de server; het
   is alleen een sleutel in de cache. Zo kunnen we het manifest van de vorige
   keer naast dat van nu leggen. */
const STORED_MANIFEST = "__state/manifest.json";

/* Hoeveel bestanden we tegelijk ophalen. Alle 175 tegelijk loslaten legt een
   mobiele verbinding plat en levert time-outs op. */
const BATCH = 12;

const url = (p) => new URL(p, self.registration.scope).toString();

function offlineResponse() {
  return new Response("", { status: 504, statusText: "Offline en niet in cache" });
}

/* ---------- manifest ---------- */

async function fetchManifest() {
  // cache:"reload" omzeilt de HTTP-cache. Nodig, want alles onder
  // /app-content/ wordt een jaar lang gecacht voor de Android-app.
  const resp = await fetch(url("manifest.json"), { cache: "reload" });
  if (!resp.ok) throw new Error(`manifest.json: HTTP ${resp.status}`);
  return resp.json();
}

async function storedManifest(cache) {
  const hit = await cache.match(url(STORED_MANIFEST));
  if (!hit) return null;
  try {
    return await hit.json();
  } catch (e) {
    return null; // half weggeschreven; behandel als "niets opgeslagen"
  }
}

function saveManifest(cache, manifest) {
  return cache.put(
    url(STORED_MANIFEST),
    new Response(JSON.stringify(manifest), {
      headers: { "Content-Type": "application/json" },
    })
  );
}

/* ---------- ophalen ---------- */

/** Haalt één bestand op en zet het in de cache. Geeft terug of dat lukte. */
async function cacheOne(cache, cache_path) {
  try {
    const resp = await fetch(url(cache_path), { cache: "reload" });
    if (!resp.ok) return false;
    await cache.put(url(cache_path), resp);
    return true;
  } catch (e) {
    return false;
  }
}

/**
 * Haalt een lijst paden op in blokken.
 *
 * Mislukkingen worden geteld, niet gegooid: één 404 mag niet betekenen dat de
 * installatie faalt en de gebruiker helemaal niets offline heeft. Wat ontbreekt
 * wordt bij de volgende start alsnog opgehaald door heal().
 */
async function cacheAll(cache, paths) {
  let ok = 0;
  for (let i = 0; i < paths.length; i += BATCH) {
    const slice = paths.slice(i, i + BATCH);
    const results = await Promise.all(slice.map((p) => cacheOne(cache, p)));
    ok += results.filter(Boolean).length;
  }
  return ok;
}

/* ---------- bijwerken ---------- */

/**
 * Vergelijkt het verse manifest met het opgeslagen exemplaar en haalt alleen
 * het verschil op. Dit is de kern: dezelfde methode als UpdateManager.sync().
 */
async function sync() {
  const cache = await caches.open(CACHE);

  let fresh;
  try {
    fresh = await fetchManifest();
  } catch (e) {
    // Offline of server onbereikbaar. Stil doorgaan met wat er is - precies
    // zoals de Android-app doet. De gebruiker merkt hier niets van.
    return { status: "offline" };
  }

  const old = await storedManifest(cache);
  const oldFiles = (old && old.files) || {};
  const newFiles = fresh.files || {};

  const changed = Object.keys(newFiles).filter(
    (p) => !oldFiles[p] || oldFiles[p].sha256 !== newFiles[p].sha256
  );
  const removed = Object.keys(oldFiles).filter((p) => !newFiles[p]);

  if (!changed.length && !removed.length) {
    await saveManifest(cache, fresh); // bundleVersion kan wél zijn opgeschoven
    return { status: "up-to-date" };
  }

  const ok = await cacheAll(cache, changed);
  await Promise.all(removed.map((p) => cache.delete(url(p))));

  /* Het manifest pas opslaan als alles binnen is. Ging er iets mis, dan blijft
     het oude manifest staan en probeert de volgende start het opnieuw - in
     plaats van dat we een gat permanent als "bijgewerkt" wegschrijven. */
  if (ok === changed.length) await saveManifest(cache, fresh);

  return { status: "updated", files: ok, removed: removed.length };
}

/**
 * Zelfherstel. iOS en Android mogen cache-opslag opruimen als de telefoon vol
 * raakt. Dan staat de bundel er half op en werkt de app op onverwachte manieren
 * niet. Dus kijken we bij elke start wat er ontbreekt en halen dat opnieuw op.
 */
async function heal() {
  const cache = await caches.open(CACHE);
  const manifest = await storedManifest(cache);
  if (!manifest) return { status: "geen manifest" };

  const paths = Object.keys(manifest.files || {});
  const missing = [];
  for (const p of paths) {
    if (!(await cache.match(url(p)))) missing.push(p);
  }
  if (!missing.length) return { status: "compleet" };

  const ok = await cacheAll(cache, missing);
  return { status: "hersteld", files: ok, missing: missing.length };
}

/* ---------- levenscyclus ---------- */

self.addEventListener("install", (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(CACHE);
      const manifest = await fetchManifest();
      const paths = Object.keys(manifest.files || {});
      const ok = await cacheAll(cache, paths);
      if (ok === paths.length) await saveManifest(cache, manifest);
      await self.skipWaiting();
    })()
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      // Caches van eerdere opzetten opruimen.
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)));
      await self.clients.claim();
      await heal();
      await sync();
    })()
  );
});

/* De pagina kan om een controle vragen (zie de injectie in build-bundle.mjs).
   Die vraag komt bij elke start binnen, zodat nieuwe lesstof uiterlijk bij de
   volgende keer openen op het toestel staat. */
self.addEventListener("message", (event) => {
  if (!event.data || event.data.type !== "sync") return;
  event.waitUntil(
    sync().then((result) => {
      if (event.source) event.source.postMessage({ type: "sync-result", ...result });
    })
  );
});

/* ---------- verzoeken afhandelen ---------- */

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;

  let target;
  try {
    target = new URL(req.url);
  } catch (e) {
    return;
  }

  /* Alles buiten de bundel met rust laten: de twee live dashboards, de
     crosscheck-feed die de EW-pagina ophaalt, kaarttegels. Die horen vers te
     zijn en zijn niet van ons. */
  if (!req.url.startsWith(self.registration.scope)) return;

  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE);

      const hit = await cache.match(target.toString(), { ignoreSearch: true });
      if (hit) return hit;

      /* Cache-mis. Tussen twee publicaties verandert er niets, dus dit is geen
         normale gang van zaken - het betekent dat de bundel nog niet compleet
         is, of dat er een pad wordt opgevraagd dat niet in het manifest staat. */
      try {
        const resp = await fetch(req);
        if (resp && resp.ok && resp.type === "basic") {
          cache.put(target.toString(), resp.clone());
        }
        return resp;
      } catch (e) {
        /* Offline en niets in de cache. Voor een navigatie is een lege pagina
           het slechtste antwoord; val terug op de hub, waar de gebruiker
           verder kan. */
        if (req.mode === "navigate") {
          const hub = await cache.match(url("hub/index.html"));
          if (hub) return hub;
        }
        return offlineResponse();
      }
    })()
  );
});
