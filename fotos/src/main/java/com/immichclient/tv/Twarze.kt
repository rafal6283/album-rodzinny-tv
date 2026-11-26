package com.immichclient.tv

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Twarz wykryta przez Immich: ramka jako ułamki zdjęcia i przypisana osoba (null = nikt). */
class Twarz(val id: String, val assetId: String, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val osoba: Persoon?)

/** Wycinek zdjęcia z samą twarzą (z marginesem), wyśrodkowaną w kafelku. */
@Composable
fun WycinekTwarzy(assetId: String, ratio: Float, t: Twarz, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clipToBounds().background(Kleur.vlak)) {
        val s = constraints.maxWidth.toFloat(); val sh = constraints.maxHeight.toFloat()
        val fw = (t.x2 - t.x1).coerceAtLeast(0.01f); val fh = (t.y2 - t.y1).coerceAtLeast(0.01f)
        val r = ratio.coerceIn(0.2f, 5f)
        // szerokość całego zdjęcia tak, by twarz z marginesem (×1,7) wypełniła kafelek
        val wi = s / (1.7f * maxOf(fw, fh / r * (s / sh)))
        val hi = wi / r
        val cx = (t.x1 + t.x2) / 2 * wi; val cy = (t.y1 + t.y2) / 2 * hi
        AsyncImage(
            preview(assetId), null,
            Modifier.layout { m, _ ->
                val p = m.measure(Constraints.fixed(wi.roundToInt().coerceAtLeast(1), hi.roundToInt().coerceAtLeast(1)))
                layout(s.roundToInt(), sh.roundToInt()) { p.place((s / 2 - cx).roundToInt(), (sh / 2 - cy).roundToInt()) }
            },
            contentScale = ContentScale.FillBounds,
        )
    }
}

/** Operacje na twarzach (w niezależnym zakresie: zamknięcie okna nie przerywa zapisu). */
object Korekta {
    /** wykonaj i zgłoś wynik; [gotowe] z twarzami, które się udały */
    fun wykonaj(twarze: List<Twarz>, opis: String, gotowe: (List<Twarz>) -> Unit, akcja: suspend (Twarz) -> Unit) {
        Edycja.zakres.launch {
            val ok = ArrayList<Twarz>()
            var blad: Throwable? = null
            for (t in twarze) runCatching { akcja(t) }.onSuccess { ok += t }.onFailure { blad = it; Log.w("Fotos", "korekta twarzy", it) }
            Edycja.wersjaOsob++
            Edycja.komunikat = blad?.let { Edycja.blad(it) } ?: opis
            gotowe(ok)
        }
    }

    fun przypisz(twarze: List<Twarz>, osobaId: String, nazwa: String, gotowe: (List<Twarz>) -> Unit) =
        wykonaj(twarze, Taal.s(R.string.twarz_przypisano, twarze.size, nazwa), gotowe) { Immich.przypiszTwarz(it.id, osobaId) }

    fun nowa(twarze: List<Twarz>, imie: String, gotowe: (List<Twarz>) -> Unit) {
        Edycja.zakres.launch {
            runCatching { Immich.nowaOsoba(imie) }
                .onSuccess { id -> przypisz(twarze, id, imie.ifBlank { Taal.s(R.string.persoon_bez_imienia) }, gotowe) }
                .onFailure { Edycja.komunikat = Edycja.blad(it) }
        }
    }

    /** „To nie ta osoba”: każda twarz dostaje własną nową osobę bez imienia (Immich nie ma „brak osoby”). */
    fun odepnij(twarze: List<Twarz>, gotowe: (List<Twarz>) -> Unit) =
        wykonaj(twarze, Taal.s(R.string.twarz_odpieto, twarze.size), gotowe) { Immich.przypiszTwarz(it.id, Immich.nowaOsoba("")) }

    fun usun(twarze: List<Twarz>, gotowe: (List<Twarz>) -> Unit) =
        wykonaj(twarze, Taal.s(R.string.twarz_usunieto, twarze.size), gotowe) { Immich.usunTwarz(it.id) }
}

