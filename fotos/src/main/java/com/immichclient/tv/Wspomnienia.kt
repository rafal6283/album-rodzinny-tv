package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Rocznice albumów: albumy zaczęte w tych dniach (±3 dni) w poprzednich latach — „3 lata temu: Bułgaria”. */
object Rocznice {
    class Wpis(val album: Album, val lat: Int)

    var lista by mutableStateOf<List<Wpis>>(emptyList())
        private set
    private var pobrano: LocalDate? = null

    suspend fun laduj() {
        val dzis = LocalDate.now()
        if (pobrano == dzis) return
        runCatching { Immich.albums() }.onSuccess { albumy ->
            lista = albumy.mapNotNull { a ->
                val od = a.van ?: return@mapNotNull null
                if (od.year >= dzis.year || a.aantal == 0) return@mapNotNull null
                val wTymRoku = runCatching { od.withYear(dzis.year) }.getOrElse { LocalDate.of(dzis.year, 2, 28) }
                if (abs(ChronoUnit.DAYS.between(dzis, wTymRoku)) > 3) null else Wpis(a, dzis.year - od.year)
            }.sortedBy { it.lat }
            pobrano = dzis
        }.onFailure { Log.w("Fotos", "rocznice", it) }
    }
}

/** Wiersz rocznic na osi czasu (pod urodzinami); OK otwiera album. */
@Composable
fun RoczniceRij(nav: Nav) {
    LaunchedEffect(Unit) { Rocznice.laduj() }
    val lista = Rocznice.lista
    if (lista.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(150.dp)) {
            Text(stringResource(R.string.rocznice), style = MaterialTheme.typography.displaySmall, maxLines = 2)
            Text(stringResource(R.string.rocznice_sub), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 4.dp))
        }
        LazyRow(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(lista, key = { it.album.id }) { w ->
                val ileLat = pluralStringResource(R.plurals.lat_temu, w.lat, w.lat)
                Card(
                    onClick = { nav.open(Scherm.Tijdlijn(w.album.naam, ileLat, Filter(albumId = w.album.id), !w.album.oudsteEerst, false, nav.scope)) },
                    modifier = Modifier.width(250.dp).height(140.dp).langeOk { Podreczne.album(w.album.id, w.album.naam) },
                    shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.06f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        w.album.omslag?.let { AsyncImage(beeld(Immich.thumb(it)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD0000000)))))
                        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                            Text(ileLat, style = MaterialTheme.typography.labelLarge, color = Color(0xFFF5B83D))
                            Text(w.album.naam, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Urodziny dziś: przy pierwszym uruchomieniu aplikacji w tym dniu automatyczny pokaz (z muzyką, jeśli włączona)
 * zdjęć solenizanta — z okolic jego urodzin w poprzednich latach i losowe z całego życia.
 */
object UrodzinyStart {
    @Volatile var pokazano = false

    /** tytuł pokazu i zdjęcia; null = nikt nie ma dziś urodzin albo pokaz już był dzisiaj */
    suspend fun dzis(c: android.content.Context): Pair<String, List<Asset>>? {
        val dzis = LocalDate.now()
        val prefs = c.getSharedPreferences("urodziny_start", android.content.Context.MODE_PRIVATE)
        if (prefs.getString("dzien", "") == dzis.toString()) return null
        val osoby = runCatching { Immich.osobyZUrodzinami() }.getOrDefault(emptyList()).filter { p ->
            !Instellingen.osobaUkryta(p.id) && p.urodziny?.let { it.monthValue == dzis.monthValue && it.dayOfMonth == dzis.dayOfMonth } == true
        }
        if (osoby.isEmpty()) return null
        val zdjecia = osoby.flatMap { o ->
            val ur = o.urodziny!!
            val zUrodzin = (1..minOf(30, dzis.year - ur.year)).flatMap { lat ->
                val d = dzis.minusYears(lat.toLong())
                runCatching { Immich.osobaWDniach(o.id, d.minusDays(3), d.plusDays(3), 40) }.getOrDefault(emptyList())
                    .filter { !it.video }.let { Pokazane.wybierz(it, 5) { a -> a.id } }
            }
            val losowe = runCatching { Immich.osobaLosowo(o.id, null, null, 30) }.getOrDefault(emptyList())
            (zUrodzin + Pokazane.wybierz(losowe, 15) { it.id }).distinctBy { it.id }
        }.sortedBy { it.datum }.take(150)
        if (zdjecia.isEmpty()) return null
        prefs.edit().putString("dzien", dzis.toString()).apply()
        Pokazane.zapamietaj(zdjecia.map { it.id })
        val imiona = osoby.joinToString(", ") { it.naam }
        return "🎂 " + c.getString(R.string.urodziny_osoby, imiona) to zdjecia
    }
}

/** „Tego dnia” jako pokaz na start (Ustawienia): raz na uruchomienie aplikacji. */
object TegoDniaStart {
    @Volatile var pokazano = false

    /** Wspomnienia z dzisiaj; z każdego roku do 25 zdjęć, losowo (najpierw jeszcze niepokazane), lata po kolei. */
    suspend fun zasoby(): List<Asset> = runCatching {
        Immich.herinneringen(LocalDate.now()).sortedBy { it.jaar }.flatMap { h ->
            Pokazane.wybierz(h.assets, 25) { it.id }.sortedBy { it.datum }
        }.also { l -> Pokazane.zapamietaj(l.map { it.id }) }
    }
        .onFailure { Log.w("Fotos", "tego dnia", it) }.getOrDefault(emptyList())
}

/**
 * „Kiedyś i dziś”: zdjęcie osoby sprzed N lat (z okolic dzisiejszej daty, ±45 dni; jeśli brak — z całego tamtego roku)
 * obok najnowszego. ◀ ▶ zmienia N.
 */
@Composable
fun KiedysIDzis(osobaId: String, imie: String, sluit: () -> Unit) {
    Lagen.Open()
    BackHandler { sluit() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { fr.requestFocus() } }
    var lat by remember { mutableIntStateOf(1) }
    val dzis = LocalDate.now()
    // „dziś”: jedno z najnowszych zdjęć (ostatnie ~3 miesiące od najnowszego), losowo
    val teraz by produceState<Asset?>(null, osobaId) {
        value = runCatching {
            val naj = Immich.osobaWOkresie(osobaId, null, null, 1).firstOrNull() ?: return@runCatching null
            val od = naj.datum.toLocalDate().minusMonths(3)
            Immich.osobaLosowo(osobaId, od, naj.datum.toLocalDate(), 15).let { Pokazane.wybierz(it, 1) { x -> x.id }.firstOrNull() } ?: naj
        }.getOrNull()
    }
    val wtedy by produceState<Pair<Boolean, Asset?>>(false to null, osobaId, lat) {
        value = false to null
        val srodek = dzis.minusYears(lat.toLong())
        val a = runCatching {
            // losowo (nie zawsze to samo zdjęcie), najpierw jeszcze niepokazane
            (Immich.osobaLosowo(osobaId, srodek.minusDays(45), srodek.plusDays(45), 20).takeIf { it.isNotEmpty() }
                ?: Immich.osobaLosowo(osobaId, LocalDate.of(srodek.year, 1, 1), LocalDate.of(srodek.year, 12, 31), 20))
                .let { Pokazane.wybierz(it, 1) { x -> x.id }.firstOrNull() }?.also { Pokazane.zapamietaj(listOf(it.id)) }
        }.getOrNull()
        value = true to a
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF7090910)).focusVangen().focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (lat < 30) lat++; true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (lat > 1) lat--; true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { sluit(); true }
                    else -> false
                }
            }
            .padding(horizontal = 48.dp, vertical = 30.dp),
    ) {
        Column {
            Text(stringResource(R.string.porownanie_etyk).uppercase(Taal.locale), style = EtykietaStyl)
            Text(imie, style = TytulStyl, color = Color.White, maxLines = 1)
            Text(stringResource(R.string.porownanie_hint), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(bottom = 14.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                val (gotowe, stare) = wtedy
                PolowaPorownania(stare, pluralStringResource(R.plurals.lat_temu, lat, lat), if (!gotowe) stringResource(R.string.laden) else stringResource(R.string.porownanie_brak), Modifier.weight(1f))
                PolowaPorownania(teraz, stringResource(R.string.porownanie_teraz), stringResource(R.string.laden), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PolowaPorownania(a: Asset?, etykieta: String, brak: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(etykieta, style = MaterialTheme.typography.headlineSmall, color = Color(0xFFF5B83D))
        Text(a?.let { it.datum.toLocalDate().format(Formaat.dagJaar) } ?: " ", style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(Kleur.vlak), contentAlignment = Alignment.Center) {
            if (a != null) AsyncImage(preview(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(brak, style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Kalendarz we Wspomnieniach: urodziny wszystkich osób z datą urodzenia (najbliższy rok) i rocznice albumów
 * (najbliższe 30 dni), posortowane po dacie.
 */
object Kalendarz {
    class Wpis(val data: LocalDate, val zaIle: Int, val lat: Int, val osoba: Persoon?, val album: Album?)

    var lista by mutableStateOf<List<Wpis>>(emptyList())
        private set
    /** urodziny i rocznice z ostatnich 60 dni (zaIle < 0 = tyle dni temu), od najświeższych */
    var minione by mutableStateOf<List<Wpis>>(emptyList())
        private set
    private var pobrano: LocalDate? = null

    fun uniewaznij() { pobrano = null }

    /** najbliższe urodziny osoby (null = brak daty urodzenia w Immich) */
    fun urodzinyOsoby(id: String): Wpis? = lista.firstOrNull { it.osoba?.id == id } ?: wszystkieUrodziny[id]
    /** wszystkie osoby z datą (lista powyżej jest przycięta do 40 pozycji) */
    private var wszystkieUrodziny: Map<String, Wpis> = emptyMap()

    private fun najblizsza(d: LocalDate, dzis: LocalDate): LocalDate {
        fun wRoku(r: Int) = runCatching { d.withYear(r) }.getOrElse { LocalDate.of(r, 2, 28) }
        val t = wRoku(dzis.year)
        return if (t.isBefore(dzis)) wRoku(dzis.year + 1) else t
    }

    suspend fun laduj() {
        val dzis = LocalDate.now()
        if (pobrano == dzis) return
        val osoby = runCatching { Immich.osobyZUrodzinami() }.onFailure { Log.w("Fotos", "kalendarz osoby", it) }.getOrDefault(emptyList())
        val albumy = runCatching { Immich.albums() }.onFailure { Log.w("Fotos", "kalendarz albumy", it) }.getOrDefault(emptyList())
        val urodziny = osoby.mapNotNull { p ->
            val ur = p.urodziny ?: return@mapNotNull null
            val n = najblizsza(ur, dzis)
            Wpis(n, ChronoUnit.DAYS.between(dzis, n).toInt(), n.year - ur.year, p, null)
        }
        val rocznice = albumy.mapNotNull { a ->
            val od = a.van ?: return@mapNotNull null
            if (od.year >= dzis.year || a.aantal == 0) return@mapNotNull null
            val n = najblizsza(od, dzis)
            val za = ChronoUnit.DAYS.between(dzis, n).toInt()
            if (za > 30) null else Wpis(n, za, n.year - od.year, null, a)
        }
        // ostatnia rocznica przed dzisiaj (≤ 60 dni temu)
        fun ostatnia(d: LocalDate): LocalDate {
            fun wRoku(r: Int) = runCatching { d.withYear(r) }.getOrElse { LocalDate.of(r, 2, 28) }
            val t = wRoku(dzis.year)
            return if (t.isBefore(dzis)) t else wRoku(dzis.year - 1)
        }
        val mUrodziny = osoby.mapNotNull { p ->
            val ur = p.urodziny ?: return@mapNotNull null
            val o = ostatnia(ur); val temu = ChronoUnit.DAYS.between(o, dzis).toInt()
            if (temu > 60 || o.year <= ur.year) null else Wpis(o, -temu, o.year - ur.year, p, null)
        }
        val mRocznice = albumy.mapNotNull { a ->
            val od = a.van ?: return@mapNotNull null
            if (a.aantal == 0) return@mapNotNull null
            val o = ostatnia(od); val temu = ChronoUnit.DAYS.between(o, dzis).toInt()
            if (temu > 60 || o.year <= od.year) null else Wpis(o, -temu, o.year - od.year, null, a)
        }
        minione = (mUrodziny + mRocznice).sortedWith(compareBy({ -it.zaIle }, { it.osoba == null })).take(40)
        wszystkieUrodziny = urodziny.associateBy { it.osoba!!.id }
        fun dozwolone(w: Wpis) = w.osoba?.let { !Instellingen.osobaUkryta(it.id) } ?: w.album?.let { !Instellingen.albumUkryty(it.id) } ?: true
        minione = minione.filter(::dozwolone)
        lista = (urodziny + rocznice).filter(::dozwolone).sortedWith(compareBy({ it.zaIle }, { it.osoba == null })).take(40)
        pobrano = dzis
    }
}

/** „🎂 12 gru · za 19 dni · 8 lat” — urodziny osoby pod jej twarzą (Ludzie) i w nagłówku jej osi czasu. */
@Composable
fun opisUrodzin(w: Kalendarz.Wpis, krotko: Boolean): String {
    val fmt = remember(krotko) { java.time.format.DateTimeFormatter.ofPattern(if (krotko) "d MMM" else "d MMMM", Taal.locale) }
    val kiedy = when (w.zaIle) { 0 -> stringResource(R.string.kalendarz_dzis); 1 -> stringResource(R.string.kalendarz_jutro); else -> stringResource(R.string.kalendarz_za, w.zaIle) }
    return "🎂 " + w.data.format(fmt) + " · " + kiedy + " · " + pluralStringResource(R.plurals.lat, w.lat, w.lat)
}

/** Wiersz „Nadchodzące” we Wspomnieniach; OK otwiera zdjęcia osoby albo album. */
@Composable
fun KalendarzRij(nav: Nav, minione: Boolean = false) {
    LaunchedEffect(Unit) { Kalendarz.laduj() }
    val lista = if (minione) Kalendarz.minione else Kalendarz.lista
    if (lista.isEmpty()) return
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("d MMMM", Taal.locale) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(if (minione) R.string.kalendarz_minione else R.string.kalendarz), style = MaterialTheme.typography.displaySmall)
            Text("   " + stringResource(if (minione) R.string.kalendarz_minione_sub else R.string.kalendarz_sub), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(bottom = 6.dp))
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        ) {
            items(lista, key = { (it.osoba?.id ?: it.album?.id).orEmpty() + it.data }) { w ->
                val kiedy = when {
                    w.zaIle == 0 -> stringResource(R.string.kalendarz_dzis); w.zaIle == 1 -> stringResource(R.string.kalendarz_jutro)
                    w.zaIle == -1 -> stringResource(R.string.kalendarz_wczoraj); w.zaIle < 0 -> stringResource(R.string.kalendarz_temu, -w.zaIle)
                    else -> stringResource(R.string.kalendarz_za, w.zaIle)
                }
                val opis = if (w.osoba != null) pluralStringResource(R.plurals.lat, w.lat, w.lat) else pluralStringResource(R.plurals.lat_temu, w.lat, w.lat)
                val nazwa = w.osoba?.let { Edycja.imie(it) } ?: w.album?.naam.orEmpty()
                Card(
                    onClick = {
                        w.osoba?.let { otworzUrodziny(nav, it, nazwa) }
                        w.album?.let { nav.open(Scherm.Tijdlijn(it.naam, opis, Filter(albumId = it.id), !it.oudsteEerst, false, nav.scope)) }
                    },
                    modifier = Modifier.pamiecFokusu("rocznice", (w.osoba?.id ?: w.album?.id).orEmpty() + w.data, pierwszy = !minione && w === lista.first())
                        .langeOk { w.osoba?.let { Podreczne.osoba(it.id, nazwa) }; w.album?.let { Podreczne.album(it.id, it.naam) } }
                        .width(260.dp).height(120.dp),
                    shape = CardDefaults.shape(RoundedCornerShape(18.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.06f),
                ) {
                    Row(
                        Modifier.fillMaxSize().background(Brush.horizontalGradient(
                            if (w.osoba != null) listOf(Color(0xFF5B2A4A), Color(0xFF2A2340)) else listOf(Color(0xFF2C3E58), Color(0xFF1E2238)),
                        )).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (w.osoba != null) Gezicht(w.osoba, 62.dp)
                        else w.album?.omslag?.let {
                            AsyncImage(beeld(Immich.thumb(it)), null, Modifier.width(62.dp).height(62.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(w.data.format(fmt) + "  ·  " + kiedy, style = MaterialTheme.typography.labelMedium, color = Color(0xFFF5B83D), maxLines = 1)
                            Text((if (w.osoba != null) "🎂 " else "") + nazwa, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                            Text(opis, style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Zdjęcia osoby z okolic jej urodzin (±3 dni) z każdego roku, od najnowszych: tort, goście, prezenty przez lata.
 * Rok po roku od roku urodzenia (najwyżej 40 lat wstecz); zapytania idą po kolei, pierwsze lata widać od razu.
 */
fun otworzUrodziny(nav: Nav, osoba: Persoon, nazwa: String) {
    val ur = osoba.urodziny ?: return
    val teraz = LocalDate.now()
    val tytul = Taal.s(R.string.urodziny_osoby, nazwa)
    val sub = Taal.s(R.string.urodziny_osoby_sub, ur.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", Taal.locale)))
    fun wRoku(r: Int) = runCatching { ur.withYear(r) }.getOrElse { LocalDate.of(r, 2, 28) }
    val ostatni = if (wRoku(teraz.year).isAfter(teraz)) teraz.year - 1 else teraz.year
    val najstarszy = maxOf(ur.year, ostatni - 40)
    var nastepny = ostatni
    nav.open(Scherm.Lijst(tytul, sub, true, nav.scope, losowyPokaz = true) { strona ->
        // kolejne lata wstecz, aż uzbiera się choć kilkanaście zdjęć (w niektórych latach nie ma żadnych)
        if (strona == 1) nastepny = ostatni
        val zdjecia = ArrayList<Asset>()
        while (nastepny >= najstarszy && zdjecia.size < 15) {
            val d = wRoku(nastepny)
            // ±3 dni wokół urodzin; gdy w tym roku prawie nic — szersze okno ±7 dni
            var zRoku = runCatching { Immich.osobaWDniach(osoba.id, d.minusDays(3), d.plusDays(3)) }.getOrDefault(emptyList())
            if (zRoku.size < 3) zRoku = runCatching { Immich.osobaWDniach(osoba.id, d.minusDays(7), d.plusDays(7)) }.getOrDefault(zRoku)
            // dużo zdjęć z jednego roku: losowa porcja (najpierw jeszcze niepokazane), żeby za każdym razem było coś innego
            zdjecia += if (zRoku.size > 24) Pokazane.wybierz(zRoku, 24) { it.id }.also { w -> Pokazane.zapamietaj(w.map { it.id }) } else zRoku
            nastepny--
        }
        Pagina(zdjecia.sortedByDescending { it.datum }, if (nastepny >= najstarszy) strona + 1 else null)
    })
}

/** Sekcja Rocznice: nadchodzące i niedawne urodziny oraz rocznice albumów. */
@Composable
fun RoczniceScherm(nav: Nav) {
    LaunchedEffect(Unit) { Kalendarz.laduj() }
    PamiecEkranu("rocznice")
    val pusto = Kalendarz.lista.isEmpty() && Kalendarz.minione.isEmpty()
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { SchermKop(stringResource(R.string.sectie_rocznice), stringResource(R.string.rocznice_sekcja_sub), etykieta = stringResource(R.string.etyk_wspomnienia)) }
        item(key = "nadchodzace") { KalendarzRij(nav) }
        item(key = "minione") { KalendarzRij(nav, minione = true) }
        if (pusto) item { Melding(stringResource(R.string.rocznice_brak), stringResource(R.string.rocznice_brak_tip)) }
    }
}
