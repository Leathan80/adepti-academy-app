package nl.adepti.academy

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat

/**
 * Bepaalt wat er binnen de WebView blijft en wat naar een Custom Tab gaat.
 *
 * Het forum moet naar buiten: het gebruikt Firebase Auth met App Check via
 * reCAPTCHA, en Google behandelt WebView-verkeer actief als verdacht.
 * Inloggen zou daar dus stukgaan. Een Custom Tab draait de echte
 * Chrome-engine met een volwaardige cookiejar — inloggen en posten werken
 * gewoon, en wie in Chrome al is ingelogd is dat hier meteen ook.
 *
 * De drie externe tools gaan om een eenvoudiger reden dezelfde kant op:
 * het zijn sites van derden, en die horen zichtbaar buiten de app.
 */
object ExternalLinks {

    /** Hosts die de app zelf rendert; al het overige gaat naar buiten. */
    private val INTERNAL_HOSTS = setOf(
        BundleAssetHandler.DOMAIN,
        "ru-mil-tracker.web.app",
        "intel-briefing-dashboard.web.app"
    )

    fun isInternal(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host in INTERNAL_HOSTS
    }

    fun open(context: Context, url: String) {
        val scheme = runCatching { Uri.parse(url).scheme }.getOrNull()?.lowercase()
        if (scheme != "https" && scheme != "http") {
            // mailto:, intent:, tel: — laat het systeem beslissen, of negeer
            return openWithSystem(context, url)
        }

        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .setDefaultColorSchemeParams(
                androidx.browser.customtabs.CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(ContextCompat.getColor(context, R.color.adepti_navy))
                    .build()
            )
            .build()

        try {
            intent.launchUrl(context, Uri.parse(url))
        } catch (e: ActivityNotFoundException) {
            // Geen browser aanwezig — dat is zeldzaam maar niet onmogelijk
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun openWithSystem(context: Context, url: String) {
        try {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
