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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * Zmiany zrobione z telewizora (osoby i albumy), widoczne od razu na ekranach, które mają już wczytane dane: imiona,
 * ukryte i połączone osoby, nazwy albumów. [wersjaOsob] / [wersjaAlbumow] rosną przy każdej zmianie (klucze remember).
 */
object Edycja {
    val imiona = mutableStateMapOf<String, String>()
    val ukryte = mutableStateMapOf<String, Boolean>()
    /** osoby połączone z inną (już nie istnieją) */
    val polaczone = mutableStateMapOf<String, Boolean>()
    val nazwyAlbumow = mutableStateMapOf<String, String>()
    var wersjaOsob by mutableIntStateOf(0)
    var wersjaAlbumow by mutableIntStateOf(0)
    /** rośnie po zmianie zdjęcia głównego osoby (nowe adresy miniatur twarzy) */
    var wersjaGlownych by mutableIntStateOf(0)

    /** Ustaw zdjęcie główne osoby (jej twarz z tego zdjęcia) i odśwież miniatury. */
    fun ustawGlowne(osobaId: String, assetId: String, poSukcesie: () -> Unit = {}) {
        zakres.launch {
            runCatching { Immich.ustawZdjecieOsoby(osobaId, assetId) }
                .onSuccess { wersjaGlownych++; wersjaOsob++; komunikat = Taal.s(R.string.glowne_ustawione); poSukcesie() }
                .onFailure { komunikat = blad(it) }
        }
    }
    /** albumy, z których usunięto zdjęcie w przeglądarce: oś czasu albumu wczytuje się od nowa po jej zamknięciu */
    val doOdswiezenia = HashSet<String>()
    /**
     * Zakres dla zapisów na serwerze (album, osoba): niezależny od okna, które je zleciło — okno zamyka się od razu,
     * a zapis trwa dalej (wcześniej zamknięcie okna przerywało zapis: „The coroutine scope left the composition”).
     */
    val zakres = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    /** krótki komunikat na dole ekranu (pokazuje MainActivity, także nad przeglądarką) */
    var komunikat by mutableStateOf<String?>(null)

    fun imie(p: Persoon) = imiona[p.id] ?: p.naam
    fun widoczna(p: Persoon) = ukryte[p.id] != true && polaczone[p.id] != true && !Instellingen.osobaUkryta(p.id)

    /** Komunikat błędu: brak uprawnienia (stary klucz) albo ogólny. */
    fun blad(e: Throwable): String {
        Log.w("Fotos", "edycja: ${e.message}")
        return if (e.message.orEmpty().startsWith("Immich 403")) Taal.s(R.string.edycja_brak_prawa) else Taal.s(R.string.edycja_blad, e.message.orEmpty().take(80))
    }
}

/** Pełnoekranowa warstwa z tytułem i przyciskami jeden pod drugim (menu osoby, potwierdzenia). */
@Composable
fun MenuWarstwa(tytul: String, podtytul: String?, sluit: () -> Unit, tresc: @Composable (pierwszy: Modifier) -> Unit) {
    Lagen.Open()
    val fr = remember { FocusRequester() }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(40); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().padding(horizontal = 64.dp, vertical = 40.dp)) {
        // przewijane: długie menu (np. podręczne z edycją) nie ucieka poza ekran
        Column(Modifier.widthIn(max = 620.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tytul, style = MaterialTheme.typography.displaySmall)
            podtytul?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht) }
            Box(Modifier.height(8.dp))
            tresc(Modifier.focusRequester(fr))
        }
    }
}

/**
 * Edycja osoby z jej osi czasu: imię, data urodzenia, ukrycie i połączenie z inną osobą. [tytul] dostaje nowe imię,
 * [poPolaczeniu] zamyka oś czasu (osoba przestała istnieć).
 */
