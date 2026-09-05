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

Deze regels **staan sinds 4 september 2026 ingevuld**, dus ondertekenen gebeurt
nu vanaf de opdrachtregel; Android Studio is er niet meer voor nodig. Let op de
JVM: Gradle 8.9 accepteert hoogstens Java 22, terwijl Studio JDK 25 meelevert.
Bouw daarom met de meegeleverde 21:

```bash
JAVA_HOME="$HOME/.jdks/jbr-21.0.11" ./gradlew assembleRelease
```

Controleer bij twijfel dat de handtekening nog dezelfde is als die van de
gepubliceerde versie — wijkt hij af, dan weigert het toestel de update:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

De vingerafdruk hoort `5dffd99d4aa921750afcd6eeea00c5d845c7789529f1e82077bd0eb6ebdcb7fd`
te zijn (CN=The Adepti).

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

Datzelfde commando werkt ook de webapp bij (zie hieronder): de service worker
leest hetzelfde `manifest.json` en haalt op dezelfde manier alleen het verschil
op. Eén publicatie, beide platformen.

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

## De webapp (iPhone, iPad, desktop)

Op iOS bestaat geen APK en geen zelf verspreiden: Apples webdistributie in de
EU stelt eisen die dit project niet haalt, dus native zou App Store betekenen -
99 euro per jaar en een reeel risico op afwijzing onder richtlijn 4.2, want een
WebView om een website heen is precies wat Apple daarmee weert.

De uitweg was er al. De gepubliceerde bundel op
`the-adepti.web.app/app-content/` is een complete site op een enkel domein, met
de kruisverwijzingen al omgeschreven. Daar hoefde alleen een service worker en
een webmanifest bij om er een installeerbare app van te maken. Kort adres:
**`adepti-academy.nl/app`**.

Wat de gebruiker daarmee wint boven de gewone site:

- **Offline**, net als de Android-app.
- **Voortgang blijft staan.** Safari wist de opslag van een gewone website na
  zeven dagen zonder bezoek. Een app op het beginscherm valt buiten die regel -
  voor een leeromgeving het zwaarste argument.
- **Opent schermvullend**, met eigen pictogram.

### Hoe het werkt

`overlay/sw.js` is de JavaScript-versie van `UpdateManager.kt`: hij leest
`manifest.json`, cachet alle bestanden, en vergelijkt bij elke start de SHA-256
per bestand om alleen het verschil op te halen. De cachenaam is bewust vast -
weggooien en opnieuw vullen zou elke gebruiker 14 MB kosten voor een gewijzigde
regel lesstof.

`injectPwa()` in `build-bundle.mjs` zet in elke pagina het manifest, de iconen
en het registratiescript. Dat script houdt zichzelf tegen zodra
`location.hostname` gelijk is aan `appassets.androidplatform.net`: in de
Android-app serveert de WebView de bundel al vanaf schijf, en een tweede kopie
in een service worker heeft daar geen functie.

### Noodknop

Loopt de cache vast, dan wist **`?reset=1`** achter de URL alles - registraties
en caches - en bouwt de app zichzelf opnieuw op. Er is geen Apple-toestel om
mee te debuggen, dus dit is geen luxe.

### Cachekoppen

`Adepti/firebase.json` zet HTML, `sw.js` en `app.webmanifest` op `no-cache`; de
rest van `/app-content/` houdt de jaarcache. Die combinatie is nodig omdat
dezelfde map twee klanten bedient: de Android-app controleert elk bestand op
SHA-256 en heeft aan een jaar cache genoeg, maar een browser zou zonder deze
uitzonderingen een jaar op dezelfde `index.html` kunnen blijven hangen. **De
volgorde is dragend** - Firebase laat de laatst passende regel winnen.

### Iconen

`make-icons.py` maakt de vier iconen uit `Adepti/assets/logo.png`. Draai dat
alleen opnieuw als het logo verandert; de uitkomst staat in `overlay/icons/`.
Twee dingen zitten er bewust in: het palet van 64 kleuren (het logo heeft
filmkorrel, en ruis comprimeert niet - op volle kleurdiepte kostte het
512-icoon alleen al bijna een halve megabyte), en het uitknippen van de gouden
cirkel voor de maskable variant, omdat het hele vierkant inplakken een zichtbaar
vierkant-in-een-vierkant gaf.

### Wat niet getest is

Safari draait niet op Windows. De service worker is volledig getest in Chromium
- installatie, offline navigatie, de hash-diff, de noodknop - maar niet op een
echt Apple-toestel. Vandaar: alleen breed ondersteunde API's, geen
slimmigheden, en de noodknop. Bewust weggelaten zijn
`apple-mobile-web-app-status-bar-style: black-translucent` en
`viewport-fit=cover`: die leggen de statusbalk over de pagina heen, precies de
fout die op Android met `applySystemBarInsets()` is rechtgezet, en dat wil je
niet blind invoeren.

Ligt er ooit vijf minuten een iPhone: installeren via Deel > Zet op
beginscherm, vliegtuigstand aan, twee academies openen, taal wisselen naar
Nederlands, afsluiten en opnieuw starten.

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
