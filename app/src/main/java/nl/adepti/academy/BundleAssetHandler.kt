package nl.adepti.academy

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Serveert de contentbundel op https://appassets.androidplatform.net/.
 *
 * Twee lagen, in deze volgorde:
 *   1. filesDir/ota/www/<pad>   — door UpdateManager gedownloade content
 *   2. assets/www/<pad>         — de versie die in de APK is meegebakken
 *
 * Meer is het update-mechanisme niet: één bestandsopzoeking met een
 * terugval. De webcontent merkt van het onderscheid niets.
 */
class BundleAssetHandler(context: Context) : WebViewAssetLoader.PathHandler {

    private val assets = context.assets
    private val otaRoot = File(context.filesDir, OTA_DIR)

    override fun handle(path: String): WebResourceResponse? {
        val clean = normalise(path) ?: return null
        val mime = mimeOf(clean)

        val stream = openOta(clean) ?: openBundled(clean) ?: return null

        return WebResourceResponse(mime, "utf-8", stream).apply {
            responseHeaders = mapOf(
                // De bundel is lokaal; hercontroleren heeft geen zin.
                "Cache-Control" to "no-cache",
                // De hub haalt cross-origin JSON op bij de live dashboards.
                "Access-Control-Allow-Origin" to "*"
            )
        }
    }

    /**
     * Weert padtraversal. Zonder deze controle zou een pagina met
     * "../../databases/x" buiten de bundel kunnen lezen.
     */
    private fun normalise(path: String): String? {
        val trimmed = path.trimStart('/')
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("..") || trimmed.contains('\\')) return null
        return trimmed
    }

    private fun openOta(path: String): InputStream? {
        val f = File(otaRoot, path)
        // canonicalPath vangt symlinks en resterende trucs af
        if (!f.canonicalPath.startsWith(otaRoot.canonicalPath)) return null
        return if (f.isFile) FileInputStream(f) else null
    }

    private fun openBundled(path: String): InputStream? = try {
        assets.open("$ASSET_DIR/$path")
    } catch (e: java.io.IOException) {
        null
    }

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json", "geojson" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val ASSET_DIR = "www"
        const val OTA_DIR = "ota/www"

        /** De pagina waarop de app opent. */
        const val START_URL = "https://$DOMAIN/hub/index.html"
    }
}
