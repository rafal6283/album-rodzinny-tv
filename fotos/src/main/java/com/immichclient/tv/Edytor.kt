package com.immichclient.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Edycja opisu zdjęć z telewizora: data i godzina, miejsce, opis, ocena, archiwum, kosz, „nie we wspomnieniach”,
 * oraz link do albumu z kodem QR i udostępnianie albumu kontom. Okna otwiera menu podręczne (przytrzymanie OK).
 */
object Edytor {
    var data by mutableStateOf<List<Asset>?>(null)
    var miejsce by mutableStateOf<List<String>?>(null)
    var opis by mutableStateOf<Asset?>(null)
    var ocena by mutableStateOf<List<String>?>(null)
    var link by mutableStateOf<Pair<String, String>?>(null)
    var udostepnij by mutableStateOf<Pair<String, String>?>(null)

    private fun s(id: Int, vararg a: Any) = Taal.s(id, *a)

    fun doKosza(ids: List<String>) = Edycja.zakres.launch {
        runCatching { Immich.doKosza(ids) }
            .onSuccess { ids.forEach { id -> Udostepnianie.koszyk.firstOrNull { it.id == id }?.let { Udostepnianie.przelacz(it) } }; Edycja.komunikat = s(R.string.edytor_do_kosza, ids.size) }
            .onFailure { Edycja.komunikat = Edycja.blad(it) }
    }

    fun archiwizuj(ids: List<String>) = Edycja.zakres.launch {
        runCatching { Immich.archiwizuj(ids, true) }
            .onSuccess { Edycja.komunikat = s(R.string.edytor_zarchiwizowano, ids.size) }
            .onFailure { Edycja.komunikat = Edycja.blad(it) }
    }

    fun ulubioneUsunWszystkie(l: List<Asset>) = Edycja.zakres.launch {
        var n = 0
        for (a in l) if (Ulubione.jest(a)) { Ulubione.przelacz(a); n++ }
        Edycja.komunikat = s(R.string.edytor_ulubione_usunieto, n)
    }

    fun ulubioneWszystkie(l: List<Asset>) = Edycja.zakres.launch {
        var n = 0
        for (a in l) if (!Ulubione.jest(a)) { Ulubione.przelacz(a); n++ }
        Edycja.komunikat = s(R.string.edytor_ulubione, n)
    }

    fun nieWeWspomnieniach(ids: List<String>) {
        UkryteWeWspomnieniach.ukryj(ids)
        Edycja.komunikat = s(R.string.edytor_nie_wspomnienia, ids.size)
    }

    /** Pozycje edycji jednego zdjęcia (menu podręczne). */
    fun pozycjeZdjecia(a: Asset): List<Podreczne.Pozycja> = listOf(
        Podreczne.Pozycja(s(R.string.edytor_data)) { data = listOf(a) },
        Podreczne.Pozycja(s(R.string.edytor_miejsce)) { miejsce = listOf(a.id) },
        Podreczne.Pozycja(s(R.string.edytor_opis)) { opis = a },
        Podreczne.Pozycja(s(R.string.edytor_ocena)) { ocena = listOf(a.id) },
        Podreczne.Pozycja(s(R.string.edytor_archiwizuj)) { archiwizuj(listOf(a.id)) },
        Podreczne.Pozycja(s(R.string.edytor_nie_wspomnienia_akcja)) { nieWeWspomnieniach(listOf(a.id)) },
        Podreczne.Pozycja(s(R.string.edytor_kosz)) { doKosza(listOf(a.id)) },
    )

    /** Menu akcji dla wszystkich zaznaczonych. */
    fun menuZaznaczonych() {
        val l = Udostepnianie.koszyk.toList()
        if (l.isEmpty()) return
        Podreczne.menu = s(R.string.edytor_zaznaczone_tytul, l.size) to pozycjeZaznaczonych(l)
    }

