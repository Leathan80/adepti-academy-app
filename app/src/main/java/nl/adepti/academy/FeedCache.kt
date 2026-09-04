package nl.adepti.academy

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Bewaart de JSON-feeds van de twee live dashboards, zodat je offline de
 * laatst bekende stand ziet in plaats van een lege pagina.
 *
 * Waarom dit met de hand moet: beide sites zetten bewust
 * `Cache-Control: no-store` op hun feeds (zie hun firebase.json), omdat een
 * crawler ze voortdurend ververst. Daardoor bewaart de HTTP-cache van de
 * WebView ze niet. Voor de app-schil van die sites volstaat de gewone
 * cache wél; die regelt MainActivity met de cacheMode.
 *
 * Netwerk gaat altijd voor. De kopie is een vangnet, geen versnelling.
 */
class FeedCache(context: Context) {

    private val dir = File(context.cacheDir, "feeds").apply { mkdirs() }

    fun handles(request: WebResourceRequest): Boolean {
        if (request.method != "GET") return false
        val url = request.url
        val host = url.host ?: return false
        if (host !in CACHED_HOSTS) return false
        return url.path?.endsWith(".json") == true
    }

    fun serve(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val key = keyFor(url)

        fetch(url)?.let { bytes ->
            runCatching { File(dir, key).writeBytes(bytes) }
            return respond(bytes, fresh = true)
        }

        val cached = File(dir, key)
        if (!cached.isFile) return null // laat de WebView zelf falen; de site heeft een catch
        Log.i(TAG, "offline, uit cache: $url")
        return respond(cached.readBytes(), fresh = false)
    }

    private fun respond(bytes: ByteArray, fresh: Boolean) =
        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
                // Puur informatief, handig bij het opsporen van problemen
                "X-Adepti-Source" to if (fresh) "network" else "cache"
            )
        }

    /** De query bevat een cache-buster (?t=…); die hoort niet in de sleutel. */
    private fun keyFor(url: String): String {
        val withoutQuery = url.substringBefore('?')
        return MessageDigest.getInstance("SHA-256")
            .digest(withoutQuery.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json"
    }

    private fun fetch(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "AdeptiFeedCache"
        private const val TIMEOUT_MS = 8_000

        private val CACHED_HOSTS = setOf(
            "ru-mil-tracker.web.app",
            "intel-briefing-dashboard.web.app"
        )
    }
}