/** Menu dla wybranych twarzy: to jest…, nowa osoba, to nie ta osoba, to nie twarz. */
@Composable
fun TwarzeAkcje(twarze: List<Twarz>, tytul: String, sluit: () -> Unit, gotowe: (List<Twarz>) -> Unit) {
    val jedna = twarze.singleOrNull()?.takeIf { it.osoba != null }
    var okno by remember { mutableStateOf<String?>(null) }
    when (okno) {
        null -> MenuWarstwa(tytul, stringResource(R.string.twarz_menu_sub), sluit) { pierwszy ->
            Knop(stringResource(R.string.twarz_to_jest), null, pierwszy.width(560.dp)) { okno = "osoba" }
            Knop(stringResource(R.string.twarz_nowa), null, Modifier.width(560.dp)) { okno = "nowa" }
            Knop(stringResource(R.string.twarz_nie_ta), null, Modifier.width(560.dp)) { sluit(); Korekta.odepnij(twarze, gotowe) }
            Knop(stringResource(R.string.twarz_nie_twarz), null, Modifier.width(560.dp)) { sluit(); Korekta.usun(twarze, gotowe) }
            // jedna twarz przypisanej osoby: to zdjęcie jako jej zdjęcie główne
            if (jedna != null) Knop(stringResource(R.string.glowne_ta_twarz, Edycja.imie(jedna.osoba!!).ifBlank { "…" }), null, Modifier.width(560.dp)) {
                sluit(); Edycja.ustawGlowne(jedna.osoba!!.id, jedna.assetId)
            }
        }
        "osoba" -> KiesLijst(stringResource(R.string.twarz_to_jest), persoonKeuzes(), "", sluit = { okno = null }) { id, naam ->
            sluit(); Korekta.przypisz(twarze, id, naam, gotowe)
        }
        "nowa" -> TekstInvoer(stringResource(R.string.twarz_nowa), "", actie = stringResource(R.string.zapisz), wielkie = true, sluit = { okno = null }) { imie ->
            sluit(); Korekta.nowa(twarze, imie, gotowe)
        }
    }
}