    /** Wszystkie operacje zbiorcze dla zdjęć [l] (zaznaczonych). */
    fun pozycjeZaznaczonych(l: List<Asset>): List<Podreczne.Pozycja> {
        val ids = l.map { it.id }
        val p = ArrayList<Podreczne.Pozycja>()
        p += Podreczne.Pozycja(s(R.string.edytor_ulubione_akcja)) { ulubioneWszystkie(l) }
        p += Podreczne.Pozycja(s(R.string.edytor_ulubione_usun_akcja)) { ulubioneUsunWszystkie(l) }
        p += Podreczne.Pozycja(s(R.string.album_akcja)) { Podreczne.doAlbumu = ids }
        p += Podreczne.Pozycja(s(R.string.dodaj_osobe_zbiorczo, l.size)) { DodajOsobe.otworz(l) }
        // osoby: w osi czasu osoby — „to nie ona” / „to ktoś inny”; wszędzie — odepnij albo przepisz wybraną osobę
        Podreczne.osobaKontekst?.let { (oid, imie, _) ->
            p += Podreczne.Pozycja(s(R.string.zbiorczo_nie_ta, imie.ifBlank { "…" }, l.size)) { Podreczne.odepnijOsobeZ(ids, oid) }
            p += Podreczne.Pozycja(s(R.string.zbiorczo_ktos_inny, l.size)) { Podreczne.przepisanie = ids to oid }
        }
        p += Podreczne.Pozycja(s(R.string.zbiorczo_odepnij)) { Podreczne.odpinanie = ids }
        p += Podreczne.Pozycja(s(R.string.zbiorczo_przepisz)) { Podreczne.przepisanieZ = ids }
        p += Podreczne.Pozycja(s(R.string.edytor_data)) { data = l }
        p += Podreczne.Pozycja(s(R.string.edytor_miejsce)) { miejsce = ids }
        p += Podreczne.Pozycja(s(R.string.edytor_ocena)) { ocena = ids }
        p += Podreczne.Pozycja(s(R.string.edytor_archiwizuj)) { archiwizuj(ids) }
        p += Podreczne.Pozycja(s(R.string.edytor_nie_wspomnienia_akcja)) { nieWeWspomnieniach(ids) }
        p += Podreczne.Pozycja(s(R.string.edytor_kosz)) { doKosza(ids) }
        p += Podreczne.Pozycja(s(R.string.zazn_pokaz, l.size)) { Podreczne.nav?.bekijk(LijstBron(l.sortedBy { it.datum }), 0, s(R.string.zazn_tytul), true, null) }
        p += Podreczne.Pozycja(s(R.string.zazn_udostepnij, l.size)) { Udostepnianie.otworz(Taal.ctx) }
        p += Podreczne.Pozycja(s(if (Udostepnianie.tryb) R.string.zazn_koniec else R.string.zazn_dalej)) { Udostepnianie.tryb = !Udostepnianie.tryb }
        p += Podreczne.Pozycja(s(R.string.zazn_wyczysc, Udostepnianie.koszyk.size)) { Udostepnianie.wyczysc(); Edycja.komunikat = s(R.string.zazn_wyczyszczono) }
        return p
    }
}

