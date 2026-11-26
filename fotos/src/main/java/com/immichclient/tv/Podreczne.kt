package com.immichclient.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

/**
 * Przytrzymanie OK = menu podręczne dla elementu z fokusem (zdjęcie, album, osoba, wspomnienie). Element rejestruje
 * swoje menu modyfikatorem [langeOk]; MainActivity.dispatchKeyEvent przy pierwszym powtórzeniu OK (ok. pół sekundy)
 * wywołuje je i „połyka” resztę naciśnięcia, więc zwykłe kliknięcie się nie wykonuje. Krótkie OK działa jak dotąd.
 */
object Podreczne {
    class Pozycja(val tekst: String, val akcja: () -> Unit)

    /** zarejestrowane menu elementu z fokusem: (właściciel, akcja) */
    @Volatile var aktywne: Pair<Any, () -> Unit>? = null
    /** element, na którym przytrzymano OK: po zamknięciu menu (i okien z niego) fokus wraca na niego */
    @Volatile var aktywnyFr: androidx.compose.ui.focus.FocusRequester? = null
    var zrodlo: androidx.compose.ui.focus.FocusRequester? = null
    /** zmienia się przy każdej zmianie ekranu / otwarciu przeglądarki (MainActivity): wtedy fokusu nie przywracamy */
    var wersjaEkranu = 0
    var wersjaPrzyOtwarciu = -1

    var menu by mutableStateOf<Pair<String, List<Pozycja>>?>(null)
    /** okna otwierane z menu (rysuje je [PodreczneWarstwa]) */
    var doAlbumu by mutableStateOf<List<String>?>(null)
    var zmianaNazwy by mutableStateOf<Pair<String, String>?>(null)
    var usuwanie by mutableStateOf<Pair<String, String>?>(null)
    var osobaEdycja by mutableStateOf<Pair<String, String>?>(null)
    /** pełny edytor bocznego menu (kolejność, ukrywanie) */
    var edycjaMenu by mutableStateOf(false)

    /** Przytrzymanie OK na pozycji bocznego menu: przesuń, ukryj, edytuj całe menu. */
    fun sekcja(sec: Sectie, nazwa: String) {
        val p = ArrayList<Pozycja>()
        if (sec != Sectie.INSTELLINGEN) {
            p += Pozycja(s(R.string.menu_wyzej)) { Sectie.przesun(sec, -1) }
            p += Pozycja(s(R.string.menu_nizej)) { Sectie.przesun(sec, 1) }
            p += Pozycja(s(R.string.menu_ukryj, nazwa)) { Sectie.ukryj(sec, true); komunikat(s(R.string.menu_ukryto, nazwa)) }
        }
        p += Pozycja(s(R.string.menu_edytuj)) { edycjaMenu = true }
        val ukryte = Sectie.ukryte()
        if (ukryte.isNotEmpty()) p += Pozycja(s(R.string.menu_pokaz_ukryte, ukryte.size)) { Instellingen.ukryteSekcje = "" }
        menu = nazwa to p
    }

    /** po usunięciu albumu (np. zamknij jego oś czasu) */
    var poUsunieciu: (() -> Unit)? = null

    /** ustawiane przez MainActivity */
    var nav: Nav? = null
    var naMape: ((Asset) -> Unit)? = null

    private fun s(id: Int, vararg a: Any) = Taal.s(id, *a)
    private fun komunikat(t: String) { Edycja.komunikat = t }

    /** Wywołane przez dispatchKeyEvent: true = menu otwarte (reszta naciśnięcia OK ma zostać połknięta). */
    fun dlugieOk(): Boolean {
        val a = aktywne ?: return false
        if (menu != null) return false
        zrodlo = aktywnyFr; wersjaPrzyOtwarciu = wersjaEkranu
        a.second()
        return true
    }

    // ---------------- menu dla rodzajów elementów ----------------

