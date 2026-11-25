package com.immichclient.tv

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Dostęp zdalny (np. przez Tailscale): dwa adresy serwera — domowy i zdalny — i wybór działającego. Gdy adres należy
 * do Tailscale, aplikacja prosi Tailscale (zainstalowany na telewizorze) o połączenie w tle (komunikat CONNECT_VPN,
 * bez otwierania jego okna). Gdy żaden adres nie odpowiada, serwer „śpi” (np. włączany tylko wieczorem): ekran z
 * ponawianiem co minutę.
 */
object Zdalnie {
    private const val TAG = "Zdalnie"
    const val TAILSCALE = "com.tailscale.ipn"

    enum class Stan { SPRAWDZAM, OK, SPI }

    var stan by mutableStateOf(Stan.SPRAWDZAM)
        private set
    /** true = aplikacja korzysta teraz z adresu zdalnego */
    var zdalnie by mutableStateOf(false)
        private set
    /** ostatnia próba: co odpowiedziały adresy (do ekranu „serwer śpi”) */
    var opis by mutableStateOf<String?>(null)
        private set

    private val szybki by lazy {
        Immich.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** Adres Tailscale: 100.64.0.0/10 (CGNAT) albo nazwa MagicDNS *.ts.net. */
    fun jestTailscale(adres: String): Boolean {
        val host = adres.toHttpUrlOrNull()?.host ?: adres.substringAfter("://").substringBefore('/').substringBefore(':')
        if (host.endsWith(".ts.net")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }

    fun tailscaleZainstalowany(c: Context): Boolean = runCatching { c.packageManager.getLaunchIntentForPackage(TAILSCALE) != null }.getOrDefault(false)

    /** Poproś Tailscale o połączenie w tle (działa, gdy Tailscale jest na tym telewizorze zalogowany i ma zgodę na VPN). */
    fun polacz(c: Context) {
        if (!tailscaleZainstalowany(c)) return
        runCatching {
            c.sendBroadcast(Intent("com.tailscale.ipn.CONNECT_VPN").setClassName(TAILSCALE, "com.tailscale.ipn.IPNReceiver"))
            Log.i(TAG, "Tailscale: prośba o połączenie")
        }.onFailure { Log.w(TAG, "Tailscale: broadcast", it) }
    }

    /** Otwórz aplikację Tailscale (gdy trzeba się zalogować albo coś zmienić ręcznie). */
    fun otworz(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(TAILSCALE)
            ?: c.packageManager.getLeanbackLaunchIntentForPackage(TAILSCALE) ?: return
        runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Czy pod adresem odpowiada Immich (/api/server/ping → pong). */
    private suspend fun ping(adres: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            szybki.newCall(Request.Builder().url("$adres/api/server/ping").build()).execute().use { r ->
                if (r.code == 200 && runCatching { JSONObject(r.body?.string().orEmpty()).optString("res") }.getOrNull() == "pong") null
                else "HTTP ${r.code}"
            }
        }.getOrElse { it.javaClass.simpleName }
    }

    /**
     * Wybierz działający adres: najpierw domowy (szybszy), potem zdalny. Przed adresem Tailscale — prośba o połączenie
     * i kilka sekund cierpliwości, bo Tailscale łączy się chwilę. true = jest połączenie (adres ustawiony w [Immich]).
     */
    suspend fun wybierz(c: Context): Boolean {
        val dom = Immich.serverDom
        val zd = Immich.serverZdalny
        val adresy = listOf(dom, zd).filter { it.isNotBlank() }.distinct()
        if (adresy.isEmpty()) { stan = Stan.OK; return true }
        val wyniki = LinkedHashMap<String, String>()
        var tailscaleProszony = false
        for (a in adresy) {
            if (jestTailscale(a) && !tailscaleProszony) { polacz(c); tailscaleProszony = true }
            val w = ping(a)
            if (w == null) { ustaw(a, zd); return true }
            wyniki[a] = w
        }
        // Tailscale dopiero się łączy: jeszcze kilka prób adresów Tailscale
        val ts = adresy.filter(::jestTailscale)
        if (ts.isNotEmpty() && tailscaleZainstalowany(c)) repeat(4) {
            delay(2500)
            for (a in ts) { val w = ping(a); if (w == null) { ustaw(a, zd); return true }; wyniki[a] = w }
        }
        opis = wyniki.entries.joinToString("  ·  ") { (a, w) -> "${a.substringAfter("://")}: $w" }
        Log.i(TAG, "serwer niedostępny: $opis")
        stan = Stan.SPI
        return false
    }

    private fun ustaw(a: String, zd: String) {
        if (Immich.server != a) Immich.ustawAktywny(a)
        zdalnie = a == zd && a != Immich.serverDom
        opis = null
        stan = Stan.OK
        Log.i(TAG, "serwer: $a" + if (zdalnie) " (zdalnie)" else "")
    }

    /** Ręczne „Kontynuuj mimo to”: aplikacja działa dalej (np. z zapamiętanymi zdjęciami), bez sprawdzania. */
    fun pomin() { stan = Stan.OK }
}