/** „Osoby na tym zdjęciu” (z przeglądarki): wycięte twarze z imionami; OK na twarzy = korekta. */
@Composable
fun TwarzeZdjecia(a: Asset, sluit: () -> Unit) {
    Lagen.Open()
    var wersja by remember { mutableIntStateOf(0) }
    val twarze by produceState<Result<List<Twarz>>?>(null, a.id, wersja) { value = runCatching { Immich.twarze(a.id) } }
    var wybrana by remember { mutableStateOf<Twarz?>(null) }
    val pierwszy = remember { FocusRequester() }
    BackHandler(enabled = wybrana == null) { sluit() }
    LaunchedEffect(twarze, wybrana) { if (wybrana == null) { delay(60); runCatching { pierwszy.requestFocus() } } }
    // lista twarzy znika na czas menu korekty: inaczej trzymała fokus (focusVangen) i przycisków menu nie dało się wybrać
    if (wybrana == null) Box(Modifier.fillMaxSize().background(Color(0xF20A0A0A)).focusVangen().padding(horizontal = 56.dp, vertical = 40.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.twarze_tytul), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.twarze_sub), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            val l = twarze
            when {
                l == null -> Laadpil(true)
                l.isFailure -> Text(Edycja.blad(l.exceptionOrNull()!!), color = Color(0xFFFCA5A5))
                l.getOrThrow().isEmpty() -> Text(stringResource(R.string.twarze_brak), color = Kleur.zacht)
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 16.dp, horizontal = 4.dp)) {
                    items(l.getOrThrow(), key = { it.id }) { t ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(170.dp)) {
                            Card(
                                onClick = { wybrana = t },
                                modifier = (if (t === l.getOrThrow().first()) Modifier.focusRequester(pierwszy) else Modifier).size(160.dp),
                                shape = CardDefaults.shape(CircleShape), scale = CardDefaults.scale(focusedScale = 1.08f),
                            ) { WycinekTwarzy(a.id, a.ratio, t, Modifier.fillMaxSize()) }
                            Text(t.osoba?.let { Edycja.imie(it) }?.ifBlank { null } ?: stringResource(R.string.persoon_bez_imienia),
                                style = MaterialTheme.typography.titleSmall, maxLines = 2, textAlign = TextAlign.Center,
                                color = if (t.osoba?.naam.isNullOrBlank()) Kleur.zacht else Color.White, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
    wybrana?.let { t ->
        TwarzeAkcje(listOf(t), t.osoba?.let { Edycja.imie(it) }?.ifBlank { null } ?: stringResource(R.string.persoon_bez_imienia), sluit = { wybrana = null }) { wersja++ }
    }
}

// ---------------- Popraw dopasowania (siatka twarzy jednej osoby) ----------------

/** Twarze osoby ze wszystkich jej zdjęć (od najstarszych, do ~600), wczytywane porcjami. */
class PoprawStaat(val osobaId: String, val imie: String, private val scope: CoroutineScope) {
    val lista = mutableStateListOf<Pair<Asset, Twarz>>()
    val zaznaczone = mutableStateMapOf<String, Boolean>()
    var gotowe by mutableStateOf(false)
        private set
    var blad by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            var strona = 1
            while (lista.size < 600) {
                val (assets, wiecej) = runCatching { Immich.osobaStrona(osobaId, strona, 48) }
                    .onFailure { blad = Edycja.blad(it) }.getOrNull() ?: break
                for (porcja in assets.chunked(6)) {
                    val wyniki = porcja.map { a ->
                        async {
                            runCatching { Immich.twarze(a.id) }.onFailure { if (blad == null) blad = Edycja.blad(it) }.getOrNull()
                                ?.filter { it.osoba?.id == osobaId }?.maxByOrNull { (it.x2 - it.x1) * (it.y2 - it.y1) }?.let { a to it }
                        }
                    }.awaitAll()
                    lista.addAll(wyniki.filterNotNull())
                }
                if (!wiecej || assets.isEmpty()) break
                strona++
            }
            gotowe = true
        }
    }

    fun wybrane(): List<Twarz> = lista.filter { zaznaczone[it.second.id] == true }.map { it.second }

    /** po udanej korekcie: twarze znikają z siatki (nie należą już do tej osoby) */
    fun usunZListy(t: List<Twarz>) {
        val ids = t.map { it.id }.toSet()
        lista.removeAll { it.second.id in ids }
        ids.forEach { zaznaczone.remove(it) }
    }
}