    fun zdjecie(a: Asset, pokaz: ((Asset) -> Unit)? = null) {
        if (Synology.jestSyno(a.id)) { pokaz?.let { p -> menu = (a.datum.toLocalDate().format(Formaat.dagJaar)) to listOf(Pozycja(s(R.string.pokaz_od_tego)) { p(a) }) }; return }
        val p = ArrayList<Pozycja>()
        val ulub = Ulubione.jest(a)
        p += Pozycja(s(if (ulub) R.string.ulubione_usun else R.string.podreczne_ulub_dodaj)) {
            Edycja.zakres.launch { Ulubione.przelacz(a)?.let { komunikat(s(it)) } }
        }
        p += Pozycja(s(if (Udostepnianie.zawiera(a)) R.string.podreczne_odznacz else R.string.podreczne_zaznacz)) {
            Udostepnianie.przelacz(a)
            // dalej krótkie OK zaznacza kolejne (tryb zaznaczania, Wstecz kończy)
            if (Udostepnianie.koszyk.isNotEmpty()) Udostepnianie.tryb = true
            komunikat(s(R.string.podreczne_zaznaczone, Udostepnianie.koszyk.size))
        }
        if (!Instellingen.trybDzieciecy) p += Pozycja(s(R.string.album_akcja)) { doAlbumu = (Udostepnianie.koszyk.map { it.id } + a.id).distinct() }
        if (a.lat != null) naMape?.let { m -> p += Pozycja(s(R.string.podreczne_na_mapie)) { m(a) } }
        // edycja: data, miejsce, opis, ocena, archiwum, „nie we wspomnieniach”, kosz
        if (!Instellingen.trybDzieciecy) p += Edytor.pozycjeZdjecia(a)
        pokaz?.let { f -> p += Pozycja(s(R.string.pokaz_od_tego)) { f(a) } }
        // zaznaczenie: pokaz, udostępnienie, wyczyszczenie jednym ruchem, tryb zaznaczania
        val ile = Udostepnianie.koszyk.size
        if (ile > 0) {
            if (!Instellingen.trybDzieciecy) p += Pozycja(s(R.string.edytor_zaznaczone_akcja, ile)) { Edytor.menuZaznaczonych() }
            p += Pozycja(s(R.string.zazn_pokaz, ile)) { nav?.bekijk(LijstBron(Udostepnianie.koszyk.sortedBy { it.datum }), 0, s(R.string.zazn_tytul), true, null) }
            p += Pozycja(s(R.string.zazn_udostepnij, ile)) { Udostepnianie.otworz(Taal.ctx) }
            p += Pozycja(s(if (Udostepnianie.tryb) R.string.zazn_koniec else R.string.zazn_dalej)) { Udostepnianie.tryb = !Udostepnianie.tryb }
            p += Pozycja(s(R.string.zazn_wyczysc, ile)) { Udostepnianie.wyczysc(); komunikat(s(R.string.zazn_wyczyszczono)) }
        }
        menu = listOfNotNull(a.datum.toLocalDate().format(Formaat.dagJaar), a.stad).joinToString("  ·  ") to p
    }