@Composable
fun OsobaMenu(id: String, imie: String, ustawTytul: (String) -> Unit, poPolaczeniu: () -> Unit, komunikat: (String) -> Unit, sluit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dane by produceState<Pair<Persoon, Boolean>?>(null, id) { value = runCatching { Immich.osoba(id) }.getOrNull() }
    var urodziny by remember { mutableStateOf<LocalDate?>(null) }
    var ukryta by remember { mutableStateOf(false) }
    LaunchedEffect(dane) { dane?.let { urodziny = it.first.urodziny; ukryta = Edycja.ukryte[id] ?: it.second } }
    var okno by remember { mutableStateOf<String?>(null) }
    val bez = stringResource(R.string.persoon_bez_imienia)
    val nazwa = Edycja.imiona[id] ?: imie

    if (okno == null) MenuWarstwa(nazwa.ifBlank { bez }, stringResource(R.string.osoba_edycja_sub), sluit) { pierwszy ->
        Knop(stringResource(R.string.osoba_imie), nazwa.ifBlank { stringResource(R.string.osoba_imie_brak) }, pierwszy.width(560.dp)) { okno = "imie" }
        Knop(stringResource(R.string.osoba_urodziny), urodziny?.format(Formaat.dagJaar) ?: stringResource(R.string.osoba_urodziny_brak), Modifier.width(560.dp)) { okno = "data" }
        Knop(stringResource(if (ukryta) R.string.osoba_pokaz else R.string.osoba_ukryj), stringResource(if (ukryta) R.string.osoba_pokaz_sub else R.string.osoba_ukryj_sub), Modifier.width(560.dp)) {
            val nowe = !ukryta
            Edycja.zakres.launch {
                runCatching { Immich.ukryjOsobe(id, nowe) }
                    .onSuccess { ukryta = nowe; Edycja.ukryte[id] = nowe; Edycja.wersjaOsob++; komunikat(Taal.s(if (nowe) R.string.osoba_ukryta else R.string.osoba_widoczna)) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
        Knop(stringResource(R.string.osoba_polacz), stringResource(R.string.osoba_polacz_sub), Modifier.width(560.dp)) { okno = "polacz" }
    }
    when (okno) {
        "imie" -> TekstInvoer(stringResource(R.string.osoba_imie), nazwa, actie = stringResource(R.string.zapisz), wielkie = true, sluit = { okno = null }) { nowe ->
            okno = null
            if (nowe.isNotBlank() && nowe.trim() != nazwa) Edycja.zakres.launch {
                runCatching { Immich.zmienImie(id, nowe) }
                    .onSuccess { Edycja.imiona[id] = nowe.trim(); Edycja.wersjaOsob++; ustawTytul(nowe.trim()); Urodziny.uniewaznij(); Kalendarz.uniewaznij(); Kalendarz.laduj(); komunikat(Taal.s(R.string.zapisano)) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
        "data" -> DataUrodzin(nazwa.ifBlank { bez }, urodziny, sluit = { okno = null }) { nowa ->
            okno = null
            Edycja.zakres.launch {
                runCatching { Immich.zmienUrodziny(id, nowa) }
                    .onSuccess { urodziny = nowa; Urodziny.uniewaznij(); Kalendarz.uniewaznij(); Kalendarz.laduj(); komunikat(Taal.s(R.string.zapisano)) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
        "polacz" -> {
            val osoby by produceState<List<KiesItem>?>(null) {
                value = runCatching {
                    Immich.wszystkieOsoby().filter { it.id != id && Edycja.widoczna(it) }
                        .map { KiesItem(it.id, Edycja.imie(it).ifBlank { bez }, null, Immich.gezicht(it.id), rond = true) }
                }.getOrDefault(emptyList())
            }
            var cel by remember { mutableStateOf<Pair<String, String>?>(null) }
            val c = cel
            if (c == null) KiesLijst(stringResource(R.string.osoba_polacz_z, nazwa.ifBlank { bez }), osoby, "", sluit = { okno = null }) { cid, cnaam -> cel = cid to cnaam }
            else MenuWarstwa(stringResource(R.string.osoba_polacz_pytanie, nazwa.ifBlank { bez }, c.second), stringResource(R.string.osoba_polacz_uwaga), sluit = { cel = null }) { pierwszy ->
                Knop(stringResource(R.string.anuluj), null, pierwszy.width(420.dp)) { cel = null }
                Knop(stringResource(R.string.osoba_polacz_tak), null, Modifier.width(420.dp)) {
                    Edycja.zakres.launch {
                        runCatching { Immich.polaczOsoby(c.first, listOf(id)) }
                            .onSuccess {
                                Edycja.polaczone[id] = true; Edycja.wersjaOsob++
                                komunikat(Taal.s(R.string.osoba_polaczona, c.second))
                                okno = null; sluit(); poPolaczeniu()
                            }
                            .onFailure { cel = null; komunikat(Edycja.blad(it)) }
                    }
                }
            }
        }
    }
}

/** Wybór daty urodzenia pilotem: ◀ ▶ pole (dzień / miesiąc / rok), ▲ ▼ wartość; OK zapisuje. */
@Composable
fun DataUrodzin(kto: String, poczatek: LocalDate?, sluit: () -> Unit, zapisz: (LocalDate?) -> Unit) {
    Lagen.Open()
    val start = poczatek ?: LocalDate.of(1990, 1, 1)
    var d by remember { mutableIntStateOf(start.dayOfMonth) }
    var m by remember { mutableIntStateOf(start.monthValue) }
    var r by remember { mutableIntStateOf(start.year) }
    val dzis = LocalDate.now()
    fun dni() = YearMonth.of(r, m).lengthOfMonth()
    val pola = remember { List(3) { FocusRequester() } }
    BackHandler { sluit() }
    LaunchedEffect(Unit) { delay(40); runCatching { pola[0].requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().padding(horizontal = 64.dp, vertical = 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.osoba_urodziny), style = MaterialTheme.typography.displaySmall)
            Text(kto + "  ·  " + stringResource(R.string.data_hint), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            Row(Modifier.padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val miesiace = remember { java.time.format.DateTimeFormatter.ofPattern("LLLL", Taal.locale) }
                PoleDaty(d.toString(), 110, pola[0], { d = if (d >= dni()) 1 else d + 1 }, { d = if (d <= 1) dni() else d - 1 }) { zapisz(LocalDate.of(r, m, d.coerceAtMost(dni()))) }
                PoleDaty(LocalDate.of(2000, m, 1).format(miesiace).replaceFirstChar { it.titlecase(Taal.locale) }, 240, pola[1], { m = if (m >= 12) 1 else m + 1; d = d.coerceAtMost(dni()) }, { m = if (m <= 1) 12 else m - 1; d = d.coerceAtMost(dni()) }) {
                    zapisz(LocalDate.of(r, m, d.coerceAtMost(dni())))
                }
                PoleDaty(r.toString(), 140, pola[2], { if (r < dzis.year) r++; d = d.coerceAtMost(dni()) }, { if (r > 1900) r--; d = d.coerceAtMost(dni()) }) {
                    zapisz(LocalDate.of(r, m, d.coerceAtMost(dni())))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Knop(stringResource(R.string.zapisz), null, Modifier.width(200.dp)) { zapisz(LocalDate.of(r, m, d.coerceAtMost(dni()))) }
                if (poczatek != null) Knop(stringResource(R.string.osoba_urodziny_usun), null, Modifier.width(240.dp)) { zapisz(null) }
                Knop(stringResource(R.string.anuluj), null, Modifier.width(180.dp)) { sluit() }
            }
        }
    }
}

@Composable
private fun PoleDaty(tekst: String, szer: Int, fr: FocusRequester, wGore: () -> Unit, wDol: () -> Unit, ok: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("▲", style = MaterialTheme.typography.titleMedium, color = if (focus) Color.White else Color.Transparent)
        Box(
            Modifier.width(szer.dp).height(72.dp).focusRequester(fr).onFocusChanged { focus = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> { wGore(); true }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { wDol(); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { ok(); true }
                        else -> false
                    }
                }
                .focusable()
                .background(if (focus) Kleur.primair else Kleur.vlak, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(tekst, style = MaterialTheme.typography.headlineMedium, color = if (focus) Kleur.opPrimair else Kleur.tekst, textAlign = TextAlign.Center, maxLines = 1)
        }
        Text("▼", style = MaterialTheme.typography.titleMedium, color = if (focus) Color.White else Color.Transparent)
    }
}

/**
 * Dodawanie do albumu z przeglądarki: lista albumów (ostatnio zmieniane na górze) z „Nowy album…” na początku.
 * [ids] = to zdjęcie albo zaznaczone (koszyk z ▼ → Zaznacz). Wynik jako krótki komunikat.
 */
@Composable
fun AlbumWybor(ids: List<String>, komunikat: (String) -> Unit, sluit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val nowy = stringResource(R.string.album_nowy)
    val albumy by produceState<List<KiesItem>?>(null, Edycja.wersjaAlbumow) {
        value = runCatching {
            listOf(KiesItem(NOWY, "＋  $nowy", null, null)) +
                Immich.albums().sortedByDescending { it.bijgewerkt }.map { a ->
                    KiesItem(a.id, Edycja.nazwyAlbumow[a.id] ?: a.naam, Taal.s(R.string.album_ile, Taal.getal(a.aantal)), a.omslag?.let { Immich.thumb(it) })
                }
        }.getOrElse { listOf(KiesItem(NOWY, "＋  $nowy", null, null)) }
    }
    var nazwaOkno by remember { mutableStateOf(false) }
    val tytul = if (ids.size == 1) stringResource(R.string.album_dodaj_jedno) else stringResource(R.string.album_dodaj_wiele, Taal.getal(ids.size))
    if (!nazwaOkno) KiesLijst(tytul, albumy, "", sluit = sluit) { id, naam ->
        if (id == NOWY) nazwaOkno = true
        else {
            sluit()
            Edycja.zakres.launch {
                runCatching { Immich.dodajDoAlbumu(id, ids) }
                    .onSuccess { n -> Edycja.wersjaAlbumow++; komunikat(if (n == 0) Taal.s(R.string.album_juz_jest, naam) else Taal.s(R.string.album_dodano, Taal.getal(n), naam)) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
    }
    else TekstInvoer(stringResource(R.string.album_nazwa), "", actie = stringResource(R.string.album_utworz), wielkie = true, sluit = { nazwaOkno = false }) { nazwa ->
        if (nazwa.isBlank()) return@TekstInvoer
        sluit()
        Edycja.zakres.launch {
            runCatching { Immich.nowyAlbum(nazwa, ids) }
                .onSuccess { Edycja.wersjaAlbumow++; komunikat(Taal.s(R.string.album_utworzono, nazwa.trim())) }
                .onFailure { komunikat(Edycja.blad(it)) }
        }
    }
}

private const val NOWY = "__nowy_album__"

/** Krótki komunikat edycji (np. „Zapisano”, „Dodano 3 do albumu …”), znika po kilku sekundach. */
@Composable
fun EdycjaKomunikat(modifier: Modifier) {
    val k = Edycja.komunikat
    LaunchedEffect(k) { if (k != null) { delay(4000); Edycja.komunikat = null } }
    androidx.compose.animation.AnimatedVisibility(k != null, modifier.padding(bottom = 60.dp), enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
        Text(k.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White,
            modifier = Modifier.background(Color(0xF0181822), RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

/**
 * „Kto to jest?”: twarze bez imienia jedna po drugiej (najczęstsze najpierw). Dla każdej: przypisz do osoby z listy
 * (połączenie), wpisz nowe imię, ukryj albo pomiń. Szybkie porządkowanie z kanapy.
 */
@Composable
fun KtoToJest(sluit: () -> Unit) {
    Lagen.Open()
    val scope = rememberCoroutineScope()
    BackHandler { sluit() }
    val bez by produceState<List<Persoon>?>(null) { value = runCatching { Immich.osobyBezImienia() }.getOrDefault(emptyList()) }
    val znane by produceState<List<Persoon>>(emptyList()) { value = runCatching { Immich.wszystkieOsoby().filter { it.naam.isNotBlank() } }.getOrDefault(emptyList()) }
    var i by remember { mutableIntStateOf(0) }
    var okno by remember { mutableStateOf<String?>(null) }
    val pierwszy = remember { FocusRequester() }
    val lista = bez?.filter { Edycja.widoczna(it) && Edycja.imiona[it.id].isNullOrBlank() }
    val p = lista?.getOrNull(i)
    LaunchedEffect(p?.id, okno) { if (okno == null) { delay(60); runCatching { pierwszy.requestFocus() } } }
    // przypisana / nazwana / ukryta twarz znika z listy, więc ten sam indeks pokazuje następną; „Pomiń” = indeks + 1
    fun zrobione(msg: String) { Edycja.wersjaOsob++; Edycja.komunikat = msg }

    Box(Modifier.fillMaxSize().background(Color(0xF7090910)).focusVangen().padding(horizontal = 56.dp, vertical = 32.dp)) {
        Column {
            Text(stringResource(R.string.kto_etyk).uppercase(Taal.locale), style = EtykietaStyl)
            Text(stringResource(R.string.kto_tytul), style = TytulStyl, color = Color.White)
            Text(
                when {
                    lista == null -> stringResource(R.string.laden)
                    p == null -> stringResource(R.string.kto_koniec)
                    else -> stringResource(R.string.kto_sub, Taal.getal(i + 1), Taal.getal(lista.size))
                },
                style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(bottom = 16.dp),
            )
            if (p == null) {
                if (lista != null) Knop(stringResource(R.string.rechten_sluiten), null, Modifier.width(220.dp).focusRequester(pierwszy)) { sluit() }
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    coil3.compose.AsyncImage(
                        beeld(Immich.gezicht(p.id)), null,
                        Modifier.size(200.dp).background(Kleur.vlak, androidx.compose.foundation.shape.CircleShape)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    // kilka zdjęć z tą osobą, żeby łatwiej rozpoznać
                    val zdjecia by produceState<List<Asset>>(emptyList(), p.id) { value = runCatching { Immich.osobaWOkresie(p.id, null, null, 3) }.getOrDefault(emptyList()) }
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        zdjecia.forEach { a ->
                            coil3.compose.AsyncImage(beeld(Immich.thumb(a.id)), null, Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Knop(stringResource(R.string.kto_to_jest), stringResource(R.string.kto_to_jest_sub), Modifier.width(420.dp).focusRequester(pierwszy)) { okno = "wybierz" }
                    Knop(stringResource(R.string.kto_nowe), stringResource(R.string.kto_nowe_sub), Modifier.width(420.dp)) { okno = "imie" }
                    Knop(stringResource(R.string.osoba_ukryj), stringResource(R.string.osoba_ukryj_sub), Modifier.width(420.dp)) {
                        Edycja.zakres.launch {
                            runCatching { Immich.ukryjOsobe(p.id, true) }
                                .onSuccess { Edycja.ukryte[p.id] = true; zrobione(Taal.s(R.string.osoba_ukryta)) }
                                .onFailure { Edycja.komunikat = Edycja.blad(it) }
                        }
                    }
                    Knop(stringResource(R.string.kto_pomin), null, Modifier.width(420.dp)) { i++ }
                }
            }
        }
    }
    val obecna = p ?: return
    when (okno) {
        "imie" -> TekstInvoer(stringResource(R.string.osoba_imie), "", actie = stringResource(R.string.zapisz), wielkie = true, sluit = { okno = null }) { nowe ->
            okno = null
            if (nowe.isNotBlank()) Edycja.zakres.launch {
                runCatching { Immich.zmienImie(obecna.id, nowe) }
                    .onSuccess { Edycja.imiona[obecna.id] = nowe.trim(); zrobione(Taal.s(R.string.zapisano)) }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
        "wybierz" -> KiesLijst(
            stringResource(R.string.kto_to_jest),
            znane.filter { Edycja.widoczna(it) }.map { KiesItem(it.id, Edycja.imie(it), null, Immich.gezicht(it.id), rond = true) },
            "", sluit = { okno = null },
        ) { cel, celNaam ->
            okno = null
            Edycja.zakres.launch {
                runCatching { Immich.polaczOsoby(cel, listOf(obecna.id)) }
                    .onSuccess { Edycja.polaczone[obecna.id] = true; zrobione(Taal.s(R.string.osoba_polaczona, celNaam)) }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
}
