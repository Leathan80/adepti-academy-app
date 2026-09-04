# Adepti Academy — Android-app

Eén app die de hele adepti-academy.nl-omgeving bundelt. De vier academies en
de Scenario Creator werken **volledig offline**; de twee dashboards laden live
met de laatst opgehaalde data als terugval; het forum en de externe tools
openen in een Chrome Custom Tab.

Lesstof-updates komen binnen **zonder nieuwe APK** — zie *Content bijwerken*.

---

## Waar dit project staat — en waarom niet in OneDrive

`C:\Users\Weste\AndroidProjects\adepti-android`

Bewust **buiten** OneDrive. Twee dingen gaan daar stuk:
- OneDrive maakt gesynchroniseerde bestanden na verloop van tijd "online-only"
  (een reparse point). Java ziet zo'n bestand niet als een gewoon bestand en de
  build faalt met `Cannot snapshot ...: not a regular file`.
- `build/` en `.gradle/` veranderen bij elke build volledig; cloudsync houdt daar
  handles op vast, waardoor opruimen mislukt met `EPERM`.

De **websites blijven wel in OneDrive** staan
(`C:\Users\Weste\OneDrive\Documenten\Claude`). `build-bundle.mjs` kent dat pad;
staat het ergens anders, zet dan de omgevingsvariabele `ADEPTI_SITES`.

---

## Eenmalig geïnstalleerd

Android Studio (met JDK en SDK). Bij het openen van het project haalde Gradle
**SDK Platform 35** zelf op. Gebruik **JVM 21** als Gradle-JDK — Java 25 is te
nieuw voor Gradle 8.9.

---

## Bouwen

```bash
node build-bundle.mjs
```

Bouwt de contentbundel uit de websites in de OneDrive-map naar
`app/src/main/assets/www/`. Draai dit **altijd** vóór een APK-build; zonder
bundel start de app op een lege pagina.

Daarna, vanuit de projectmap:

```bash
./gradlew assembleRelease
```

De APK verschijnt in `app/build/outputs/apk/release/`.

### Ondertekenen

Genereer eenmalig een keystore en bewaar die veilig — **zonder exact dezelfde
sleutel kan een latere APK niet over een bestaande installatie heen**, en dan
moet iedereen eerst de-installeren (met verlies van lesvoortgang):

```bash
keytool -genkey -v -keystore adepti.jks -keyalg RSA -keysize 2048 -validity 10000 -alias adepti
```

Zet daarna in `~/.gradle/gradle.properties` (niet in de projectmap, die staat
in git):

```
ADEPTI_KEYSTORE=C:/pad/naar/adepti.jks
ADEPTI_KEYSTORE_PASSWORD=...
ADEPTI_KEY_ALIAS=adepti
ADEPTI_KEY_PASSWORD=...
```

Zonder deze regels bouwt `assembleRelease` een niet-ondertekende APK — bruikbaar
om te testen, niet om te verspreiden.

---

## Content bijwerken

Het model is **pull, geen push**: de app kijkt bij elke start zelf of er iets
nieuws is. Geen server die duwt, geen Google-dienst, geen registratie van wie
de app heeft. De prijs is dat een update pas landt bij de volgende keer openen.

Publiceren is één commando:

```bash
node build-bundle.mjs --publish
```

Dat bouwt de bundel, kopieert hem naar `Adepti/app-content/` en deployt naar
Firebase Hosting. De app ziet bij de volgende start een nieuw `manifest.json`,
downloadt alleen de bestanden waarvan de SHA-256 afwijkt, en activeert die pas
als álles binnen is en klopt. Een afgebroken download laat de vorige versie
volledig intact.

**Draai dit na elke inhoudelijke wijziging aan een academie.** Deploy je alleen
de website zelf, dan blijft de app op de oude lesstof staan.

Alleen bij wijzigingen aan de app-schil (nieuwe academie, native functionaliteit)
is een nieuwe APK nodig.