    fun album(id: String, nazwa: String) {
        val n = nav ?: return
        val p = ArrayList<Pozycja>()
        p += Pozycja(s(R.string.diashow)) {
            Edycja.zakres.launch {
                runCatching { Immich.zAlbumu(id, 2000) }.onSuccess { l -> if (l.isNotEmpty()) n.bekijk(LijstBron(l.sortedBy { it.datum }), 0, nazwa, true, null) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
        if (!Instellingen.trybDzieciecy) {
            p += Pozycja(s(R.string.album_zmien_nazwe)) { zmianaNazwy = id to nazwa }
            val naEkranie = Instellingen.kanalAlbum == id
            p += Pozycja(s(if (naEkranie) R.string.podreczne_z_ekranu else R.string.podreczne_na_ekran)) {
                if (naEkranie) { Instellingen.kanalAlbum = ""; Instellingen.kanalAlbumNazwa = "" }
                else { Instellingen.kanalAlbum = id; Instellingen.kanalAlbumNazwa = nazwa }
                Kanaly.odswiezWTle(Taal.ctx)
                komunikat(s(if (naEkranie) R.string.podreczne_z_ekranu_ok else R.string.podreczne_na_ekran_ok))
            }
            p += Pozycja(s(R.string.link_akcja)) { Edytor.link = id to nazwa }
            p += Pozycja(s(R.string.edytor_udostepnij_akcja)) { Edytor.udostepnij = id to nazwa }
            p += Pozycja(s(R.string.album_usun_caly)) { poUsunieciu = null; usuwanie = id to nazwa }
        }
        menu = nazwa to p
    }

    fun osoba(id: String, nazwa: String) {
        val n = nav ?: return
        val p = ArrayList<Pozycja>()
        p += Pozycja(s(R.string.diashow)) {
            Edycja.zakres.launch {
                runCatching { Immich.osobaWOkresie(id, null, null, 1000, rosnaco = true) }
                    .onSuccess { l -> if (l.isNotEmpty()) n.bekijk(LijstBron(l), 0, nazwa, true, null) }
                    .onFailure { komunikat(Edycja.blad(it)) }
            }
        }
        p += Pozycja(s(R.string.rosla_przycisk)) { n.open(Scherm.Rosniecie(id, nazwa, n.scope)) }
        if (!Instellingen.trybDzieciecy) {
            p += Pozycja(s(R.string.edytuj_osobe)) { osobaEdycja = id to nazwa }
            p += Pozycja(s(R.string.popraw_przycisk)) { n.open(Scherm.Popraw(id, nazwa, n.scope)) }
            p += Pozycja(s(R.string.glowne_zmien)) { n.open(Scherm.Popraw(id, nazwa, n.scope, wyborGlownego = true)) }
            p += Pozycja(s(R.string.podreczne_ukryj)) {
                Edycja.zakres.launch {
                    runCatching { Immich.ukryjOsobe(id, true) }
                        .onSuccess { Edycja.ukryte[id] = true; Edycja.wersjaOsob++; komunikat(s(R.string.podreczne_ukryto, nazwa.ifBlank { "…" })) }
                        .onFailure { komunikat(Edycja.blad(it)) }
                }
            }
        }
        menu = nazwa.ifBlank { s(R.string.persoon_bez_imienia) } to p
    }

    fun wspomnienie(h: Herinnering, tytul: String) {
        val n = nav ?: return
        val p = ArrayList<Pozycja>()
        p += Pozycja(s(R.string.diashow)) { n.bekijk(LijstBron(h.assets), 0, tytul, true, null) }
        if (!Instellingen.trybDzieciecy) p += Pozycja(s(R.string.podreczne_wszystkie_do_albumu, h.assets.size)) { doAlbumu = h.assets.map { it.id } }
        menu = tytul to p
    }
}

/** Element z menu pod przytrzymaniem OK (rejestracja, gdy ma fokus). */
@Composable
fun Modifier.langeOk(akcja: () -> Unit): Modifier {
    val wlasciciel = remember { Any() }
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val aktualna by rememberUpdatedState(akcja)
    DisposableEffect(Unit) { onDispose { if (Podreczne.aktywne?.first === wlasciciel) { Podreczne.aktywne = null; Podreczne.aktywnyFr = null } } }
    return this.focusRequester(fr).onFocusChanged {
        if (it.hasFocus) { Podreczne.aktywne = wlasciciel to { aktualna() }; Podreczne.aktywnyFr = fr }
        else if (Podreczne.aktywne?.first === wlasciciel) { Podreczne.aktywne = null; Podreczne.aktywnyFr = null }
    }
}

/** Menu podręczne i okna z niego (nad wszystkim, także nad przeglądarką). */
@Composable
fun PodreczneWarstwa() {
    val ctx = LocalContext.current
    // po zamknięciu menu i okien z niego: fokus z powrotem na element, na którym przytrzymano OK (nie do bocznego menu)
    val otwarte = Podreczne.menu != null || Podreczne.doAlbumu != null || Podreczne.zmianaNazwy != null || Podreczne.usuwanie != null ||
        Podreczne.osobaEdycja != null || Podreczne.edycjaMenu || Edytor.data != null || Edytor.miejsce != null || Edytor.opis != null ||
        Edytor.ocena != null || Edytor.link != null || Edytor.udostepnij != null
    androidx.compose.runtime.LaunchedEffect(otwarte) {
        if (otwarte) return@LaunchedEffect
        val z = Podreczne.zrodlo ?: return@LaunchedEffect
        Podreczne.zrodlo = null
        if (Podreczne.wersjaEkranu != Podreczne.wersjaPrzyOtwarciu) return@LaunchedEffect
        repeat(5) { kotlinx.coroutines.delay(40); if (runCatching { z.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    Podreczne.menu?.let { (tytul, pozycje) ->
        MenuWarstwa(tytul, stringResource(R.string.podreczne_sub), { Podreczne.menu = null }) { pierwszy ->
            pozycje.forEachIndexed { i, p ->
                Knop(p.tekst, null, (if (i == 0) pierwszy else Modifier).width(560.dp)) { Podreczne.menu = null; p.akcja() }
            }
        }
    }
    Podreczne.doAlbumu?.let { ids -> AlbumWybor(ids, komunikat = { Edycja.komunikat = it }, sluit = { Podreczne.doAlbumu = null }) }
    Podreczne.zmianaNazwy?.let { (id, nazwa) ->
        TekstInvoer(stringResource(R.string.album_zmien_nazwe), Edycja.nazwyAlbumow[id] ?: nazwa, actie = stringResource(R.string.zapisz), wielkie = true, sluit = { Podreczne.zmianaNazwy = null }) { nowa ->
            Podreczne.zmianaNazwy = null
            if (nowa.isBlank() || nowa.trim() == nazwa) return@TekstInvoer
            Edycja.zakres.launch {
                runCatching { Immich.zmienNazweAlbumu(id, nowa) }
                    .onSuccess {
                        Edycja.nazwyAlbumow[id] = nowa.trim(); Edycja.wersjaAlbumow++
                        if (Instellingen.kanalAlbum == id) Instellingen.kanalAlbumNazwa = nowa.trim()
                        Edycja.komunikat = ctx.getString(R.string.zapisano)
                    }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
    Podreczne.usuwanie?.let { (id, nazwa) ->
        MenuWarstwa(stringResource(R.string.album_usun_pytanie, nazwa), stringResource(R.string.album_usun_info), { Podreczne.usuwanie = null }) { pierwszy ->
            Knop(stringResource(R.string.anuluj), null, pierwszy.width(560.dp)) { Podreczne.usuwanie = null }
            Knop(stringResource(R.string.album_usun_tak), null, Modifier.width(560.dp)) {
                Podreczne.usuwanie = null
                Edycja.zakres.launch {
                    runCatching { Immich.usunAlbum(id) }
                        .onSuccess {
                            if (Instellingen.kanalAlbum == id) { Instellingen.kanalAlbum = ""; Instellingen.kanalAlbumNazwa = ""; Kanaly.odswiezWTle(ctx) }
                            Edycja.wersjaAlbumow++
                            Edycja.komunikat = ctx.getString(R.string.album_usuniety, nazwa)
                            Podreczne.poUsunieciu?.invoke(); Podreczne.poUsunieciu = null
                        }
                        .onFailure { Edycja.komunikat = Edycja.blad(it) }
                }
            }
        }
    }
    if (Podreczne.edycjaMenu) KolejnoscSekcji { Podreczne.edycjaMenu = false }
    Podreczne.osobaEdycja?.let { (id, nazwa) ->
        OsobaMenu(id, nazwa, ustawTytul = {}, poPolaczeniu = {}, komunikat = { Edycja.komunikat = it }, sluit = { Podreczne.osobaEdycja = null })
    }
}

/** Pasek na dole: ile zaznaczonych i podpowiedź (bez fokusu — nie przeszkadza w nawigacji). */
@Composable
fun PasekZaznaczenia(modifier: Modifier) {
    val ile = Udostepnianie.koszyk.size
    androidx.compose.foundation.layout.Row(
        modifier.padding(start = 110.dp, bottom = 24.dp)
            .background(Color(0xF0181822), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .border(2.dp, Color(0xFFF5B83D), androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, null, Modifier.size(22.dp), tint = Color(0xFFF5B83D))
        androidx.tv.material3.Text("  " + stringResource(R.string.zazn_licznik, ile), style = androidx.tv.material3.MaterialTheme.typography.titleMedium, color = Color.White)
        androidx.tv.material3.Text(
            "   ·   " + stringResource(if (Udostepnianie.tryb) R.string.zazn_hint_tryb else R.string.zazn_hint),
            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium, color = Color(0xCCFFFFFF),
        )
    }
}
