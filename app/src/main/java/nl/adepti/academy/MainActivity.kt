package nl.adepti.academy

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var feedCache: FeedCache

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        feedCache = FeedCache(this)

        /* De bundel op een echte https-origin zetten in plaats van file://.
           Dat is de kern van het ontwerp: localStorage werkt betrouwbaar, de
           sites mogen cross-origin JSON ophalen, en de vier academies delen
           nu één origin (hun localStorage-sleutels zijn al genamespaced,
           dus daar botst niets). */
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(BundleAssetHandler.DOMAIN)
            .addPathHandler("/", BundleAssetHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true          // localStorage voor taal en voortgang
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                // De sites regelen hun eigen responsieve opmaak; laat de
                // WebView niets extra's forceren.
                setSupportZoom(false)
                builtInZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT

                /* Expliciet uit: dan laadt de WebView een target="_blank"-link
                   in hetzelfde venster in plaats van hem te laten vallen. Het
                   buildscript haalt dat attribuut al weg bij interne links,
                   maar niet elk anker is als letterlijke string te herkennen —
                   dit vangt de rest op. Externe links komen zo alsnog langs
                   shouldOverrideUrlLoading en dus in een Custom Tab. */
                setSupportMultipleWindows(false)
            }
            webViewClient = AdeptiWebViewClient()
            setBackgroundColor(getColor(R.color.adepti_navy))
        }

        root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.adepti_navy))
            addView(webView)
        }
        setContentView(root)
        applySystemBarInsets()

        if (savedInstanceState == null) {
            webView.loadUrl(BundleAssetHandler.START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        checkForContentUpdate()
        checkForAppUpdate()
    }

    /**
     * Houdt de pagina uit de klok, de accu-indicator en de navigatieknoppen.
     *
     * Vanaf targetSdk 35 tekent Android standaard van rand tot rand: de
     * systeembalken worden doorzichtig en liggen óver de inhoud. Deze sites
     * hebben hun eigen kopbalk met menu- en zoekknop, dus dat levert een
     * onbruikbare overlap op. Het venster krijgt daarom de hoogte van de
     * balken als padding; de vrijgekomen randen worden geverfd in de kleur
     * van de pagina zelf (zie paintBackdrop), zodat het één geheel blijft.
     *
     * De oude weg — statusBarColor in het thema — doet vanaf Android 15
     * niets meer en is dus geen alternatief.
     */
    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Alle sites zijn donker, dus de pictogrammen in de balken moeten licht zijn.
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    /**
     * Verft de randen rond de pagina in de achtergrondkleur van die pagina.
     *
     * Elke site heeft zijn eigen tint — de hub navy, EW donkergroen, Intel
     * blauwzwart. Eén vaste kleur zou bij de helft ervan als een verkeerde
     * balk uitspringen, dus nemen we hem over van de pagina zelf.
     */
    private fun paintBackdrop(cssColor: String?) {
        val parsed = parseCssRgb(cssColor) ?: return
        root.setBackgroundColor(parsed)
        webView.setBackgroundColor(parsed)
    }

    /** Verwacht "rgb(r, g, b)" of "rgba(r, g, b, a)"; alles anders levert null. */
    private fun parseCssRgb(raw: String?): Int? {
        val s = raw?.trim()?.trim('"') ?: return null
        val nums = Regex("[0-9]+(\\.[0-9]+)?").findAll(s).map { it.value }.toList()
        if (nums.size < 3) return null
        // Volledig doorzichtig betekent: de pagina zet zelf geen kleur.
        if (nums.size >= 4 && nums[3].toFloatOrNull() == 0f) return null
        return Color.rgb(nums[0].toInt(), nums[1].toInt(), nums[2].toInt())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /**
     * Kijkt bij elke start of er nieuwe lesstof is. Faalt dit — geen netwerk,
     * server onbereikbaar — dan gebeurt er niets en draait de app door op wat
     * er al is. De gebruiker merkt van een mislukking niets, en hoort het
     * alleen als er wél iets is bijgewerkt.
     */
    private fun checkForContentUpdate() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateManager(this@MainActivity).sync() }
            if (result is UpdateManager.Result.Updated) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.content_updated, result.files),
                    Toast.LENGTH_SHORT
                ).show()
                /* Niet midden in een pagina omschakelen: de nieuwe bestanden
                   worden vanzelf gebruikt bij de volgende navigatie. */
            }
        }
    }

    /**
     * Kijkt of er een nieuwe versie van de app-schil is.
     *
     * Anders dan de lesstof kan dit niet stil: Android laat geen app zichzelf
     * ongevraagd vervangen. De gebruiker krijgt dus een vraag. Dat gebeurt
     * zelden, want gewone inhoudswijzigingen komen via checkForContentUpdate
     * binnen zonder dat er iets geïnstalleerd hoeft te worden.
     */
    private fun checkForAppUpdate() {
        val checker = AppUpdateChecker(this)
        lifecycleScope.launch {
            val release = withContext(Dispatchers.IO) { checker.fetchIfNewer() } ?: return@launch
            if (!isFinishing) checker.promptForInstall(release)
        }
    }

    private inner class AdeptiWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (request.url.host == BundleAssetHandler.DOMAIN) {
                return assetLoader.shouldInterceptRequest(request.url)
            }
            if (feedCache.handles(request)) {
                return feedCache.serve(request)
            }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            if (ExternalLinks.isInternal(url)) return false
            ExternalLinks.open(this@MainActivity, url)
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            /* Zonder netwerk moet de WebView de app-schil van de live sites
               uit zijn eigen cache mogen halen; met netwerk juist niet, want
               dan wil je verse data. */
            view.settings.cacheMode =
                if (isOnline()) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

            view.evaluateJavascript(
                "(function(){try{return getComputedStyle(document.body).backgroundColor;}" +
                    "catch(e){return '';}})()"
            ) { paintBackdrop(it) }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