@Composable
fun PoprawScherm(staat: PoprawStaat, nav: Nav, wyborGlownego: Boolean = false) {
    var okno by remember { mutableStateOf<String?>(null) }
    val pierwszy = remember { FocusRequester() }
    val ile = staat.zaznaczone.count { it.value }
    LaunchedEffect(Unit) { delay(80); runCatching { pierwszy.requestFocus() } }
    val pierwszaTwarz = remember { FocusRequester() }
    if (wyborGlownego) LaunchedEffect(staat.lista.isNotEmpty()) { if (staat.lista.isNotEmpty()) { delay(60); runCatching { pierwszaTwarz.requestFocus() } } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                if (wyborGlownego) SchermKop(
                    stringResource(R.string.glowne_tytul, staat.imie),
                    stringResource(R.string.popraw_ile, staat.lista.size) + (if (!staat.gotowe) "…" else "") + "  ·  " + stringResource(R.string.glowne_hint),
                    etykieta = stringResource(R.string.personen),
                )
                else SchermKop(
                    stringResource(R.string.popraw_tytul, staat.imie),
                    listOfNotNull(
                        stringResource(R.string.popraw_ile, staat.lista.size) + if (!staat.gotowe) "…" else "",
                        if (ile > 0) stringResource(R.string.popraw_zaznaczone, ile) else stringResource(R.string.popraw_hint),
                    ).joinToString("  ·  "),
                    etykieta = stringResource(R.string.personen),
                ) {
                    Keuze(selected = false, onClick = { if (ile > 0) okno = "akcje" }, modifier = Modifier.focusRequester(pierwszy),
                        tekst = stringResource(R.string.popraw_popraw, ile))
                    // dokładnie jedna zaznaczona twarz: jako zdjęcie główne osoby
                    if (ile == 1) Keuze(selected = false, onClick = {
                        staat.lista.firstOrNull { staat.zaznaczone[it.second.id] == true }?.let { (a, _) -> Edycja.ustawGlowne(staat.osobaId, a.id); staat.zaznaczone.clear() }
                    }, tekst = stringResource(R.string.glowne_przycisk))
                    if (ile > 0) Keuze(selected = false, onClick = { staat.zaznaczone.clear() }, tekst = stringResource(R.string.popraw_wyczysc))
                    Keuze(selected = false, onClick = { okno = "polacz" }, tekst = stringResource(R.string.popraw_polacz))
                }
                staat.blad?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFCA5A5)) }
                if (!staat.gotowe && staat.lista.isEmpty()) Laadpil(true)
                else if (staat.gotowe && staat.lista.isEmpty() && staat.blad == null) Text(stringResource(R.string.twarze_brak), color = Kleur.zacht)
            }
        }
        items(staat.lista.toList(), key = { it.second.id }) { (a, t) ->
            val wybrana = staat.zaznaczone[t.id] == true
            Column {
                Card(
                    onClick = {
                        if (wyborGlownego) Edycja.ustawGlowne(staat.osobaId, a.id) { nav.terug() }
                        else staat.zaznaczone[t.id] = !wybrana
                    },
                    // przytrzymanie OK: całe zdjęcie (sprawdzić, kto to naprawdę jest)
                    modifier = (if (wyborGlownego && t === staat.lista.firstOrNull()?.second) Modifier.focusRequester(pierwszaTwarz) else Modifier)
                        .fillMaxWidth().aspectRatio(1f).langeOk { nav.bekijk(LijstBron(listOf(a)), 0, staat.imie, false, null) },
                    shape = CardDefaults.shape(RoundedCornerShape(16.dp)), scale = CardDefaults.scale(focusedScale = 1.08f),
                    border = CardDefaults.border(
                        border = if (wybrana) androidx.tv.material3.Border(BorderStroke(4.dp, Color(0xFFF5B83D)), shape = RoundedCornerShape(16.dp)) else androidx.tv.material3.Border.None,
                        focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(16.dp)),
                    ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        WycinekTwarzy(a.id, a.ratio, t, Modifier.fillMaxSize())
                        if (wybrana) Icon(Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp), tint = Color(0xFFF5B83D))
                    }
                }
                Text(a.datum.toLocalDate().format(Formaat.dagJaar), style = MaterialTheme.typography.labelSmall, color = Kleur.zacht, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    when (okno) {
        "akcje" -> TwarzeAkcje(staat.wybrane(), stringResource(R.string.popraw_zaznaczone, ile), sluit = { okno = null }) { staat.usunZListy(it) }
        "polacz" -> KiesLijst(stringResource(R.string.popraw_polacz_tytul, staat.imie), persoonKeuzes(), "", sluit = { okno = null }) { id, naam ->
            okno = null
            if (id == staat.osobaId) return@KiesLijst
            Edycja.zakres.launch {
                runCatching { Immich.polaczOsoby(id, listOf(staat.osobaId)) }
                    .onSuccess {
                        Edycja.polaczone[staat.osobaId] = true; Edycja.wersjaOsob++
                        Edycja.komunikat = Taal.s(R.string.popraw_polaczono, staat.imie, naam)
                        nav.terug()
                    }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
}