/** Wybór daty i godziny pilotem: ◀ ▶ pole, ▲ ▼ wartość, OK zapisz. */
@Composable
fun DataWybor(start: LocalDateTime, ile: Int, sluit: () -> Unit, zapisz: (LocalDateTime) -> Unit) {
    Lagen.Open()
    BackHandler { sluit() }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { fr.requestFocus() } }
    var d by remember { mutableStateOf(start.withSecond(0).withNano(0)) }
    var pole by remember { mutableIntStateOf(0) }
    // cyfry z pilota wpisują wartość bieżącego pola (rok 4 cyfry, reszta 2), potem przejście do następnego
    var bufor by remember { mutableStateOf("") }
    fun wpisz(c: Int) {
        bufor += c
        val v = bufor.toInt()
        val dl = if (pole == 0) 4 else 2
        runCatching {
            when (pole) {
                0 -> if (bufor.length == 4 && v in 1800..2100) d = d.withYear(v).withDayOfMonth(d.dayOfMonth.coerceAtMost(java.time.YearMonth.of(v, d.monthValue).lengthOfMonth()))
                1 -> if (v in 1..12) d = d.withDayOfMonth(d.dayOfMonth.coerceAtMost(java.time.YearMonth.of(d.year, v).lengthOfMonth())).withMonth(v)
                2 -> if (v in 1..d.toLocalDate().lengthOfMonth()) d = d.withDayOfMonth(v)
                3 -> if (v in 0..23) d = d.withHour(v)
                else -> if (v in 0..59) d = d.withMinute(v)
            }
        }
        val pelne = bufor.length >= dl || (pole == 1 && v > 1) || (pole == 2 && v > 3) || (pole == 3 && v > 2) || (pole == 4 && v > 5)
        if (pelne) { bufor = ""; pole = (pole + 1).coerceAtMost(4) }
    }
    fun zmien(k: Int) {
        bufor = ""
        d = when (pole) { 0 -> d.plusYears(k.toLong()); 1 -> d.plusMonths(k.toLong()); 2 -> d.plusDays(k.toLong()); 3 -> d.plusHours(k.toLong()); else -> d.plusMinutes(5L * k) }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            cyfraKlawisza(e.key.nativeKeyCode)?.let { wpisz(it); return@onPreviewKeyEvent true }
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { bufor = ""; pole = (pole - 1).coerceAtLeast(0); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { bufor = ""; pole = (pole + 1).coerceAtMost(4); true }
                KeyEvent.KEYCODE_DPAD_UP -> { zmien(1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { zmien(-1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (e.nativeKeyEvent.repeatCount == 0) zapisz(d); true }
                else -> false
            }
        }.padding(horizontal = 64.dp, vertical = 48.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.edytor_data), style = MaterialTheme.typography.displaySmall)
            Text(if (ile > 1) stringResource(R.string.edytor_data_wiele, ile) else stringResource(R.string.edytor_data_hint), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            val pola = listOf("%04d".format(d.year), d.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, Taal.locale), d.dayOfMonth.toString(), "%02d".format(d.hour), "%02d".format(d.minute))
            val etykiety = listOf(R.string.edytor_rok, R.string.edytor_miesiac, R.string.edytor_dzien, R.string.edytor_godzina, R.string.edytor_minuta)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 24.dp)) {
                pola.mapIndexed { i, t -> if (i == pole && bufor.isNotEmpty()) bufor.padEnd(if (i == 0) 4 else 2, '_') else t }.forEachIndexed { i, t ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(etykiety[i]), style = MaterialTheme.typography.labelLarge, color = Kleur.zacht)
                        Text(
                            (if (i == pole) "▲\n" else "\n") + t + (if (i == pole) "\n▼" else "\n"),
                            style = MaterialTheme.typography.headlineMedium, color = if (i == pole) Color(0xFF101018) else Color.White,
                            modifier = Modifier.padding(top = 6.dp)
                                .background(if (i == pole) Color.White else Color(0x22FFFFFF), RoundedCornerShape(14.dp))
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            Text(stringResource(R.string.edytor_data_klawisze), style = MaterialTheme.typography.labelLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 18.dp))
        }
    }
}

