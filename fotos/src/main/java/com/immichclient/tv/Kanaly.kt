package com.immichclient.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File

/**
 * Kanały na ekranie głównym Android TV (jak w Immichin): „Najnowsze zdjęcia”, „Losowe zdjęcia” i „Tego dnia”
 * (wspomnienia). Każdy kanał to wiersz z ~20 kafelkami; OK na kafelku otwiera aplikację w przeglądarce na tym zdjęciu
 * (◀ ▶ przegląda resztę kanału).
 *
 * Miniatury: launcher pobiera obrazki sam, bez klucza API Immich. Dlatego plakat to `content://…kanaly/<id>` z
 * [KanalyObrazy], który pobiera podgląd z kluczem i zwraca plik z pamięci podręcznej. Dostawca wydaje tylko zdjęcia,
 * które są aktualnie w kanałach (lista w prefs), nic innego.
 *
 * Odświeżanie: przy starcie aplikacji i co ~3 godziny przez JobScheduler (też po restarcie urządzenia).
 */
object Kanaly {
    private const val PREFS = "kanaly"
    private const val TAG = "Kanaly"
    const val AUTORYTET = "com.immichclient.tv.kanaly"
    const val EXTRA_KANAL = "kanal"
    const val EXTRA_ZASOB = "zasob"
    private const val JOB_ID = 4711

    enum class Rodzaj(val klucz: String, val nazwa: Int) {
        NAJNOWSZE("najnowsze", R.string.kanal_najnowsze),
        LOSOWE("losowe", R.string.kanal_losowe),
        WSPOMNIENIA("wspomnienia", R.string.kanal_wspomnienia),
        /** jedno zdjęcie na dzień (najchętniej z ulubionych), zmienia się o północy */
        DZIEN("dzien", R.string.kanal_dzien),
        /** wybrany album (przycisk „Na ekran główny” w albumie); nazwa kanału = nazwa albumu */
        ALBUM("album", R.string.kanal_album),
    }

    private fun nazwa(r: Rodzaj) = if (r == Rodzaj.ALBUM) Instellingen.kanalAlbumNazwa.ifBlank { Taal.ctx.getString(r.nazwa) } else Taal.ctx.getString(r.nazwa)

    /** Kanały działają od Androida 8 (API 26), czyli zawsze w tej aplikacji; launcher musi je jednak obsługiwać. */
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Id zdjęć w kanale (dla przeglądarki i dla dostawcy obrazków). */
    fun idy(c: Context, r: Rodzaj): List<String> = prefs(c).getString("idy_${r.klucz}", "").orEmpty().split(',').filter { it.isNotBlank() }
    fun dozwolone(c: Context): Set<String> = Rodzaj.entries.flatMap { idy(c, it) }.toSet()

