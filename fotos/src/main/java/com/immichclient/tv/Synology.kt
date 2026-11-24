package com.immichclient.tv

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Synology Photos (DSM 7) jako dodatkowe źródło: albumy utworzone w Synology Photos i ich zdjęcia/filmy. Używa
 * webowego API Synology (SYNO.API.Auth, SYNO.Foto.Browse.Album/Item, SYNO.Foto.Thumbnail) — nieoficjalnego, ale
 * stabilnego od lat. Logowanie kontem DSM (opcjonalnie z kodem 2FA); hasło zostaje w prywatnych danych aplikacji,
 * żeby po wygaśnięciu sesji zalogować się ponownie bez pytania.
 *
 * Zdjęcia z Synology mają w aplikacji id „syno:<typ>:<unit_id>:<cache_key>”; [Immich.thumb] i reszta adresów
 * rozpoznają ten przedrostek i prowadzą do Synology, więc siatka, przeglądarka i pokaz działają bez zmian.
 */
object Synology {
    private const val PREFS = "synology"
    private const val TAG = "Synology"

    @Volatile var adres = ""; private set
    @Volatile private var konto = ""
    @Volatile private var haslo = ""
    @Volatile private var sid = ""
    /** zmienia się po zalogowaniu / wylogowaniu (Compose) */
    var stan by mutableIntStateOf(0)
        private set

    val skonfigurowane get() = adres.isNotBlank() && sid.isNotBlank()
    val nazwaKonta get() = konto