/** Wszystkie okna edycji (rysuje MainActivity nad wszystkim). */
@Composable
fun EdytorWarstwa() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Edytor.data?.let { l ->
        val posort = remember(l) { l.sortedBy { it.datum } }
        DataWybor(posort.first().datum, l.size, sluit = { Edytor.data = null }) { nowa ->
            Edytor.data = null
            Edycja.zakres.launch {
                // kilka zdjęć: ta sama data, kolejne o minutę później (zachowana kolejność, np. cały skan z jednego roku)
                val wynik = runCatching { posort.forEachIndexed { i, a -> Immich.zmienDate(a.id, nowa.plusMinutes(i.toLong())) } }
                Edycja.komunikat = wynik.exceptionOrNull()?.let { Edycja.blad(it) } ?: ctx.getString(R.string.edytor_data_ok, l.size)
            }
        }
    }
    Edytor.miejsce?.let { ids ->
        // miejsca: miasta ze zdjęć na mapie (najczęstsze najpierw); współrzędne = średnia zdjęć z tego miasta
        val miasta by produceState<List<KiesItem>?>(null) {
            value = runCatching {
                val m = Immich.markers()
                val grupy = HashMap<String, MutableList<Int>>()
                for (i in 0 until m.aantal) m.stad[i]?.let { st -> grupy.getOrPut(st + "|" + (m.land[i] ?: "")) { ArrayList() }.add(i) }
                grupy.entries.sortedByDescending { it.value.size }.map { (k, v) ->
                    val lat = v.map { m.lat[it] }.average(); val lon = v.map { m.lon[it] }.average()
                    KiesItem("$lat,$lon", k.substringBefore('|'), listOfNotNull(k.substringAfter('|').ifBlank { null }, Taal.getal(v.size)).joinToString("  ·  "), null)
                }
            }.getOrDefault(emptyList())
        }
        KiesLijst(stringResource(R.string.edytor_miejsce_tytul, ids.size), miasta, "", sluit = { Edytor.miejsce = null }) { wsp, naam ->
            Edytor.miejsce = null
            val (lat, lon) = wsp.split(',').map { it.toDouble() }
            Edycja.zakres.launch {
                runCatching { Immich.zmienMiejsce(ids, lat, lon) }
                    .onSuccess { Edycja.komunikat = ctx.getString(R.string.edytor_miejsce_ok, naam, ids.size) }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
    Edytor.opis?.let { a ->
        val obecny by produceState<String?>(null, a.id) { value = runCatching { Immich.details(a.id).omschrijving }.getOrNull().orEmpty() }
        obecny?.let { o ->
            TekstInvoer(stringResource(R.string.edytor_opis), o, actie = stringResource(R.string.zapisz), wielkie = true, sluit = { Edytor.opis = null }) { nowy ->
                Edytor.opis = null
                Edycja.zakres.launch {
                    runCatching { Immich.zmienOpis(a.id, nowy.trim()) }
                        .onSuccess { Edycja.komunikat = ctx.getString(R.string.zapisano) }
                        .onFailure { Edycja.komunikat = Edycja.blad(it) }
                }
            }
        }
    }
    Edytor.ocena?.let { ids ->
        MenuWarstwa(stringResource(R.string.edytor_ocena), stringResource(R.string.edytor_ocena_sub, ids.size), { Edytor.ocena = null }) { pierwszy ->
            (5 downTo 0).forEach { g ->
                Knop(if (g == 0) stringResource(R.string.edytor_bez_oceny) else "★".repeat(g) + "☆".repeat(5 - g), null, (if (g == 5) pierwszy else Modifier).width(420.dp)) {
                    Edytor.ocena = null
                    Edycja.zakres.launch {
                        val w = runCatching { ids.forEach { Immich.ocen(it, g) } }
                        Edycja.komunikat = w.exceptionOrNull()?.let { Edycja.blad(it) } ?: ctx.getString(R.string.edytor_ocena_ok, ids.size)
                    }
                }
            }
        }
    }
    Edytor.link?.let { (id, nazwa) -> LinkOkno(id, nazwa) { Edytor.link = null } }
    Edytor.udostepnij?.let { (id, nazwa) ->
        val konta by produceState<List<KiesItem>?>(null) {
            value = runCatching { Immich.uzytkownicy().map { (uid, n) -> KiesItem(uid, n, null, null) } }.getOrDefault(emptyList())
        }
        KiesLijst(stringResource(R.string.edytor_udostepnij_tytul, nazwa), konta, "", sluit = { Edytor.udostepnij = null }) { uid, n ->
            Edytor.udostepnij = null
            Edycja.zakres.launch {
                runCatching { Immich.udostepnijAlbum(id, uid) }
                    .onSuccess { Edycja.komunikat = ctx.getString(R.string.edytor_udostepniono, nazwa, n) }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
}

/** Link do albumu z kodem QR: najpierw ważność, potem kod do zeskanowania telefonem. */
@Composable
fun LinkOkno(albumId: String, nazwa: String, sluit: () -> Unit) {
    var adres by remember { mutableStateOf<String?>(null) }
    var trwa by remember { mutableStateOf(false) }
    val a = adres
    if (a == null) MenuWarstwa(stringResource(R.string.link_tytul, nazwa), stringResource(R.string.link_sub), sluit) { pierwszy ->
        listOf(7 to R.string.link_7, 30 to R.string.link_30, null to R.string.link_bez).forEachIndexed { i, (dni, napis) ->
            Knop(stringResource(if (trwa) R.string.laden else napis), null, (if (i == 0) pierwszy else Modifier).width(560.dp)) {
                if (trwa) return@Knop
                trwa = true
                Edycja.zakres.launch {
                    runCatching { Immich.linkDoAlbumu(albumId, dni) }
                        .onSuccess { adres = it }
                        .onFailure { Edycja.komunikat = Edycja.blad(it); sluit() }
                    trwa = false
                }
            }
        }
    }
    else {
        Lagen.Open()
        BackHandler { sluit() }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(40); runCatching { fr.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().padding(48.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.link_gotowy, nazwa), style = MaterialTheme.typography.headlineMedium)
                val qr = remember(a) { Koppel.qr(a).asImageBitmap() }
                Box(Modifier.size(300.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp)) {
                    Image(qr, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                }
                Text(a, style = MaterialTheme.typography.titleSmall, color = Kleur.primair)
                Text(stringResource(R.string.link_info), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                Knop(stringResource(R.string.actie_klaar), null, Modifier.focusRequester(fr).width(300.dp)) { sluit() }
            }
        }
    }
}