    /** Odśwież w tle (przy starcie aplikacji); zaplanuj też okresowe odświeżanie. */
    fun odswiezWTle(c: Context) {
        val app = c.applicationContext
        zaplanuj(app)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { odswiez(app) }.onFailure { Log.w(TAG, "odświeżanie", it) } }
    }

    fun zaplanuj(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(c, KanalyJob::class.java))
            .setPeriodic(3 * 60 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { js.schedule(job) }.onFailure { Log.w(TAG, "job", it) }
    }

    /** jedno odświeżanie naraz (start aplikacji i job mogły się nałożyć i tworzyć kanały podwójnie) */
    private val blokada = kotlinx.coroutines.sync.Mutex()

    /** Wszystkie kanały na nowo: kanały tworzone przy pierwszym razie, programy wymieniane za każdym razem. */
    suspend fun odswiez(c: Context) = blokada.withLock { odswiezTeraz(c) }

    private suspend fun odswiezTeraz(c: Context) {
        if (Immich.sleutel.isBlank()) return
        if (!Instellingen.kanaly) { usunWszystkie(c); return }
        val zestawy = mapOf(
            Rodzaj.NAJNOWSZE to runCatching { Immich.najnowsze(20) }.getOrElse { Log.w(TAG, "najnowsze", it); null },
            Rodzaj.LOSOWE to runCatching { Immich.willekeurig(20) }.getOrElse { Log.w(TAG, "losowe", it); null },
            Rodzaj.WSPOMNIENIA to runCatching {
                // dzisiejsze urodziny na początku kanału „Tego dnia”: kafelek z ostatnim zdjęciem osoby z okolic urodzin
                urodzinyDzis().map { it.first } + Immich.herinneringen(java.time.LocalDate.now()).sortedBy { it.jaar }.flatMap { it.assets }.take(30)
            }.getOrElse { Log.w(TAG, "wspomnienia", it); null },
            Rodzaj.DZIEN to runCatching { zdjecieDnia(c) }.getOrElse { Log.w(TAG, "zdjęcie dnia", it); null },
        )
        // kanał albumu: tylko gdy album jest wybrany; inaczej usuwamy go z ekranu głównego
        val album = Instellingen.kanalAlbum
        val zAlbumu = if (album.isBlank()) { usunKanal(c, Rodzaj.ALBUM); null }
            else runCatching { Immich.zAlbumu(album, 30) }.getOrElse { Log.w(TAG, "album", it); null }
        var pierwszy = true
        for ((r, lista) in zestawy) {
            if (lista == null) continue // błąd sieci: zostaw stary stan kanału
            val kanal = kanal(c, r, domyslny = pierwszy)
            pierwszy = false
            if (kanal < 0) continue
            ustawProgramy(c, r, kanal, lista)
        }
        if (zAlbumu != null) {
            val k = kanal(c, Rodzaj.ALBUM, domyslny = false)
            if (k >= 0) {
                // nazwa mogła się zmienić (inny album)
                runCatching { c.contentResolver.update(TvContractCompat.buildChannelUri(k), Channel.Builder().setDisplayName(nazwa(Rodzaj.ALBUM)).build().toContentValues(), null, null) }
                ustawProgramy(c, Rodzaj.ALBUM, k, zAlbumu)
                runCatching { TvContractCompat.requestChannelBrowsable(c, k) }
            }
        }
        Log.i(TAG, "kanały odświeżone")
    }

    /** Zdjęcie dnia: wybierane raz dziennie (ulubione, a gdy ich brak — losowe; najpierw jeszcze niepokazane). */
    private suspend fun zdjecieDnia(c: Context): List<Asset> {
        val p = prefs(c)
        val dzis = java.time.LocalDate.now().toString()
        val zapisane = p.getString("dzien_id", null)
        val a = if (p.getString("dzien_data", "") == dzis && zapisane != null) runCatching { Immich.asset(zapisane) }.getOrNull() else null
        val wybrane = a ?: run {
            val kandydaci = runCatching { Immich.losowe(40, ulubione = true) }.getOrDefault(emptyList()).filter { !it.video }
                .ifEmpty { Immich.willekeurig(40).filter { !it.video } }
            Pokazane.wybierz(kandydaci, 1) { it.id }.firstOrNull()?.also {
                p.edit().putString("dzien_data", dzis).putString("dzien_id", it.id).apply()
                Pokazane.zapamietaj(listOf(it.id))
            }
        } ?: return emptyList()
        tytulyUrodzin[wybrane.id] = c.getString(R.string.kanal_dzien) + (listOfNotNull(wybrane.stad, wybrane.datum.year.toString()).joinToString(", ").let { " · $it" })
        return listOf(wybrane)
    }

    /** tytuły kafelków urodzinowych („🎂 Dziś urodziny: Ola · 8 lat”) wg id zdjęcia */
    private val tytulyUrodzin = HashMap<String, String>()

    /** Osoby z urodzinami dziś: zdjęcie (z okolic urodzin w poprzednich latach albo najnowsze) + tytuł kafelka. */
    private suspend fun urodzinyDzis(): List<Pair<Asset, String>> {
        tytulyUrodzin.clear()
        val dzis = java.time.LocalDate.now()
        val osoby = runCatching { Immich.osobyZUrodzinami() }.getOrDefault(emptyList())
            .filter { p -> p.urodziny?.let { it.monthValue == dzis.monthValue && it.dayOfMonth == dzis.dayOfMonth } == true }
        return osoby.mapNotNull { p ->
            val ur = p.urodziny ?: return@mapNotNull null
            val zdjecie = (1..5).firstNotNullOfOrNull { lat ->
                val d = dzis.minusYears(lat.toLong())
                runCatching { Immich.osobaWDniach(p.id, d.minusDays(3), d.plusDays(3), 20) }.getOrDefault(emptyList()).firstOrNull { !it.video }
            } ?: runCatching { Immich.osobaWOkresie(p.id, null, null, 1).firstOrNull() }.getOrNull() ?: return@mapNotNull null
            val lata = dzis.year - ur.year
            val tytul = Taal.ctx.getString(R.string.kanal_urodziny, p.naam) + if (lata > 0) " · " + Taal.ctx.resources.getQuantityString(R.plurals.lat, lata, lata) else ""
            tytulyUrodzin[zdjecie.id] = tytul
            zdjecie to tytul
        }
    }

    /** Id kanału w systemie; tworzy go, jeśli go nie ma (albo użytkownik go usunął). */
    private fun kanal(c: Context, r: Rodzaj, domyslny: Boolean): Long {
        val p = prefs(c)
        // własne kanały aplikacji wg internalProviderId (pewniejsze niż samo id w prefs); duplikaty usuwamy
        val pasujace = wlasneKanaly(c).filter { it.internalProviderId == r.klucz }
        if (pasujace.isNotEmpty()) {
            pasujace.drop(1).forEach { d -> runCatching { c.contentResolver.delete(TvContractCompat.buildChannelUri(d.id), null, null) } }
            p.edit().putLong("kanal_${r.klucz}", pasujace[0].id).apply()
            return pasujace[0].id
        }
        val zapisany = p.getLong("kanal_${r.klucz}", -1L)
        if (zapisany >= 0 && istnieje(c, zapisany)) return zapisany
        val nazwa = nazwa(r)
        val wejscie = Intent(c, MainActivity::class.java).setData(Uri.parse("immichtv://kanal/${r.klucz}"))
            .putExtra(EXTRA_KANAL, r.klucz).toUri(Intent.URI_INTENT_SCHEME)
        val ch = Channel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(nazwa)
            .setAppLinkIntentUri(Uri.parse(wejscie))
            .setInternalProviderId(r.klucz)
            .build()
        val uri = runCatching { c.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, ch.toContentValues()) }
            .onFailure { Log.w(TAG, "brak obsługi kanałów w launcherze", it) }.getOrNull() ?: return -1
        val id = ContentUris.parseId(uri)
        runCatching { ChannelLogoUtils.storeChannelLogo(c, id, android.graphics.BitmapFactory.decodeResource(c.resources, R.mipmap.ic_album_rodzinny)) }
        // pierwszy kanał aplikacji launcher pokazuje sam; pozostałe użytkownik włącza (Ustawienia → Ekran główny)
        if (domyslny) runCatching { TvContractCompat.requestChannelBrowsable(c, id) }
        p.edit().putLong("kanal_${r.klucz}", id).apply()
        return id
    }

    private fun wlasneKanaly(c: Context): List<Channel> = runCatching {
        c.contentResolver.query(TvContractCompat.Channels.CONTENT_URI, null, null, null, null)?.use { cur ->
            buildList { while (cur.moveToNext()) add(Channel.fromCursor(cur)) }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private fun istnieje(c: Context, id: Long): Boolean = runCatching {
        c.contentResolver.query(TvContractCompat.buildChannelUri(id), null, null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun ustawProgramy(c: Context, r: Rodzaj, kanal: Long, lista: List<Asset>) {
        val cr = c.contentResolver
        // stare programy kanału usunąć
        runCatching { cr.delete(TvContractCompat.buildPreviewProgramsUriForChannel(kanal), null, null) }
        val nazwa = nazwa(r)
        lista.forEachIndexed { i, a ->
            // unikalne dane na kafelek: inaczej Android tylko przywraca działającą aplikację, bez nowej intencji
            val wejscie = Intent(c, MainActivity::class.java)
                .setData(Uri.parse("immichtv://kanal/${r.klucz}/${a.id}"))
                .putExtra(EXTRA_KANAL, r.klucz).putExtra(EXTRA_ZASOB, a.id)
                .toUri(Intent.URI_INTENT_SCHEME)
            val tytul = tytulyUrodzin[a.id] ?: listOfNotNull(a.stad, a.land).joinToString(", ").ifEmpty { nazwa }
            val program = PreviewProgram.Builder()
                .setChannelId(kanal)
                .setType(if (a.video) TvContractCompat.PreviewPrograms.TYPE_CLIP else TvContractCompat.PreviewPrograms.TYPE_EVENT)
                .setTitle(tytul)
                .setDescription(a.datum.format(Formaat.dagJaar))
                .setPosterArtUri(Uri.parse("content://$AUTORYTET/${a.id}"))
                .setPosterArtAspectRatio(if (a.ratio < 1f) TvContractCompat.PreviewPrograms.ASPECT_RATIO_3_2 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse(wejscie))
                .setInternalProviderId(a.id)
                .setWeight(lista.size - i)
                .build()
            runCatching { cr.insert(TvContractCompat.PreviewPrograms.CONTENT_URI, program.toContentValues()) }
                .onFailure { Log.w(TAG, "program", it) }
        }
        prefs(c).edit().putString("idy_${r.klucz}", lista.joinToString(",") { it.id }).apply()
    }

    private fun usunKanal(c: Context, r: Rodzaj) {
        val p = prefs(c)
        wlasneKanaly(c).filter { it.internalProviderId == r.klucz }.forEach { ch ->
            runCatching { c.contentResolver.delete(TvContractCompat.buildChannelUri(ch.id), null, null) }
        }
        p.edit().remove("kanal_${r.klucz}").remove("idy_${r.klucz}").apply()
    }

    private fun usunWszystkie(c: Context) {
        val p = prefs(c)
        for (r in Rodzaj.entries) {
            val id = p.getLong("kanal_${r.klucz}", -1L)
            if (id >= 0) runCatching { c.contentResolver.delete(TvContractCompat.buildChannelUri(id), null, null) }
            p.edit().remove("kanal_${r.klucz}").remove("idy_${r.klucz}").apply()
        }
    }

    /** Intencja „pokaż ten kanał na ekranie głównym” (systemowe okienko z pytaniem), dla kanałów, które jeszcze nie są widoczne. */
    fun prosbyOWidocznosc(c: Context): List<Intent> = Rodzaj.entries.mapNotNull { r ->
        val id = prefs(c).getLong("kanal_${r.klucz}", -1L).takeIf { it >= 0 } ?: return@mapNotNull null
        Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).putExtra(TvContractCompat.EXTRA_CHANNEL_ID, id)
    }

    /** Zdjęcia kanału (dla przeglądarki po kliknięciu kafelka); wczytywane z serwera po id. */
    suspend fun zasoby(c: Context, klucz: String): Pair<String, List<Asset>> {
        val r = Rodzaj.entries.firstOrNull { it.klucz == klucz } ?: Rodzaj.NAJNOWSZE
        val lista = kotlinx.coroutines.coroutineScope {
            idy(c, r).map { id -> async(Dispatchers.IO) { runCatching { Immich.zasob(id) }.getOrNull() } }.mapNotNull { it.await() }
        }
        return Taal.ctx.getString(r.nazwa) to lista
    }

    /** Podgląd zdjęcia do pliku w cache (dla dostawcy obrazków); null = błąd. */
    fun plakat(c: Context, id: String): File? {
        val dir = File(c.cacheDir, "kanaly").apply { mkdirs() }
        val f = File(dir, "$id.jpg")
        if (f.length() > 0) return f
        // launcher prosi o wiele kafelków naraz; przerwane pobranie ponawiamy (unikalny plik tymczasowy na próbę)
        repeat(3) { proba ->
            val wynik = runCatching {
                Immich.http.newCall(Request.Builder().url(Immich.preview(id)).build()).execute().use { odp ->
                    if (!odp.isSuccessful) return null
                    val tmp = File.createTempFile(id, ".tmp", dir)
                    tmp.outputStream().use { out -> odp.body!!.byteStream().copyTo(out) }
                    if (!tmp.renameTo(f)) tmp.delete()
                    // porządek: zostaw tylko pliki zdjęć, które są w kanałach
                    val ok = dozwolone(c)
                    dir.listFiles()?.forEach { x -> if (x.nameWithoutExtension !in ok && x.name.endsWith(".jpg")) x.delete() }
                    f
                }
            }.onFailure { Log.w(TAG, "plakat $id (próba ${proba + 1}): ${it.message}") }.getOrNull()
            if (wynik != null && wynik.length() > 0) return wynik
            Thread.sleep(300L * (proba + 1))
        }
        return null
    }
}

/** Okresowe odświeżanie kanałów (JobScheduler, co ~3 h). */
class KanalyJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Kanaly.odswiez(applicationContext) }.onFailure { Log.w("Kanaly", "job", it) }
            jobFinished(p, false)
        }
        return true
    }
    override fun onStopJob(p: JobParameters) = true
}

/**
 * Obrazki kafelków dla launchera: `content://com.immichclient.tv.kanaly/<id zdjęcia>`. Tylko do odczytu i tylko dla
 * zdjęć, które są aktualnie w kanałach — inne id dają „nie znaleziono”.
 */
class KanalyObrazy : ContentProvider() {
    override fun onCreate() = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        if (mode != "r") throw SecurityException("tylko odczyt")
        val id = uri.lastPathSegment ?: throw java.io.FileNotFoundException()
        if (id !in Kanaly.dozwolone(c)) throw java.io.FileNotFoundException(id)
        val f = Kanaly.plakat(c, id) ?: throw java.io.FileNotFoundException(id)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
