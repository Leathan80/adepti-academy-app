package nl.adepti.academy

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Haalt contentupdates op van Firebase Hosting.
 *
 * Het model is *pull*: de app kijkt zelf bij elke start of er iets nieuws
 * is. Er is geen server die duwt, geen Google-dienst, geen registratie van
 * wie de app heeft. De prijs is dat een update pas landt bij de volgende
 * keer openen — voor lesstof ruim voldoende.
 *
 * Alles of niets: er wordt pas iets zichtbaar als élk gewijzigd bestand
 * binnen is en de hash klopt. Een afgebroken download laat de vorige
 * versie volledig intact.
 */
class UpdateManager(private val context: Context) {

    sealed class Result {
        /** Niets te doen, of geen netwerk — in beide gevallen geen actie. */
        object UpToDate : Result()
        data class Updated(val files: Int, val version: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val otaRoot = File(context.filesDir, BundleAssetHandler.OTA_DIR)
    private val stagingRoot = File(context.filesDir, "ota-staging")
    private val localManifest = File(context.filesDir, "ota-manifest.json")

    /** Blokkeert; roep dit aan op een achtergrondthread. */
    fun sync(): Result {
        val remote = fetchJson(MANIFEST_URL) ?: return Result.UpToDate // offline: stil terug
        val remoteFiles = remote.optJSONObject("files") ?: return Result.Failed("manifest zonder files")
        val remoteVersion = remote.optString("bundleVersion", "?")

        val current = readCurrentManifest()
        if (current?.optString("bundleVersion") == remoteVersion) return Result.UpToDate

        /* Bepaal wat er echt anders is. We vergelijken tegen de hashes die
           we nú serveren: dat is de OTA-laag als die er is, anders de
           meegebakken bundel. */
        val have = currentHashes(current)
        val todo = mutableListOf<Pair<String, String>>() // pad -> verwachte sha256
        val names = remoteFiles.keys()
        while (names.hasNext()) {
            val path = names.next()
            val want = remoteFiles.getJSONObject(path).optString("sha256")
            if (have[path] != want) todo.add(path to want)
        }

        if (todo.isEmpty()) {
            writeManifest(remote)
            return Result.UpToDate
        }

        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()

        for ((path, wantHash) in todo) {
            val bytes = fetchBytes("$CONTENT_BASE/$path")
                ?: return abort("download mislukt: $path")
            if (sha256(bytes) != wantHash) return abort("hash klopt niet: $path")

            val out = File(stagingRoot, path)
            if (!out.canonicalPath.startsWith(stagingRoot.canonicalPath)) {
                return abort("verdacht pad in manifest: $path")
            }
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
        }

        /* Alles binnen en geverifieerd — nu pas activeren. De gedownloade
           bestanden worden over de bestaande OTA-laag heen gelegd; wat niet
           veranderd is blijft staan. */
        otaRoot.mkdirs()
        if (!moveInto(stagingRoot, otaRoot)) return abort("verplaatsen mislukt")
        stagingRoot.deleteRecursively()
        writeManifest(remote)

        Log.i(TAG, "Content bijgewerkt: ${todo.size} bestanden, versie $remoteVersion")
        return Result.Updated(todo.size, remoteVersion)
    }

    /** Gooit de OTA-laag weg; de app valt terug op wat in de APK zit. */
    fun resetToBundled() {
        otaRoot.deleteRecursively()
        stagingRoot.deleteRecursively()
        localManifest.delete()
    }

    // ---------- intern ----------

    private fun abort(reason: String): Result {
        stagingRoot.deleteRecursively()
        Log.w(TAG, "Update afgebroken: $reason")
        return Result.Failed(reason)
    }

    private fun readCurrentManifest(): JSONObject? = try {
        if (localManifest.isFile) JSONObject(localManifest.readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun writeManifest(m: JSONObject) = localManifest.writeText(m.toString())

    /**
     * De hashes van wat we op dit moment serveren. Zonder eerdere update is
     * dat de meegebakken bundel, waarvan het manifest in de assets staat.
     */
    private fun currentHashes(current: JSONObject?): Map<String, String> {
        val src = current ?: bundledManifest() ?: return emptyMap()
        val files = src.optJSONObject("files") ?: return emptyMap()
        val out = HashMap<String, String>(files.length())
        val it = files.keys()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = files.getJSONObject(k).optString("sha256")
        }
        return out
    }

    private fun bundledManifest(): JSONObject? = try {
        context.assets.open("${BundleAssetHandler.ASSET_DIR}/manifest.json")
            .bufferedReader().use { JSONObject(it.readText()) }
    } catch (e: Exception) {
        null
    }

    private fun moveInto(from: File, to: File): Boolean {
        for (f in from.walkTopDown()) {
            if (!f.isFile) continue
            val dest = File(to, f.relativeTo(from).path)
            dest.parentFile?.mkdirs()
            dest.delete()
            if (f.renameTo(dest)) continue
            // renameTo kan falen over partitiegrenzen; dan maar kopiëren
            try {
                f.copyTo(dest, overwrite = true)
            } catch (e: Exception) {
                Log.w(TAG, "kon ${f.name} niet plaatsen", e)
                return false
            }
        }
        return true
    }

    private fun fetchJson(url: String): JSONObject? =
        fetchBytes(url)?.let {
            try { JSONObject(String(it, Charsets.UTF_8)) } catch (e: Exception) { null }
        }

    private fun fetchBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode != 200) return null
            val raw = conn.inputStream
            val stream = if (conn.contentEncoding.equals("gzip", true)) {
                java.util.zip.GZIPInputStream(raw)
            } else raw
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            null // offline of storing: stil terug, de app draait gewoon door
        } finally {
            conn?.disconnect()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AdeptiUpdate"
        private const val TIMEOUT_MS = 5_000

        const val CONTENT_BASE = "https://the-adepti.web.app/app-content"
        const val MANIFEST_URL = "$CONTENT_BASE/manifest.json"
    }
}