---

## Hoe het in elkaar zit

| Bestand | Rol |
|---|---|
| `build-bundle.mjs` | Bouwt en snoeit de contentbundel, genereert het manifest |
| `overlay/_fonts/` | Lokale IBM Plex-, Cinzel- en Inter-fonts (248 KB) |
| `MainActivity.kt` | De WebView en zijn client; verder niets |
| `BundleAssetHandler.kt` | Serveert de bundel; kijkt eerst in de OTA-map, dan in de APK |
| `UpdateManager.kt` | Haalt en verifieert contentupdates |
| `FeedCache.kt` | Bewaart de JSON-feeds van de twee dashboards voor offline |
| `ExternalLinks.kt` | Stuurt forum en externe tools naar een Custom Tab |

### Waarom `appassets.androidplatform.net` en niet `file://`

De bundel wordt geserveerd via `WebViewAssetLoader` op een echte https-origin.
Dat is geen detail maar de kern van het ontwerp: `localStorage` werkt
betrouwbaar (op `file://` per Android-versie niet), de sites mogen cross-origin
JSON ophalen bij de dashboards, en er zijn geen CORS-verrassingen.

Bijvangst: de vier academies delen nu één origin in plaats van vier aparte
domeinen. Hun `localStorage`-sleutels zijn al genamespaced (`ew_lang`,
`intel_lang`, `vks_lang`, `drone_lang`), dus daar botst niets.

### Wat het buildscript aan de content verandert

De webbronnen zelf blijven onaangeroerd; alle aanpassingen gebeuren op de kopie:

- **Google Fonts → lokaal.** Dezelfde aanpak als `ew-leeromgeving/standalone/`.
- **EW: 19 taalpakken eruit.** Alleen `lang-en.js` blijft; `EW_AVAILABLE` wordt
  vooraf gezet zodat `fetch("content/langs.json")` wegvalt. Nederlands is de
  brontaal en zit al in `content/chN.js`, dus de NL/EN-schakelaar blijft werken.
- **EW: de mediadetectie stilgelegd.** `probeFile()` doet per hoofdstuk negen
  HEAD-verzoeken om audio en video te vinden. Er zit geen media in de bundel,
  dus dat leverde 125 mislukte verzoeken per laadbeurt op. Het script patcht de
  functie en **faalt hard** als de ankerstring niet meer voorkomt — zo glijdt de
  patch er niet stilzwijgend af als `app.js` verandert.
- **Cloudflare-beacon eruit.**
- **Geen mapnamen die met een underscore beginnen.** AAPT gooit die stilzwijgend
  uit de APK (`<dir>_*` staat in zijn ignore-patroon): de bundel lijkt compleet,
  maar de bestanden ontbreken in de app. Vandaar `overlay/fonts/` en niet
  `_fonts/`; het script controleert hier ook expliciet op.
- **Academie-links relatief gemaakt**, inclusief het verwijderen van
  `target="_blank"` daarop: een WebView opent uit zichzelf geen nieuw venster,
  dus zonder die stap doet een klik op een tegel niets.

Het script controleert ook of elke academie nog op Engels als standaardtaal
staat, en weigert te bouwen als de bundel boven 25 MB uitkomt.

---

## Bewust niet in v1

- **De EW-audio** (15 `.m4a`-bestanden, 613 MB). Hercoderen naar 64 kbps mono
  AAC zou dit naar ~30–50 MB brengen; dan wordt streamen met optionele download
  per hoofdstuk logisch.
- **Accountsynchronisatie** (`les-sync.js`). Firebase App Check staat afgedwongen
  met reCAPTCHA, en dat is onbetrouwbaar in een WebView. Voortgang blijft lokaal;
  de bestaande plakcode-export werkt gewoon. De juiste route hiervoor is native
  App Check met Play Integrity — een eigen project.
- **Play Store, push-notificaties, iOS.**
