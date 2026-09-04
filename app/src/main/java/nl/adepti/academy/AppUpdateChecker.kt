package nl.adepti.academy

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kijkt of er een nieuwe versie van de app zélf is.
 *
 * Let op het onderscheid met [UpdateManager]: die haalt nieuwe *lesstof* op en
 * werkt volledig op de achtergrond. Deze klas gaat over de app-schil, en dat
 * kan niet stil: Android laat een app zichzelf niet ongevraagd vervangen. De
 * gebruiker krijgt dus een vraag en tikt zelf op installeren.
 *
 * Waarom dat zelden nodig is: de lesstof komt al via de OTA-laag binnen. Een
 * nieuwe APK is alleen nodig als er iets aan de schil verandert.
 *
 * De versiegegevens staan op Firebase naast de OTA-laag, niet op de GitHub-API.
 * Zo is er één plek waar we de cache-headers in de hand hebben, geen
 * verzoeklimiet, en blijft GitHub alleen de opslagplek van het bestand.
 */
class AppUpdateChecker(private val activity: MainActivity) {

    data class Release(
        val versionCode: Long,
        val versionName: String,
        val url: String,
        val notes: String
    )

    /** Blokkeert; roep dit aan op een achtergrondthread. */
    fun fetchIfNewer(): Release? {
        val json = fetch(VERSION_URL) ?: return null   // offline: stil terug
        return try {
            val o = JSONObject(json)
            val remote = o.optLong("versionCode", -1)
            if (remote <= currentVersionCode()) return null
            Release(
                versionCode = remote,
                versionName = o.optString("versionName", "?"),
                url = o.optString("url", ""),
                notes = o.optString("notes", "")
            ).takeIf { it.url.startsWith("https://") }
        } catch (e: Exception) {
            Log.w(TAG, "versiebestand onleesbaar", e)
            null
        }
    }

    /** Toont de vraag; [release] komt uit [fetchIfNewer]. */
    fun promptForInstall(r: Release) {
        val body = buildString {
            append(activity.getString(R.string.update_available_body, r.versionName))
            if (r.notes.isNotBlank()) {
                append("\n\n")
                append(r.notes)
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_available_title)
            .setMessage(body)
            .setPositiveButton(R.string.update_install) { _, _ -> startDownload(r) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    // ---------- downloaden en installeren ----------

    private fun startDownload(r: Release) {
        if (!canInstallPackages()) {
            askForInstallPermission()
            return
        }

        val dm = activity.getSystemService(DownloadManager::class.java) ?: return

        // Een oude download met dezelfde naam zou anders blijven staan.
        File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME).delete()

        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.url))
                .setTitle(activity.getString(R.string.app_name))
                .setDescription(activity.getString(R.string.update_downloading, r.versionName))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    activity, Environment.DIRECTORY_DOWNLOADS, FILE_NAME
                )
        )
        Toast.makeText(activity, R.string.update_downloading_toast, Toast.LENGTH_SHORT).show()
        awaitDownload(id)
    }

    private fun awaitDownload(id: Long) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                runCatching { activity.unregisterReceiver(this) }
                openInstaller()
            }
        }
        /* Dit is een systeembroadcast, dus hij moet als geëxporteerd worden
           geregistreerd — anders komt hij op Android 14+ nooit aan. */
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun openInstaller() {
        val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)
        if (!file.isFile) {
            Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /**
     * Vanaf Android 8 moet een app apart toestemming krijgen om een installatie
     * te mogen starten. Die vraag stelt het systeem zelf, in de instellingen.
     */
    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.packageManager.canRequestPackageInstalls()
        } else true

    private fun askForInstallPermission() {
        Toast.makeText(activity, R.string.update_needs_permission, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
        }
    }

    // ---------- hulpjes ----------

    private fun currentVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "AdeptiAppUpdate"
        private const val TIMEOUT_MS = 5_000
        private const val FILE_NAME = "adepti-academy.apk"

        const val VERSION_URL = "https://the-adepti.web.app/app-version.json"
    }
}