    fun wczytaj(c: Context) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        adres = p.getString("adres", "").orEmpty(); konto = p.getString("konto", "").orEmpty()
        haslo = p.getString("haslo", "").orEmpty(); sid = p.getString("sid", "").orEmpty()
        stan++
    }

    private fun zapisz(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("adres", adres).putString("konto", konto).putString("haslo", haslo).putString("sid", sid).apply()
        stan++
    }

    fun wyloguj(c: Context) {
        adres = ""; konto = ""; haslo = ""; sid = ""
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        stan++
    }

    /**
     * „192.168.1.10” → „http://192.168.1.10:5000”; „…:5001” (domyślny port HTTPS w DSM) → zawsze „https://…:5001”,
     * bo zwykłe HTTP na port HTTPS DSM odrzuca błędem 400.
     */
    fun normalizuj(a: String): String {
        var s = a.trim().trimEnd('/')
        val maSchemat = s.startsWith("http://") || s.startsWith("https://")
        val host = s.substringAfter("://").substringBefore('/')
        if (!maSchemat) s = (if (host.endsWith(":5001")) "https://" else "http://") + s
        if (s.startsWith("http://") && host.endsWith(":5001")) s = "https://" + s.removePrefix("http://")
        if (!host.contains(':')) s = s.replaceFirst(host, host + if (s.startsWith("https://")) ":5001" else ":5000")
        return s
    }

    /**
     * NAS w sieci domowej ma zwykle certyfikat „z podpisem własnym”, którego Android nie uznaje. Dla adresu NAS z sieci
     * lokalnej (192.168.x, 10.x, 172.16–31.x, *.local) aplikacja przyjmuje taki certyfikat; dla adresów publicznych
     * obowiązuje zwykła weryfikacja.
     */
    private val klientLan: OkHttpClient by lazy {
        val ufaj = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(ufaj), SecureRandom()) }
        Immich.http.newBuilder().sslSocketFactory(ssl.socketFactory, ufaj).hostnameVerifier { _, _ -> true }.build()
    }

    private fun lokalny(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    /** Czy to adres skonfigurowanego NAS w sieci lokalnej (wtedy klient z certyfikatami z podpisem własnym). */
    fun naszLokalny(u: HttpUrl): Boolean {
        val nas = adres.toHttpUrlOrNull() ?: return false
        return u.host == nas.host && u.port == nas.port && lokalny(u.host)
    }

    /** Wszystkie połączenia aplikacji (Immich, obrazy, filmy): do lokalnego NAS przez [klientLan], reszta zwykle. */
    val polaczenia = okhttp3.Call.Factory { r -> if (naszLokalny(r.url)) klientLan.newCall(r) else Immich.http.newCall(r) }

    private fun url(api: String, metoda: String, wersja: Int, vararg param: Pair<String, String>, zSid: Boolean = true): HttpUrl {
        val b = ("$adres/webapi/entry.cgi".toHttpUrlOrNull() ?: throw IOException("niepoprawny adres Synology")).newBuilder()
            .addQueryParameter("api", api).addQueryParameter("method", metoda).addQueryParameter("version", wersja.toString())
        for ((k, v) in param) b.addQueryParameter(k, v)
        if (zSid) b.addQueryParameter("_sid", sid)
        return b.build()
    }

    /** Błąd API Synology z kodem (np. 119 = sesja wygasła, 400 = złe hasło, 403 = potrzebny kod 2FA). */
    class Blad(val kod: Int) : IOException("Synology $kod")

    private suspend fun wywolaj(u: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        polaczenia.newCall(Request.Builder().url(u).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Synology HTTP ${r.code}" + if (r.code == 400 && body.contains("HTTPS", true)) " (HTTPS)" else "")
            val o = JSONObject(body)
            if (!o.optBoolean("success")) throw Blad(o.optJSONObject("error")?.optInt("code") ?: -1)
            o.optJSONObject("data") ?: JSONObject()
        }
    }

    /** Logowanie; [otp] = kod z aplikacji uwierzytelniającej, gdy konto ma 2FA. */
    suspend fun zaloguj(c: Context, adresNas: String, login: String, hasloNas: String, otp: String?) {
        adres = normalizuj(adresNas); konto = login.trim(); haslo = hasloNas
        val p = mutableListOf("account" to konto, "passwd" to haslo, "session" to "SynologyPhotos", "format" to "sid")
        if (!otp.isNullOrBlank()) p += "otp_code" to otp.trim()
        val d = wywolaj(url("SYNO.API.Auth", "login", 7, *p.toTypedArray(), zSid = false))
        sid = d.optString("sid")
        if (sid.isBlank()) throw IOException("Synology: brak sesji")
        zapisz(c)
        Log.i(TAG, "zalogowano jako $konto")
    }

    /** Wywołanie z jednorazowym ponownym logowaniem, gdy sesja wygasła (bez 2FA; z 2FA trzeba zalogować się ręcznie). */
    private suspend fun zSesja(blok: () -> HttpUrl): JSONObject = try { wywolaj(blok()) } catch (e: Blad) {
        if (e.kod !in setOf(106, 107, 119) || haslo.isBlank()) throw e
        val d = wywolaj(url("SYNO.API.Auth", "login", 7, "account" to konto, "passwd" to haslo, "session" to "SynologyPhotos", "format" to "sid", zSid = false))
        sid = d.optString("sid"); Taal.ctx.let { zapisz(it) }
        wywolaj(blok())
    }

    /** Album Synology; [haslo] = passphrase albumu udostępnionego mi (wtedy elementy i miniatury idą przez nie). */
    class AlbumSyno(val id: Int, val nazwa: String, val ile: Int, val okladka: String?, val od: java.time.LocalDate?, val haslo: String? = null, val wlasciciel: String? = null)

    /** Albumy z Synology Photos (przestrzeń osobista), od najnowszych. */
    suspend fun albumy(): List<AlbumSyno> = listaAlbumow("SYNO.Foto.Browse.Album", "list", 2, udostepnione = false)

    /** Albumy udostępnione mi przez innych użytkowników (zakładka „Udostępnione mi” w Synology Photos). */
    suspend fun albumyUdostepnione(): List<AlbumSyno> = listaAlbumow("SYNO.Foto.Sharing.Misc", "list_shared_with_me_album", 2, udostepnione = true)

    private suspend fun listaAlbumow(api: String, metoda: String, wersja: Int, udostepnione: Boolean): List<AlbumSyno> = withContext(Dispatchers.Default) {
        val wynik = ArrayList<AlbumSyno>()
        var offset = 0
        while (true) {
            val d = zSesja { url(api, metoda, wersja, "offset" to "$offset", "limit" to "200", "additional" to "[\"thumbnail\",\"sharing_info\"]") }
            val l = d.optJSONArray("list") ?: break
            for (i in 0 until l.length()) l.getJSONObject(i).let { o ->
                val haslo = if (udostepnione) o.optString("passphrase").ifBlank { null } else null
                val t = o.optJSONObject("additional")?.optJSONObject("thumbnail")
                val okladka = t?.let { id("photo", it.optInt("unit_id"), it.optString("cache_key"), haslo?.let { h -> "P$h" }.orEmpty()) }
                val od = o.optLong("start_time").takeIf { it > 0 }?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC).toLocalDate() }
                val wl = o.optJSONObject("additional")?.optJSONObject("sharing_info")?.optJSONObject("owner")?.optString("name")?.ifBlank { null }
                wynik.add(AlbumSyno(o.optInt("id"), o.optString("name"), o.optInt("item_count"), okladka, od, haslo, wl))
            }
            if (l.length() < 200) break
            offset += 200
        }
        Log.i(TAG, "albumy ($metoda): ${wynik.size}")
        wynik.sortedByDescending { it.od }
    }

    /** Folder przestrzeni współdzielonej. */
    class FolderSyno(val id: Int, val nazwa: String)

    /** Główny folder przestrzeni współdzielonej (Team Space). */
    suspend fun folderGlowny(): FolderSyno = withContext(Dispatchers.Default) {
        val f = zSesja { url("SYNO.FotoTeam.Browse.Folder", "get", 1) }.optJSONObject("folder") ?: throw IOException("Synology: brak przestrzeni współdzielonej")
        FolderSyno(f.optInt("id"), f.optString("name").substringAfterLast('/').ifBlank { "/" })
    }

    /** Podfoldery folderu przestrzeni współdzielonej, po nazwie. */
    suspend fun podfoldery(id: Int): List<FolderSyno> = withContext(Dispatchers.Default) {
        val wynik = ArrayList<FolderSyno>()
        var offset = 0
        while (true) {
            val l = zSesja { url("SYNO.FotoTeam.Browse.Folder", "list", 1, "id" to "$id", "offset" to "$offset", "limit" to "500", "sort_by" to "filename", "sort_direction" to "asc") }
                .optJSONArray("list") ?: break
            for (i in 0 until l.length()) l.getJSONObject(i).let { o -> wynik.add(FolderSyno(o.optInt("id"), o.optString("name").substringAfterLast('/'))) }
            if (l.length() < 500) break
            offset += 500
        }
        wynik
    }

    /** Zdjęcia i filmy bezpośrednio w folderze przestrzeni współdzielonej, strona po 100. */
    suspend fun elementyFolderu(folderId: Int, strona: Int): Pagina = withContext(Dispatchers.Default) {
        val d = zSesja {
            url("SYNO.FotoTeam.Browse.Item", "list", 1, "folder_id" to "$folderId", "offset" to "${(strona - 1) * 100}", "limit" to "100",
                "sort_by" to "takentime", "sort_direction" to "asc", "additional" to DODATKI)
        }
        val l = d.optJSONArray("list")
        val lista = if (l == null) emptyList() else List(l.length()) { i -> element(l.getJSONObject(i), "T") }.filterNotNull()
        Pagina(lista, if ((l?.length() ?: 0) >= 100) strona + 1 else null)
    }

    private const val DODATKI = "[\"thumbnail\",\"resolution\",\"orientation\",\"gps\",\"address\"]"

    /** Zdjęcia i filmy albumu, strona po 100 (strona od 1), od najstarszych jak w Synology Photos. */
    suspend fun elementy(album: AlbumSyno, strona: Int): Pagina = withContext(Dispatchers.Default) {
        val d = zSesja {
            val p = mutableListOf("offset" to "${(strona - 1) * 100}", "limit" to "100", "sort_by" to "takentime", "sort_direction" to "asc", "additional" to DODATKI)
            // album udostępniony mi: elementy przez passphrase, nie przez id
            if (album.haslo != null) p += "passphrase" to album.haslo else p += "album_id" to "${album.id}"
            url("SYNO.Foto.Browse.Item", "list", 1, *p.toTypedArray())
        }
        val l = d.optJSONArray("list")
        val dodatek = album.haslo?.let { "P$it" }.orEmpty()
        val lista = if (l == null) emptyList() else List(l.length()) { i -> element(l.getJSONObject(i), dodatek) }.filterNotNull()
        Pagina(lista, if ((l?.length() ?: 0) >= 100) strona + 1 else null)
    }

    private fun element(o: JSONObject, dodatek: String): Asset? {
        val dod = o.optJSONObject("additional") ?: return null
        val t = dod.optJSONObject("thumbnail") ?: return null
        val typ = o.optString("type")
        val wideo = typ == "video"
        val r = dod.optJSONObject("resolution")
        var w = r?.optInt("width") ?: 0; var h = r?.optInt("height") ?: 0
        if (dod.optInt("orientation") in 5..8) w = h.also { h = w }
        val adr = dod.optJSONObject("address")
        val gps = dod.optJSONObject("gps")
        return Asset(
            id = id(if (wideo) "video" else "photo", t.optInt("unit_id", o.optInt("id")), t.optString("cache_key"), dodatek),
            ratio = if (w > 0 && h > 0) w.toFloat() / h else 1.5f,
            video = wideo, thumbhash = null,
            datum = LocalDateTime.ofEpochSecond(o.optLong("time"), 0, ZoneOffset.UTC),
            duur = null,
            stad = adr?.optString("city")?.ifBlank { null } ?: adr?.optString("town")?.ifBlank { null },
            land = adr?.optString("country")?.ifBlank { null },
            favoriet = false,
            lat = gps?.optDouble("latitude")?.takeIf { !it.isNaN() && it != 0.0 },
            lon = gps?.optDouble("longitude")?.takeIf { !it.isNaN() && it != 0.0 },
        )
    }

    /** „syno:<typ>:<unit_id>:<cache_key>:<dodatek>”; dodatek: "" = przestrzeń osobista, "T" = współdzielona, "P<passphrase>" = album udostępniony mi */
    private fun id(typ: String, unit: Int, cacheKey: String, dodatek: String = "") = "syno:$typ:$unit:$cacheKey:$dodatek"

    fun jestSyno(id: String) = id.startsWith("syno:")

    /** Adres miniatury: rozmiar sm (siatka), m, xl (przeglądarka, pełny ekran). */
    fun miniatura(id: String, rozmiar: String): String {
        val c = id.split(':')
        val unit = c.getOrNull(2).orEmpty(); val klucz = c.getOrNull(3).orEmpty(); val dod = c.getOrNull(4).orEmpty()
        val p = mutableListOf("mode" to "download", "id" to unit, "type" to "unit", "size" to rozmiar, "cache_key" to klucz)
        if (dod.startsWith("P")) p += "passphrase" to dod.drop(1)
        val api = if (dod == "T") "SYNO.FotoTeam.Thumbnail" else "SYNO.Foto.Thumbnail"
        // wersja 1: jedyna, którą SYNO.Foto.Thumbnail obsługuje na każdym DSM 7 (z wersją 2 NAS zwracał błąd zamiast obrazka)
        return url(api, "get", 1, *p.toTypedArray()).toString()
    }

    /**
     * Sprawdzenie miniatury (gdy siatka zostaje pusta): pobiera jedną i zwraca null = obrazek dotarł, albo opis
     * odpowiedzi NAS (kod HTTP, typ, początek treści) — trafia też do Diagnostyki.
     */
    suspend fun sprawdzMiniature(id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            polaczenia.newCall(Request.Builder().url(miniatura(id, "sm")).build()).execute().use { r ->
                val typ = r.header("Content-Type").orEmpty()
                if (r.isSuccessful && typ.startsWith("image")) null
                else "HTTP ${r.code} $typ ${r.body?.string().orEmpty().take(160)}"
            }
        }.getOrElse { it.message ?: it.toString() }?.also { Diagnose.noteer("synology miniatura", IOException(it)) }
    }

    /** Adres filmu (pobranie oryginału, odtwarzacz czyta go strumieniowo). */
    fun film(id: String): String {
        val c = id.split(':')
        val unit = c.getOrNull(2).orEmpty(); val dod = c.getOrNull(4).orEmpty()
        val p = mutableListOf("unit_id" to "[$unit]", "force_download" to "false")
        if (dod.startsWith("P")) p += "passphrase" to dod.drop(1)
        return url(if (dod == "T") "SYNO.FotoTeam.Download" else "SYNO.Foto.Download", "download", 2, *p.toTypedArray()).toString()
    }
}
