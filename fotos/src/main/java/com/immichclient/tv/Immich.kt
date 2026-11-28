package com.immichclient.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Immich (alleen lezen). Serveradres en API-sleutel staan in de prefs: gezet via het instelscherm (Koppel.kt, met de
 * telefoon) of via adb met `am broadcast -a com.immichclient.tv.SET_KEY --es sleutel … [--es server …]` (zie SleutelOntvanger).
 * Een eigen launcher kan een profiel meegeven (extra "kijker", bij het zetten én bij het starten van de app): dan geldt
 * per profiel een eigen account.
 */
object Immich {
    private const val PREFS = "immich"

    @Volatile var sleutel = ""
        private set
    /** Basisadres zonder /api, bijv. http://192.168.1.10:2283 of https://photos.example.com — het adres dat nu gebruikt wordt */
    @Volatile var server = ""
        private set
    /** adres główny (dom) i zdalny (np. Tailscale 100.x / *.ts.net); aktywny = [server], wybiera [Zdalnie.wybierz] */
    @Volatile var serverDom = ""
        private set
    @Volatile var serverZdalny = ""
        private set
    /** Profiel waarvan het account nu gebruikt wordt (null = het algemene account). */
    @Volatile var kijker: String? = null
        private set
    val api get() = "$server/api"
    /** Verandert bij koppelen/ontkoppelen zodat Compose het merkt (sleutel/server zelf zijn gewone velden). */
    var stand by mutableIntStateOf(0)
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        // de sleutel alleen naar de eigen server (niet naar bijv. tiles.immich.cloud)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.url.toString().startsWith(server)) req.newBuilder().header("x-api-key", sleutel).build() else req)
        }
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 })
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // slim zoeken kan na een tijdje stilte even duren (ML-model laadt)
        .build()

    /** Kies het account voor dit profiel (valt terug op het algemene account). */
    fun kiesAccount(c: Context, kijker: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // bez wskazanego profilu: ostatnio wybrany profil domownika (Ustawienia → Profile), inaczej konto główne
        val k = (kijker ?: p.getString(Profile.AKTYWNY, null))?.takeIf { it.isNotBlank() && p.contains("sleutel_$it") }
        this.kijker = k
        sleutel = p.getString(if (k != null) "sleutel_$k" else "sleutel", "") ?: ""
        serverDom = p.getString(if (k != null) "server_$k" else "server", "") ?: ""
        serverZdalny = p.getString(if (k != null) "zdalny_$k" else "zdalny", "") ?: ""
        server = serverDom.ifBlank { serverZdalny }
        ik = null
        Instellingen.laad(c, k)
        stand++
    }

    fun zetSleutel(c: Context, nieuw: String, kijker: String?, serverAdres: String? = null, zdalny: String? = null) {
        val na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("sleutel$na", nieuw.trim())
            serverAdres?.let { putString("server$na", Koppel.normaliseer(it)) }
            zdalny?.let { if (it.isBlank()) remove("zdalny$na") else putString("zdalny$na", Koppel.normaliseer(it)) }
        }.apply()
    }

    /** Adres zdalny (Tailscale) bieżącego konta; pusty = brak. */
    fun zetZdalny(c: Context, adres: String) {
        val na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        val a = adres.trim().takeIf { it.isNotEmpty() }?.let { Koppel.normaliseer(it) }.orEmpty()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (a.isEmpty()) remove("zdalny$na") else putString("zdalny$na", a) }.apply()
        serverZdalny = a
    }

    /** Przełącz na działający adres (dom albo zdalny). */
    fun ustawAktywny(adres: String) { server = adres }

    /** Sleutel (en adres) van dit account wissen; de sleutel zelf blijft in Immich staan tot je hem daar verwijdert. */
    fun ontkoppel(c: Context, kijker: String?) {
        val na = if (kijker.isNullOrBlank()) "" else "_$kijker"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("sleutel$na").remove("server$na").remove("zdalny$na").apply()
        kiesAccount(c, kijker)
    }

    // zdjęcia z Synology Photos (id „syno:…”) prowadzą do Synology, reszta do Immich
    fun thumb(id: String) = if (Synology.jestSyno(id)) Synology.miniatura(id, "sm") else "$api/assets/$id/thumbnail?size=thumbnail"
    fun preview(id: String) = if (Synology.jestSyno(id)) Synology.miniatura(id, "xl") else "$api/assets/$id/thumbnail?size=preview"
    /** pełna rozdzielczość (konwersja JPEG z Immich ≥ 1.133); wystarcza uprawnienie asset.view */
    fun pelny(id: String) = if (Synology.jestSyno(id)) Synology.miniatura(id, "xl") else "$api/assets/$id/thumbnail?size=fullsize"
    fun video(id: String) = if (Synology.jestSyno(id)) Synology.film(id) else "$api/assets/$id/video/playback"
    /** oryginalny plik filmu (HDR10 / HLG / Dolby Vision zachowane; uprawnienie asset.download) */
    fun videoOryginal(id: String) = "$api/assets/$id/original"
    /** miniatura twarzy osoby; po zmianie zdjęcia głównego inny adres (pamięć obrazków nie pokaże starego) */
    fun gezicht(id: String) = "$api/people/$id/thumbnail" + (Edycja.wersjaGlownych.takeIf { it > 0 }?.let { "?w=$it" } ?: "")

    private suspend fun haal(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Immich ${r.code}: ${req.url.encodedPath} ${body.take(200)}")
            body
        }
    }

    private suspend fun get(pad: String) = haal(Request.Builder().url(api + pad).build())
    private suspend fun post(pad: String, json: JSONObject) =
        haal(Request.Builder().url(api + pad).post(json.toString().toRequestBody("application/json".toMediaType())).build())

    /** Ingelogde gebruiker (naam, e-mail) voor Instellingen en de begroeting; één keer opgehaald per account. */
    var ik by mutableStateOf<Gebruiker?>(null)
        private set
    suspend fun laadIk(): Gebruiker? = ik ?: runCatching { JSONObject(get("/users/me")).let { Gebruiker(it.optString("name"), it.optString("email")) } }
        .onFailure { Log.w("Fotos", "users/me", it) }.getOrNull()?.also { ik = it }

    /** Versie van de Immich-server ("3.3.3"), voor "Over". */
    suspend fun serverVersie(): String? = runCatching {
        JSONObject(get("/server/version")).let { "${it.optInt("major")}.${it.optInt("minor")}.${it.optInt("patch")}" }
    }.getOrNull()

    // ---- Tijdlijn (ook voor albums, personen en favorieten: zelfde endpoint met een filter) ----

    suspend fun maanden(filter: Filter, nieuwsteEerst: Boolean): List<Maand> {
        val a = JSONArray(get("/timeline/buckets?" + filter.query(nieuwsteEerst)))
        return List(a.length()) { i -> a.getJSONObject(i).let { Maand(it.getString("timeBucket"), it.getInt("count")) } }
    }

    suspend fun maand(filter: Filter, nieuwsteEerst: Boolean, maand: String): List<Asset> = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/timeline/bucket?" + filter.query(nieuwsteEerst) + "&withCoordinates=true&timeBucket=$maand"))
        val ids = o.getJSONArray("id")
        val ratio = o.optJSONArray("ratio")
        val isImage = o.getJSONArray("isImage")
        val hash = o.optJSONArray("thumbhash")
        val datum = o.getJSONArray("fileCreatedAt")
        val offset = o.optJSONArray("localOffsetHours")
        val duur = o.optJSONArray("duration")
        val stad = o.optJSONArray("city")
        val land = o.optJSONArray("country")
        val fav = o.optJSONArray("isFavorite")
        val lat = o.optJSONArray("latitude")
        val lon = o.optJSONArray("longitude")
        val live = o.optJSONArray("livePhotoVideoId")
        fun coord(a: JSONArray?, i: Int) = a?.optDouble(i)?.takeIf { !it.isNaN() }
        List(ids.length()) { i ->
            Asset(
                id = ids.getString(i),
                ratio = ratio?.optDouble(i, 1.0)?.toFloat()?.takeIf { it > 0 && !it.isNaN() } ?: 1f,
                video = !isImage.getBoolean(i),
                thumbhash = hash?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                datum = lokaleTijd(datum.getString(i), offset?.optDouble(i, 0.0) ?: 0.0),
                duur = duur?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                stad = stad?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                land = land?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" }?.let(Landnaam::vertaal),
                favoriet = fav?.optBoolean(i) ?: false,
                lat = coord(lat, i),
                lon = coord(lon, i),
                live = live?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
            )
        }
    }

    // ---- Zoeken ----

    /** Slim zoeken (CLIP): "strand", "verjaardag met taart", "hond in de sneeuw". Volgorde = relevantie; taal = de app-taal. */
    suspend fun zoekSlim(tekst: String, pagina: Int, alleenVideo: Boolean?, filtr: ZoekFiltr = ZoekFiltr()): Pagina {
        val body = JSONObject().put("query", tekst).put("language", Taal.code).put("size", 120).put("page", pagina)
        if (alleenVideo != null) body.put("type", if (alleenVideo) "VIDEO" else "IMAGE")
        filtr.dopisz(body)
        return pagina(JSONObject(post("/search/smart", body)))
    }

    /** Het ML-model van Immich gaat na een paar minuten stilte uit het geheugen; dan duurt de eerste zoekopdracht ~9 s. */
    suspend fun warmZoeken() {
        runCatching { post("/search/smart", JSONObject().put("query", "photo").put("language", Taal.code).put("size", 1)) }
    }

    suspend fun zoekPlaats(stad: String, pagina: Int): Pagina =
        pagina(JSONObject(post("/search/metadata", JSONObject().put("city", stad).put("size", 250).put("page", pagina).put("order", "desc"))))

    /**
     * Bestandsnaam / omschrijving / OCR (tekst op de foto), eventueel alleen foto's of video's. Zonder tekst = alle
     * foto's of video's van de tijdlijn, nieuwste eerst (zoals het type-filter in de web-app).
     */
    suspend fun zoekTekst(tekst: String?, veld: String, pagina: Int, alleenVideo: Boolean? = null, filtr: ZoekFiltr = ZoekFiltr()): Pagina {
        val body = JSONObject().put("size", 250).put("page", pagina).put("order", "desc")
        if (tekst != null) body.put(veld, tekst) else body.put("visibility", "timeline")
        if (alleenVideo != null) body.put("type", if (alleenVideo) "VIDEO" else "IMAGE")
        filtr.dopisz(body)
        return pagina(JSONObject(post("/search/metadata", body)))
    }

    private suspend fun pagina(o: JSONObject): Pagina = withContext(Dispatchers.Default) {
        val a = o.getJSONObject("assets")
        val items = a.getJSONArray("items")
        Pagina(List(items.length()) { asset(items.getJSONObject(it)) }, a.optString("nextPage").toIntOrNull())
    }

    suspend fun plaatsen(): List<Plaats> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/search/cities"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val exif = o.optJSONObject("exifInfo")
            Plaats(exif?.optString("city").orEmpty(), Landnaam.vertaal(exif?.optString("country").orEmpty()), o.getString("id"))
        }.filter { it.stad.isNotBlank() }
            // to samo miasto dwa razy (np. różna pisownia kraju w EXIF) wywracało ekran Odkrywaj: klucz listy musi być unikalny
            .distinctBy { it.stad to it.land }
    }

    suspend fun personen(): List<Persoon> = withContext(Dispatchers.Default) {
        val a = JSONObject(get("/people?size=1000&withHidden=false")).getJSONArray("people")
        List(a.length()) { i -> a.getJSONObject(i).let { Persoon(it.getString("id"), it.optString("name"), it.optBoolean("isFavorite")) } }
            .filter { it.naam.isNotBlank() }
    }

    /**
     * Wszystkie rozpoznane osoby (sekcja Ludzie), także te bez imienia, w kolejności Immich (ulubione, potem z imieniem,
     * potem wg liczby zdjęć). Stronami po 500, bo nienazwanych twarzy bywa kilka tysięcy.
     */
    suspend fun wszystkieOsoby(): List<Persoon> = withContext(Dispatchers.Default) {
        val wynik = ArrayList<Persoon>()
        var strona = 1
        while (strona <= 40) {
            val o = JSONObject(get("/people?size=500&page=$strona&withHidden=false"))
            val a = o.getJSONArray("people")
            for (i in 0 until a.length()) a.getJSONObject(i).let { p ->
                if (!p.optBoolean("isHidden")) wynik.add(Persoon(p.getString("id"), p.optString("name").takeIf { it != "null" }.orEmpty().trim(), p.optBoolean("isFavorite")))
            }
            if (!o.optBoolean("hasNextPage") || a.length() == 0) break
            strona++
        }
        wynik.distinctBy { it.id }
    }

    // ---- Albums ----

    suspend fun albums(): List<Album> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/albums"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Album(
                id = o.getString("id"),
                naam = o.optString("albumName"),
                omslag = o.optString("albumThumbnailAssetId").takeIf { it.isNotEmpty() && it != "null" },
                aantal = o.optInt("assetCount"),
                van = o.optString("startDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                tot = o.optString("endDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                bijgewerkt = o.optString("lastModifiedAssetTimestamp").ifEmpty { o.optString("updatedAt") },
                gedeeld = o.optBoolean("shared"),
                oudsteEerst = o.optString("order") == "asc",
            )
        }
    }

    // ---- Herinneringen ("vandaag X jaar geleden") ----

    suspend fun herinneringen(dag: LocalDate): List<Herinnering> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/memories?for=$dag"))
        List(a.length()) { wspomnienie(a.getJSONObject(it), dag) }.filter { it.assets.isNotEmpty() }.sortedByDescending { it.jaar }
    }

    /**
     * Wszystkie wspomnienia z serwera (jak lista „Wspomnienia” w aplikacji Immich na telefon), także inne niż „tego dnia”,
     * np. urodziny z własnym tytułem. Od najnowszych.
     */
    suspend fun wszystkieWspomnienia(): List<Herinnering> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/memories"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val kiedy = (o.optString("memoryAt").ifEmpty { o.optString("showAt") }).take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            wspomnienie(o, kiedy)
        }.filter { it.assets.isNotEmpty() }.sortedByDescending { it.dag }
    }

    private fun wspomnienie(o: JSONObject, dag: LocalDate): Herinnering {
        val assets = o.getJSONArray("assets")
        val data = o.optJSONObject("data")
        fun tekst(x: JSONObject?, k: String) = x?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
        val typ = o.optString("type").ifEmpty { "on_this_day" }
        // tytuł: pole w danych albo w samym wspomnieniu (różne wersje / rodzaje wspomnień)
        val tytul = tekst(data, "title") ?: tekst(o, "title") ?: tekst(data, "name") ?: tekst(o, "name")
        if (typ != "on_this_day") Log.i("Fotos", "wspomnienie typu $typ: ${o.optJSONObject("data")}")
        return Herinnering(
            id = o.getString("id"),
            jaar = data?.optInt("year") ?: 0,
            dag = dag,
            // „Nie pokazuj we wspomnieniach” (lista na telewizorze)
            assets = List(assets.length()) { asset(assets.getJSONObject(it)) }.filter { !UkryteWeWspomnieniach.jest(it.id) }.sortedBy { it.datum },
            typ = typ, tytul = tytul,
        )
    }

    /** Willekeurige foto's (screensaver "alle foto's"): /search/random geeft [n] assets met exif in één call. */
    suspend fun willekeurig(n: Int, vanafJaar: Int = 0): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("type", "IMAGE").put("visibility", "timeline").put("withExif", true)
        if (vanafJaar > 0) q.put("takenAfter", "$vanafJaar-01-01T00:00:00.000Z")
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /** Losowe zdjęcia I filmy z osi czasu (przycisk „Losowo”); [ulubione] = tylko ulubione. */
    suspend fun losowe(n: Int, ulubione: Boolean = false, tylkoFilmy: Boolean = false): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("visibility", "timeline").put("withExif", true)
        if (ulubione) q.put("isFavorite", true)
        if (tylkoFilmy) q.put("type", "VIDEO")
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    // ---- Albums van één foto (viewer: "uit album …") ----

    private val albumsVan = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>?) = size > 300
    }

    /** Namen van de albums waar deze foto in zit (gecachet; fout of geen recht = lege lijst). */
    suspend fun albumsVan(id: String): List<String> {
        synchronized(albumsVan) { albumsVan[id] }?.let { return it }
        val namen = runCatching {
            val a = JSONArray(get("/albums?assetId=$id"))
            List(a.length()) { a.getJSONObject(it).optString("albumName") }.filter { it.isNotBlank() }.distinct()
        }.onFailure { Log.w("Fotos", "albums van $id: ${it.message}") }.getOrNull() ?: return emptyList()
        synchronized(albumsVan) { albumsVan[id] = namen }
        return namen
    }

    // ---- Rechten van de sleutel (zie Rechten.kt) ----

    /** Naam + rechten van de eigen sleutel; null als de server dat niet kent (dan [probeerRecht]). */
    suspend fun sleutelRechten(): Pair<String, Set<String>>? = runCatching {
        val o = JSONObject(get("/api-keys/me"))
        val p = o.getJSONArray("permissions")
        o.optString("name") to (0 until p.length()).map { p.getString(it) }.toSet()
    }.onFailure { Log.w("Fotos", "api-keys/me: ${it.message}") }.getOrNull()

    /** Recht testen via het bijbehorende endpoint: true = werkt, false = 403, null = niet te bepalen. */
    suspend fun probeerRecht(recht: String): Boolean? {
        val pad = when (recht) {
            "user.read" -> "/users/me"
            "asset.read" -> "/timeline/buckets?visibility=timeline&order=desc"
            "album.read" -> "/albums"
            "memory.read" -> "/memories?for=${LocalDate.now()}"
            "person.read" -> "/people?size=1"
            "map.read" -> "/map/markers?fileCreatedAfter=2999-01-01T00:00:00.000Z"
            "folder.read" -> "/view/folder/unique-paths"
            else -> return null
        }
        return try { get(pad); true } catch (e: IOException) {
            if (e.message.orEmpty().startsWith("Immich 403")) false else null
        }
    }

    // ---- Mappen (zoals de bestanden op de schijf of in een externe bibliotheek staan; recht folder.read) ----

    /** Alle mappen met foto's (volledige paden zoals Immich ze kent). */
    suspend fun mappen(): List<String> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/view/folder/unique-paths"))
        List(a.length()) { a.getString(it) }
    }

    /** Foto's en video's direct in deze map (niet in submappen), op datum. */
    suspend fun mapInhoud(pad: String): List<Asset> = withContext(Dispatchers.Default) {
        val url = ("$api/view/folder".toHttpUrlOrNull() ?: throw IOException("ongeldig serveradres"))
            .newBuilder().addQueryParameter("path", pad).build()
        val a = JSONArray(haal(Request.Builder().url(url).build()))
        List(a.length()) { asset(a.getJSONObject(it)) }.sortedBy { it.datum }
    }

    /** Eén asset (voor de viewer vanaf de kaart: daar zijn alleen id's bekend). */
    suspend fun asset(id: String): Asset = withContext(Dispatchers.Default) { asset(JSONObject(get("/assets/$id"))) }

    // ---- Kaart ----

    /**
     * Alle foto's met GPS (id, plek, plaats): één call (kunnen tienduizenden zijn: 47k = 6,6 MB JSON), nieuwste eerst.
     * Gestreamd gelezen (JsonReader) in plaats van als één JSONArray: dat kostte ~40 MB tijdelijke objecten op de
     * Java-heap (47k JSONObjects met elk een eigen map), precies op het moment dat de kaart ook geheugen nodig heeft.
     * Plaats- en landnamen worden gedeeld (dezelfde String voor dezelfde naam).
     */
    suspend fun markers(): Markers = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$api/map/markers").build()
        val ids = ArrayList<String>(4096); var lat = DoubleArray(4096); var lon = DoubleArray(4096)
        val stad = ArrayList<String?>(4096); val engels = ArrayList<String?>(4096)
        val pool = HashMap<String, String>()
        fun gedeeld(s: String?) = s?.takeIf { it.isNotEmpty() && it != "null" }?.let { pool.getOrPut(it) { it } }
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Immich ${r.code}: /api/map/markers ${r.body?.string().orEmpty().take(200)}")
            android.util.JsonReader(r.body!!.charStream()).use { j ->
                fun tekst(): String? = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); null } else j.nextString()
                j.beginArray()
                while (j.hasNext()) {
                    var id = ""; var la = Double.NaN; var lo = Double.NaN; var st: String? = null; var la2: String? = null
                    j.beginObject()
                    while (j.hasNext()) when (j.nextName()) {
                        "id" -> id = j.nextString()
                        "lat" -> la = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); Double.NaN } else j.nextDouble()
                        "lon" -> lo = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); Double.NaN } else j.nextDouble()
                        "city" -> st = gedeeld(tekst())
                        "country" -> la2 = gedeeld(tekst())
                        else -> j.skipValue()
                    }
                    j.endObject()
                    if (id.isEmpty() || la.isNaN() || lo.isNaN()) continue
                    val n = ids.size
                    if (n == lat.size) { lat = lat.copyOf(n * 2); lon = lon.copyOf(n * 2) }
                    ids.add(id); lat[n] = la; lon[n] = lo; stad.add(st); engels.add(la2)
                }
                j.endArray()
            }
        }
        val n = ids.size
        // landnaam en ISO-code één keer per verschillend land
        val naam = HashMap<String, String>(); val code = HashMap<String, String?>()
        val land = arrayOfNulls<String>(n); val iso = arrayOfNulls<String>(n)
        for (i in 0 until n) engels[i]?.let { e -> land[i] = naam.getOrPut(e) { Landnaam.vertaal(e) }; iso[i] = code.getOrPut(e) { Landnaam.iso(e) } }
        Markers(ids.toTypedArray(), lat.copyOf(n), lon.copyOf(n), stad.toTypedArray(), land, iso)
    }

    /** Kaartstijl van de Immich-server (licht of donker; satelliet = donkere stijl met Sentinel-2 eronder, zie Wereldkaart.kt). */
    suspend fun kaartStijl(licht: Boolean): String {
        val veld = if (licht) "mapLightStyleUrl" else "mapDarkStyleUrl"
        return runCatching { JSONObject(get("/server/config")).optString(veld).takeIf { it.startsWith("http") } }.getOrNull()
            ?: "https://tiles.immich.cloud/v1/style/${if (licht) "light" else "dark"}.json"
    }

    private suspend fun put(pad: String, json: JSONObject) =
        haal(Request.Builder().url(api + pad).put(json.toString().toRequestBody("application/json".toMediaType())).build())

    private suspend fun patch(pad: String, json: JSONObject) =
        haal(Request.Builder().url(api + pad).patch(json.toString().toRequestBody("application/json".toMediaType())).build())
    private suspend fun delete(pad: String, json: JSONObject) =
        haal(Request.Builder().url(api + pad).delete(json.toString().toRequestBody("application/json".toMediaType())).build())

    /** Zdjęcia osoby z okresu [od, do] (porównanie „kiedyś i dziś”), od najnowszych. */
    suspend fun osobaWOkresie(osobaId: String, od: LocalDate?, doDnia: LocalDate?, n: Int, rosnaco: Boolean = false): List<Asset> = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", n).put("page", 1).put("order", if (rosnaco) "asc" else "desc").put("withExif", true)
            .put("personIds", JSONArray(listOf(osobaId))).put("type", "IMAGE")
        od?.let { body.put("takenAfter", "${it}T00:00:00.000Z") }
        doDnia?.let { body.put("takenBefore", "${it}T23:59:59.999Z") }
        val a = JSONObject(post("/search/metadata", body)).getJSONObject("assets").getJSONArray("items")
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /** Zdjęcia i filmy osoby z dni [od, do] (urodziny z różnych lat), najwyżej [n]. */
    suspend fun osobaWDniach(osobaId: String, od: LocalDate, doDnia: LocalDate, n: Int = 200): List<Asset> = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", n).put("page", 1).put("order", "asc").put("withExif", true)
            .put("personIds", JSONArray(listOf(osobaId)))
            .put("takenAfter", "${od}T00:00:00.000Z").put("takenBefore", "${doDnia}T23:59:59.999Z")
        val a = JSONObject(post("/search/metadata", body)).getJSONObject("assets").getJSONArray("items")
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /**
     * Losowe zdjęcia osoby z okresu (wyszukiwanie losowe Immich, nie pierwsze N od początku okresu): podstawa losowości
     * w „Jak rosła”, „Kiedyś i dziś” i urodzinach.
     */
    suspend fun osobaLosowo(osobaId: String, od: LocalDate?, doDnia: LocalDate?, n: Int, tylkoZdjecia: Boolean = true): List<Asset> = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", n).put("withExif", true).put("personIds", JSONArray(listOf(osobaId)))
        if (tylkoZdjecia) body.put("type", "IMAGE")
        od?.let { body.put("takenAfter", "${it}T00:00:00.000Z") }
        doDnia?.let { body.put("takenBefore", "${it}T23:59:59.999Z") }
        val a = JSONArray(post("/search/random", body))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /** Pierwsza strona twarzy bez imienia (ekran „Kto to jest?”), w kolejności Immich (najczęstsze najpierw). */
    suspend fun osobyBezImienia(): List<Persoon> = wszystkieOsoby().filter { it.naam.isBlank() }

    /** Zdjęcia i filmy osoby zrobione przed jej datą urodzenia (zwykle błędne rozpoznanie twarzy), od najstarszych. */
    suspend fun przedUrodzeniem(osobaId: String, ur: LocalDate, pagina: Int, n: Int = 250): Pagina {
        val body = JSONObject().put("size", n).put("page", pagina).put("order", "asc").put("withExif", true)
            .put("personIds", JSONArray(listOf(osobaId))).put("takenBefore", "${ur}T00:00:00.000Z")
        return pagina(JSONObject(post("/search/metadata", body)))
    }

    /** Filmy z osi czasu (sekcja Filmy): od najnowszych albo najstarszych, opcjonalnie od wybranego miesiąca. */
    suspend fun filmy(pagina: Int, nieuwsteEerst: Boolean, odMiesiaca: java.time.YearMonth?): Pagina {
        val body = JSONObject().put("size", 250).put("page", pagina).put("order", if (nieuwsteEerst) "desc" else "asc")
            .put("visibility", "timeline").put("type", "VIDEO")
        odMiesiaca?.let {
            if (nieuwsteEerst) body.put("takenBefore", "${it.atEndOfMonth()}T23:59:59.999Z")
            else body.put("takenAfter", "${it.atDay(1)}T00:00:00.000Z")
        }
        return pagina(JSONObject(post("/search/metadata", body)))
    }

    // ---- Twarze w kadrze (pokaz: przybliżanie w stronę ludzi) ----

    private val srodki = object : LinkedHashMap<String, Pair<Float, Float>?>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Float, Float>?>?) = size > 300
    }

    /** Środek twarzy na zdjęciu (0..1 w poziomie i pionie, średnia z rozpoznanych twarzy); null = brak twarzy. */
    suspend fun srodekTwarzy(id: String): Pair<Float, Float>? {
        if (Synology.jestSyno(id)) return null
        synchronized(srodki) { if (srodki.containsKey(id)) return srodki[id] }
        val wynik = runCatching {
            withContext(Dispatchers.Default) {
                val ludzie = JSONObject(get("/assets/$id")).optJSONArray("people") ?: return@withContext null
                var sx = 0f; var sy = 0f; var n = 0
                for (i in 0 until ludzie.length()) {
                    val twarze = ludzie.getJSONObject(i).optJSONArray("faces") ?: continue
                    for (j in 0 until twarze.length()) {
                        val f = twarze.getJSONObject(j)
                        val w = f.optDouble("imageWidth"); val h = f.optDouble("imageHeight")
                        if (w.isNaN() || h.isNaN() || w <= 0 || h <= 0) continue
                        sx += ((f.optDouble("boundingBoxX1") + f.optDouble("boundingBoxX2")) / 2 / w).toFloat()
                        sy += ((f.optDouble("boundingBoxY1") + f.optDouble("boundingBoxY2")) / 2 / h).toFloat()
                        n++
                    }
                }
                if (n == 0) null else (sx / n).coerceIn(0f, 1f) to (sy / n).coerceIn(0f, 1f)
            }
        }.getOrNull()
        synchronized(srodki) { srodki[id] = wynik }
        return wynik
    }

    /** Losowe zdjęcia z osobami i miejscami (quiz rodzinny). */
    suspend fun losoweZOsobami(n: Int): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("type", "IMAGE").put("visibility", "timeline").put("withExif", true).put("withPeople", true)
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    // ---- Tryb imprezy: wgrywanie zdjęć od gości (uprawnienie asset.upload) ----

    /** Wgraj plik do Immich; zwraca id zasobu (także gdy taki plik już był). */
    suspend fun wgraj(plik: java.io.File, nazwa: String): String = withContext(Dispatchers.IO) {
        val teraz = java.time.Instant.now().toString()
        val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("assetData", nazwa, plik.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("deviceAssetId", "album-rodzinny-" + plik.name + "-" + plik.length())
            .addFormDataPart("deviceId", "album-rodzinny-tv")
            .addFormDataPart("fileCreatedAt", teraz).addFormDataPart("fileModifiedAt", teraz)
            .build()
        val r = haal(Request.Builder().url("$api/assets").post(body).build())
        JSONObject(r).getString("id")
    }

    /** Dodaj do albumu o tej nazwie (utwórz go, jeśli go nie ma); zwraca id albumu. */
    suspend fun doAlbumuONazwie(nazwa: String, ids: List<String>, znaneId: String?): String {
        if (znaneId != null) { dodajDoAlbumu(znaneId, ids); return znaneId }
        val istniejacy = albums().firstOrNull { it.naam == nazwa }
        return if (istniejacy != null) { dodajDoAlbumu(istniejacy.id, ids); istniejacy.id } else nowyAlbum(nazwa, ids)
    }

    /** Ramka twarzy osoby [osobaId] na zdjęciu [assetId] (ułamki 0..1: x1, y1, x2, y2); null = brak. */
    suspend fun twarzOsoby(assetId: String, osobaId: String): FloatArray? = runCatching {
        withContext(Dispatchers.Default) {
            val ludzie = JSONObject(get("/assets/$assetId")).optJSONArray("people") ?: return@withContext null
            for (i in 0 until ludzie.length()) {
                val p = ludzie.getJSONObject(i)
                if (p.optString("id") != osobaId) continue
                val twarze = p.optJSONArray("faces") ?: continue
                var najw: FloatArray? = null; var pole = 0.0
                for (j in 0 until twarze.length()) {
                    val f = twarze.getJSONObject(j)
                    val w = f.optDouble("imageWidth"); val h = f.optDouble("imageHeight")
                    if (w.isNaN() || h.isNaN() || w <= 0 || h <= 0) continue
                    val x1 = f.optDouble("boundingBoxX1") / w; val y1 = f.optDouble("boundingBoxY1") / h
                    val x2 = f.optDouble("boundingBoxX2") / w; val y2 = f.optDouble("boundingBoxY2") / h
                    val pp = (x2 - x1) * (y2 - y1)
                    if (pp > pole) { pole = pp; najw = floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) }
                }
                return@withContext najw
            }
            null
        }
    }.getOrNull() ?: twarzZFaces(assetId, osobaId)

    /** Zapasowo: GET /faces?id=<zdjęcie> (twarze z ramkami i osobą); null przy braku uprawnienia albo twarzy. */
    private suspend fun twarzZFaces(assetId: String, osobaId: String): FloatArray? = runCatching {
        withContext(Dispatchers.Default) {
            val a = JSONArray(get("/faces?id=$assetId"))
            var najw: FloatArray? = null; var pole = 0.0
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                if (f.optJSONObject("person")?.optString("id") != osobaId) continue
                val w = f.optDouble("imageWidth"); val h = f.optDouble("imageHeight")
                if (w.isNaN() || h.isNaN() || w <= 0 || h <= 0) continue
                val x1 = f.optDouble("boundingBoxX1") / w; val y1 = f.optDouble("boundingBoxY1") / h
                val x2 = f.optDouble("boundingBoxX2") / w; val y2 = f.optDouble("boundingBoxY2") / h
                val pp = (x2 - x1) * (y2 - y1)
                if (pp > pole) { pole = pp; najw = floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat()) }
            }
            najw
        }
    }.onFailure { Log.w("Fotos", "faces $assetId: ${it.message}") }.getOrNull()

    // ---- Porządki: kosz, archiwum, data, miejsce, opis, ocena, duplikaty, linki ----

    /** Do kosza (asset.delete); z kosza można przywrócić. */
    suspend fun doKosza(ids: List<String>) { delete("/assets", JSONObject().put("ids", JSONArray(ids)).put("force", false)) }
    suspend fun przywrocZKosza(ids: List<String>) { post("/trash/restore/assets", JSONObject().put("ids", JSONArray(ids))) }
    suspend fun oproznijKosz() { post("/trash/empty", JSONObject()) }

    /** Zdjęcia w koszu, strona po 200 (od ostatnio usuniętych). */
    suspend fun kosz(strona: Int): Pagina = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", 200).put("page", strona).put("withDeleted", true).put("trashedAfter", "1970-01-01T00:00:00.000Z").put("withExif", true)
        pagina(JSONObject(post("/search/metadata", body)))
    }

    /** Archiwum (ukryte z osi czasu), strona po 200. */
    suspend fun archiwum(strona: Int): Pagina = withContext(Dispatchers.Default) {
        pagina(JSONObject(post("/search/metadata", JSONObject().put("size", 200).put("page", strona).put("visibility", "archive").put("withExif", true))))
    }

    /** Archiwizuj (true) albo przywróć na oś czasu (false). */
    suspend fun archiwizuj(ids: List<String>, tak: Boolean) {
        put("/assets", JSONObject().put("ids", JSONArray(ids)).put("visibility", if (tak) "archive" else "timeline"))
    }

    /** Data i godzina wykonania (strefa telewizora). */
    suspend fun zmienDate(id: String, kiedy: java.time.LocalDateTime) {
        val z = kiedy.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString()
        put("/assets", JSONObject().put("ids", JSONArray(listOf(id))).put("dateTimeOriginal", z))
    }

    suspend fun zmienMiejsce(ids: List<String>, lat: Double, lon: Double) {
        put("/assets", JSONObject().put("ids", JSONArray(ids)).put("latitude", lat).put("longitude", lon))
    }

    suspend fun zmienOpis(id: String, opis: String) { put("/assets/$id", JSONObject().put("description", opis)) }

    /** Ocena 1–5 (0 = bez oceny). */
    suspend fun ocen(id: String, gwiazdki: Int) { put("/assets/$id", JSONObject().put("rating", if (gwiazdki == 0) JSONObject.NULL else gwiazdki)) }

    /** Zdjęcia z oceną co najmniej [min] (losowa kolejność nie jest potrzebna: od najnowszych). */
    suspend fun zOcena(min: Int, strona: Int): Pagina = withContext(Dispatchers.Default) {
        val wszystkie = ArrayList<Asset>()
        var dalej = false
        for (r in min..5) {
            val p = pagina(JSONObject(post("/search/metadata", JSONObject().put("size", 200).put("page", strona).put("rating", r).put("withExif", true))))
            wszystkie += p.assets; if (p.volgende != null) dalej = true
        }
        Pagina(wszystkie.sortedByDescending { it.datum }, if (dalej) strona + 1 else null)
    }

    /** Nowo wysłane od [od] (data dodania do Immich), strona po 200, od najstarszych. */
    suspend fun noweOd(od: java.time.Instant, strona: Int): Pagina = withContext(Dispatchers.Default) {
        pagina(JSONObject(post("/search/metadata", JSONObject().put("size", 200).put("page", strona).put("order", "asc")
            .put("createdAfter", od.toString()).put("visibility", "timeline").put("withExif", true))))
    }

    /** Grupy duplikatów wykrytych przez Immich: (id grupy, zdjęcia). */
    suspend fun duplikaty(): List<Pair<String, List<Asset>>> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/duplicates"))
        (0 until a.length()).map { i ->
            val g = a.getJSONObject(i)
            val l = g.getJSONArray("assets")
            g.optString("duplicateId") to List(l.length()) { asset(l.getJSONObject(it)) }
        }.filter { it.second.size > 1 }
    }

    /** „To nie duplikaty”: grupa znika z listy. */
    suspend fun odrzucDuplikat(id: String) { haal(Request.Builder().url("$api/duplicates/$id").delete().build()) }

    /** Link do albumu dla osób bez konta; zwraca pełny adres (domena zewnętrzna z ustawień serwera, jeśli jest). */
    suspend fun linkDoAlbumu(albumId: String, dni: Int?): String {
        val body = JSONObject().put("type", "ALBUM").put("albumId", albumId).put("allowDownload", true).put("showMetadata", true)
        dni?.let { body.put("expiresAt", java.time.Instant.now().plus(java.time.Duration.ofDays(it.toLong())).toString()) }
        val key = JSONObject(post("/shared-links", body)).getString("key")
        val zewn = runCatching { JSONObject(get("/server/config")).optString("externalDomain").trim().trimEnd('/') }.getOrNull().orEmpty()
        return (zewn.ifBlank { server }) + "/share/" + key
    }

    /** Konta Immich (do udostępniania albumu), bez własnego. */
    suspend fun uzytkownicy(): List<Pair<String, String>> = withContext(Dispatchers.Default) {
        val ja = runCatching { JSONObject(get("/users/me")).optString("id") }.getOrNull()
        val a = JSONArray(get("/users"))
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") != ja }
            .map { it.optString("id") to it.optString("name").ifBlank { it.optString("email") } }
    }

    suspend fun udostepnijAlbum(albumId: String, userId: String) {
        put("/albums/$albumId/users", JSONObject().put("albumUsers", JSONArray().put(JSONObject().put("userId", userId).put("role", "viewer"))))
    }

    // ---- Korekty twarzy (face.read / face.update / face.delete / person.create) ----

    /** Wszystkie twarze na zdjęciu z ramkami (ułamki) i przypisaną osobą (null = nieprzypisana). */
    suspend fun twarze(assetId: String): List<Twarz> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/faces?id=$assetId"))
        (0 until a.length()).mapNotNull { i ->
            val f = a.getJSONObject(i)
            val w = f.optDouble("imageWidth"); val h = f.optDouble("imageHeight")
            if (w.isNaN() || h.isNaN() || w <= 0 || h <= 0) return@mapNotNull null
            val o = f.optJSONObject("person")
            Twarz(
                f.optString("id"), assetId,
                (f.optDouble("boundingBoxX1") / w).toFloat(), (f.optDouble("boundingBoxY1") / h).toFloat(),
                (f.optDouble("boundingBoxX2") / w).toFloat(), (f.optDouble("boundingBoxY2") / h).toFloat(),
                o?.let { Persoon(it.optString("id"), it.optString("name"), it.optBoolean("isFavorite")) },
            )
        }
    }

    /** Przypisz twarz do osoby. */
    // Immich: PUT /faces/{id osoby} z treścią {"id": id twarzy} (odwrotnie, niż sugeruje adres) — wcześniej zamienione, stąd błąd 400
    suspend fun przypiszTwarz(twarzId: String, osobaId: String) { put("/faces/$osobaId", JSONObject().put("id", twarzId)) }

    /**
     * Nowa twarz dodana ręcznie (Immich jej nie wykrył): ramka na środku kadru, przypisana do osoby — zdjęcie pojawia się
     * u tej osoby w sekcji Ludzie (uprawnienie face.create). Współrzędne względem podanego rozmiaru obrazu.
     */
    suspend fun dodajTwarz(assetId: String, osobaId: String, ratio: Float) {
        val w = 1000; val h = (1000 / ratio.coerceIn(0.2f, 5f)).toInt().coerceAtLeast(1)
        post("/faces", JSONObject().put("assetId", assetId).put("personId", osobaId).put("imageWidth", w).put("imageHeight", h)
            .put("x", w / 4).put("y", h / 5).put("width", w / 2).put("height", h * 3 / 5))
    }

    /** Usuń twarz (wykryta omyłkowo: zabawka, plakat…). */
    suspend fun usunTwarz(twarzId: String) { delete("/faces/$twarzId", JSONObject().put("force", false)) }

    /** Nowa osoba (puste imię = bez imienia); zwraca id. */
    suspend fun nowaOsoba(imie: String): String =
        JSONObject(post("/people", JSONObject().apply { if (imie.isNotBlank()) put("name", imie.trim()) })).getString("id")

    /** Zdjęcia osoby, strona po [n] (od najstarszych), do przeglądu twarzy. */
    suspend fun osobaStrona(osobaId: String, strona: Int, n: Int): Pair<List<Asset>, Boolean> = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", n).put("page", strona).put("order", "asc").put("withExif", true)
            .put("personIds", JSONArray(listOf(osobaId))).put("type", "IMAGE")
        val o = JSONObject(post("/search/metadata", body)).getJSONObject("assets")
        val a = o.getJSONArray("items")
        List(a.length()) { asset(a.getJSONObject(it)) } to (!o.isNull("nextPage") && o.optString("nextPage").isNotBlank())
    }

    /** Losowe zdjęcia z jednego miasta (Powroty: rozkład na lata). */
    suspend fun losoweZMiasta(miasto: String, n: Int): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("type", "IMAGE").put("visibility", "timeline").put("withExif", true).put("city", miasto)
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /** Podpowiedzi wyszukiwania z serwera (type = country / city …): nazwy dokładnie tak, jak zapisał je Immich. */
    suspend fun sugestie(typ: String): List<String> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/search/suggestions?type=$typ"))
        List(a.length()) { a.optString(it) }.filter { it.isNotBlank() && it != "null" }
    }

    // ---- Rok w skrócie ----

    /** Losowa próbka zdjęć i filmów z roku [rok] (z osobami i miejscami), do statystyk i pokazu. */
    suspend fun probkaRoku(rok: Int, n: Int, ulubione: Boolean = false, osobaId: String? = null): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("visibility", "timeline").put("withExif", true).put("withPeople", true)
            .put("takenAfter", "$rok-01-01T00:00:00.000Z").put("takenBefore", "$rok-12-31T23:59:59.999Z")
        if (ulubione) q.put("isFavorite", true)
        osobaId?.let { q.put("personIds", JSONArray(listOf(it))) }
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    // ---- Edycja: osoby i albumy (uprawnienia person.update / person.merge / album.create / album.update / albumAsset.*) ----

    /** Jedna osoba z datą urodzenia i informacją, czy jest ukryta. */
    suspend fun osoba(id: String): Pair<Persoon, Boolean> = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/people/$id"))
        val ur = o.optString("birthDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() }
        Persoon(o.getString("id"), o.optString("name").takeIf { it != "null" }.orEmpty(), o.optBoolean("isFavorite"), ur) to o.optBoolean("isHidden")
    }

    /** Zdjęcie główne osoby = jej twarz z tego zdjęcia (uprawnienie person.update). */
    suspend fun ustawZdjecieOsoby(osobaId: String, assetId: String) { put("/people/$osobaId", JSONObject().put("featureFaceAssetId", assetId)) }

    suspend fun zmienImie(id: String, imie: String) { put("/people/$id", JSONObject().put("name", imie.trim())) }

    /** Data urodzenia osoby; null = usuń datę. */
    suspend fun zmienUrodziny(id: String, data: LocalDate?) {
        put("/people/$id", JSONObject().put("birthDate", data?.toString() ?: JSONObject.NULL))
    }

    suspend fun ukryjOsobe(id: String, ukryj: Boolean) { put("/people/$id", JSONObject().put("isHidden", ukryj)) }

    /** Twarze osób [ids] przechodzą do osoby [cel]; osoby [ids] przestają istnieć. */
    suspend fun polaczOsoby(cel: String, ids: List<String>) { post("/people/$cel/merge", JSONObject().put("ids", JSONArray(ids))) }

    /** Nowy album z podanymi zdjęciami; zwraca jego id. */
    suspend fun nowyAlbum(nazwa: String, ids: List<String>): String =
        JSONObject(post("/albums", JSONObject().put("albumName", nazwa.trim()).put("assetIds", JSONArray(ids)))).getString("id")

    /** Dodaj do albumu; zwraca liczbę faktycznie dodanych (pozostałe już w nim były). */
    suspend fun dodajDoAlbumu(albumId: String, ids: List<String>): Int {
        val a = JSONArray(put("/albums/$albumId/assets", JSONObject().put("ids", JSONArray(ids))))
        return (0 until a.length()).count { a.getJSONObject(it).optBoolean("success") }
    }

    /** Usuń album (uprawnienie album.delete); zdjęcia zostają w bibliotece. */
    suspend fun usunAlbum(albumId: String) { haal(Request.Builder().url("$api/albums/$albumId").delete().build()) }

    /** Okładka albumu (uprawnienie album.update). */
    suspend fun ustawOkladke(albumId: String, assetId: String) { patch("/albums/$albumId", JSONObject().put("albumThumbnailAssetId", assetId)) }

    suspend fun usunZAlbumu(albumId: String, ids: List<String>) { delete("/albums/$albumId/assets", JSONObject().put("ids", JSONArray(ids))) }

    suspend fun zmienNazweAlbumu(albumId: String, nazwa: String) { patch("/albums/$albumId", JSONObject().put("albumName", nazwa.trim())) }

    /** Ulubione z pilota (wymaga uprawnienia asset.update; bez niego Immich odpowiada 403). */
    suspend fun ustawUlubione(id: String, ulubione: Boolean) { put("/assets/$id", JSONObject().put("isFavorite", ulubione)) }

    /** Osoby z datą urodzenia (urodziny na osi czasu). */
    suspend fun osobyZUrodzinami(): List<Persoon> = withContext(Dispatchers.Default) {
        val a = JSONObject(get("/people?size=1000&withHidden=false")).getJSONArray("people")
        List(a.length()) { i -> a.getJSONObject(i) }.mapNotNull { o ->
            val ur = o.optString("birthDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            Persoon(o.getString("id"), o.optString("name"), o.optBoolean("isFavorite"), ur).takeIf { it.naam.isNotBlank() }
        }
    }

    /** Najnowsze zdjęcia i filmy jednego albumu (kanał albumu na ekranie głównym). */
    suspend fun zAlbumu(albumId: String, n: Int): List<Asset> = withContext(Dispatchers.Default) {
        val a = JSONObject(get("/albums/$albumId")).optJSONArray("assets") ?: return@withContext emptyList()
        List(a.length()) { asset(a.getJSONObject(it)) }.sortedByDescending { it.datum }.take(n)
    }

    /** Jedno zdjęcie po id (kafelek kanału na ekranie głównym). */
    suspend fun zasob(id: String): Asset = withContext(Dispatchers.Default) { asset(JSONObject(get("/assets/$id"))) }

    /** Najnowsze zdjęcia i filmy z osi czasu (kanał „Najnowsze zdjęcia”). */
    suspend fun najnowsze(n: Int): List<Asset> = withContext(Dispatchers.Default) {
        val body = JSONObject().put("size", n).put("page", 1).put("order", "desc").put("visibility", "timeline").put("withExif", true)
        val a = JSONObject(post("/search/metadata", body)).getJSONObject("assets").getJSONArray("items")
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    /** Details voor het infopaneel in de viewer. */
    suspend fun details(id: String): Details = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/assets/$id"))
        val e = o.optJSONObject("exifInfo")
        fun s(k: String) = e?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" }
        val mensen = o.optJSONArray("people")
        Details(
            bestand = o.optString("originalFileName"),
            sciezka = o.optString("originalPath").takeIf { it.isNotBlank() && it != "null" },
            zewnetrzna = !o.isNull("libraryId") && o.optString("libraryId").isNotBlank(),
            camera = listOfNotNull(s("make"), s("model")).joinToString(" ").ifEmpty { null },
            lens = s("lensModel"),
            // kolejność jak w Immichin: przysłona · czas · ISO · ogniskowa
            instellingen = listOfNotNull(
                e?.optDouble("fNumber")?.takeIf { !it.isNaN() && it > 0 }?.let { "ƒ/" + ("%.1f".format(java.util.Locale.ROOT, it)).removeSuffix(".0") },
                s("exposureTime")?.let { "$it s" },
                e?.optInt("iso")?.takeIf { it > 0 }?.let { "ISO $it" },
                e?.optDouble("focalLength")?.takeIf { !it.isNaN() && it > 0 }?.let { "${it.toInt()} mm" },
            ).joinToString("  ·  ").ifEmpty { null },
            plaats = listOfNotNull(s("city"), s("state"), s("country")?.let(Landnaam::vertaal)).distinct().joinToString(", ").ifEmpty { null },
            mensen = if (mensen == null) emptyList() else
                List(mensen.length()) { mensen.getJSONObject(it).let { p -> Persoon(p.optString("id"), p.optString("name"), p.optBoolean("isFavorite")) } }
                    .filter { it.naam.isNotBlank() && it.id.isNotBlank() }.distinctBy { it.id },
            omschrijving = s("description"),
            afmeting = wymiary(o, e)?.let { (w, h) -> "$w × $h" },
            plikSub = run {
                val wh = wymiary(o, e)
                val mp = wh?.let { (w, h) -> w.toLong() * h / 1_000_000.0 }?.takeIf { it >= 0.05 }
                    ?.let { "%.1f MP".format(Taal.locale, it) }
                val bajty = e?.optLong("fileSizeInByte")?.takeIf { it > 0 }?.let(::rozmiarPliku)
                listOfNotNull(mp, wh?.let { (w, h) -> "$w × $h" }, bajty).joinToString("  ·  ").ifEmpty { null }
            },
            wspolrzedne = run {
                val la = e?.optDouble("latitude")?.takeIf { !it.isNaN() }
                val lo = e?.optDouble("longitude")?.takeIf { !it.isNaN() }
                if (la != null && lo != null) "%.4f, %.4f".format(java.util.Locale.ROOT, la, lo) else null
            },
            strefa = s("timeZone"),
            ulubione = o.optBoolean("isFavorite"),
            wideo = o.optString("type") == "VIDEO",
            dlugosc = duurKort(o.optString("duration").takeIf { it.isNotEmpty() && it != "null" }),
        )
    }

    /** Szerokość × wysokość: z zasobu, a jeśli brak, z EXIF (z uwzględnieniem obrotu). */
    private fun wymiary(o: JSONObject, e: JSONObject?): Pair<Int, Int>? {
        var w = o.optInt("width"); var h = o.optInt("height")
        if (w <= 0 || h <= 0) {
            w = e?.optInt("exifImageWidth") ?: 0; h = e?.optInt("exifImageHeight") ?: 0
            if (e?.optString("orientation") in setOf("5", "6", "7", "8")) w = h.also { h = w }
        }
        return if (w > 0 && h > 0) w to h else null
    }

    /** 375.1 KB / 4.4 MB / 1.2 GB */
    private fun rozmiarPliku(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(Taal.locale, b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> "%.1f MB".format(Taal.locale, b / (1024.0 * 1024))
        else -> "%.1f KB".format(Taal.locale, b / 1024.0)
    }

    private fun asset(o: JSONObject): Asset {
        var w = o.optInt("width")
        var h = o.optInt("height")
        val e = o.optJSONObject("exifInfo")
        if (w <= 0 || h <= 0) {
            w = e?.optInt("exifImageWidth") ?: 0
            h = e?.optInt("exifImageHeight") ?: 0
            if (e?.optString("orientation") in setOf("5", "6", "7", "8")) w = h.also { h = w }
        }
        return Asset(
            id = o.getString("id"),
            ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f,
            video = o.optString("type") == "VIDEO",
            thumbhash = o.optString("thumbhash").takeIf { it.isNotEmpty() && it != "null" },
            // localDateTime is de lokale tijd, ook al staat er een Z achter
            datum = lokaleTijd(o.optString("localDateTime").ifEmpty { o.optString("fileCreatedAt") }, 0.0),
            duur = o.optString("duration").takeIf { it.isNotEmpty() && it != "null" },
            stad = e?.optString("city")?.takeIf { it.isNotEmpty() && it != "null" },
            land = e?.optString("country")?.takeIf { it.isNotEmpty() && it != "null" }?.let(Landnaam::vertaal),
            favoriet = o.optBoolean("isFavorite"),
            lat = e?.optDouble("latitude")?.takeIf { !it.isNaN() },
            lon = e?.optDouble("longitude")?.takeIf { !it.isNaN() },
            live = o.optString("livePhotoVideoId").takeIf { it.isNotEmpty() && it != "null" },
            osoby = o.optJSONArray("people")?.let { p ->
                List(p.length()) { p.getJSONObject(it).let { x -> Persoon(x.optString("id"), x.optString("name"), x.optBoolean("isFavorite")) } }
                    .filter { it.id.isNotBlank() }
            }.orEmpty(),
        )
    }
}

/**
 * Zet de sleutel via adb (alleen de shell mag dit, zie de DUMP-permissie in het manifest):
 * `am broadcast -a com.immichclient.tv.SET_KEY -n com.immichclient.tv/.SleutelOntvanger --es sleutel … --es server http://…:2283 [--es taal nl]`.
 * Zonder `server` blijft het opgeslagen adres staan; `taal` (en/nl/de/fr/es) slaat het taalkeuzescherm van de eerste start over.
 */
class SleutelOntvanger : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = i.getStringExtra("sleutel") ?: return
        i.getStringExtra("taal")?.let { Taal.kies(c, it) }
        Immich.zetSleutel(c, s, i.getStringExtra("kijker"), i.getStringExtra("server"))
        Immich.kiesAccount(c, null)
        Log.i("Fotos", "sleutel gezet" + (i.getStringExtra("kijker")?.let { " voor $it" } ?: "") + " voor ${Immich.server}")
    }
}
