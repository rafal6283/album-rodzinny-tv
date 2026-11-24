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
import okhttp3.Request
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

    /** „192.168.1.10:5000” → „http://192.168.1.10:5000” (bez ukośnika na końcu). */
    fun normalizuj(a: String): String {
        var s = a.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

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
        Immich.http.newCall(Request.Builder().url(u).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Synology HTTP ${r.code}")
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

    class AlbumSyno(val id: Int, val nazwa: String, val ile: Int, val okladka: String?, val od: java.time.LocalDate?)

    /** Albumy z Synology Photos (przestrzeń osobista), od najnowszych. */
    suspend fun albumy(): List<AlbumSyno> = withContext(Dispatchers.Default) {
        val wynik = ArrayList<AlbumSyno>()
        var offset = 0
        while (true) {
            val d = zSesja { url("SYNO.Foto.Browse.Album", "list", 2, "offset" to "$offset", "limit" to "200", "additional" to "[\"thumbnail\"]") }
            val l = d.optJSONArray("list") ?: break
            for (i in 0 until l.length()) l.getJSONObject(i).let { o ->
                val t = o.optJSONObject("additional")?.optJSONObject("thumbnail")
                val okladka = t?.let { id("photo", it.optInt("unit_id"), it.optString("cache_key")) }
                val od = o.optLong("start_time").takeIf { it > 0 }?.let { LocalDateTime.ofEpochSecond(it, 0, ZoneOffset.UTC).toLocalDate() }
                wynik.add(AlbumSyno(o.optInt("id"), o.optString("name"), o.optInt("item_count"), okladka, od))
            }
            if (l.length() < 200) break
            offset += 200
        }
        wynik.sortedByDescending { it.od }
    }

    /** Zdjęcia i filmy albumu, strona po 100 (strona od 1), od najstarszych jak w Synology Photos. */
    suspend fun elementy(albumId: Int, strona: Int): Pagina = withContext(Dispatchers.Default) {
        val d = zSesja {
            url("SYNO.Foto.Browse.Item", "list", 1, "album_id" to "$albumId", "offset" to "${(strona - 1) * 100}", "limit" to "100",
                "sort_by" to "takentime", "sort_direction" to "asc",
                "additional" to "[\"thumbnail\",\"resolution\",\"orientation\",\"gps\",\"address\"]")
        }
        val l = d.optJSONArray("list")
        val lista = if (l == null) emptyList() else List(l.length()) { i -> element(l.getJSONObject(i)) }.filterNotNull()
        Pagina(lista, if ((l?.length() ?: 0) >= 100) strona + 1 else null)
    }

    private fun element(o: JSONObject): Asset? {
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
            id = id(if (wideo) "video" else "photo", t.optInt("unit_id", o.optInt("id")), t.optString("cache_key")),
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

    private fun id(typ: String, unit: Int, cacheKey: String) = "syno:$typ:$unit:$cacheKey"

    fun jestSyno(id: String) = id.startsWith("syno:")

    /** Adres miniatury: rozmiar sm (siatka), m, xl (przeglądarka, pełny ekran). */
    fun miniatura(id: String, rozmiar: String): String {
        val c = id.split(':')
        val unit = c.getOrNull(2).orEmpty(); val klucz = c.getOrNull(3).orEmpty()
        return url("SYNO.Foto.Thumbnail", "get", 2, "mode" to "download", "id" to unit, "type" to "unit", "size" to rozmiar, "cache_key" to klucz).toString()
    }

    /** Adres filmu (pobranie oryginału, odtwarzacz czyta go strumieniowo). */
    fun film(id: String): String {
        val unit = id.split(':').getOrNull(2).orEmpty()
        return url("SYNO.Foto.Download", "download", 2, "unit_id" to "[$unit]", "force_download" to "false").toString()
    }
}
