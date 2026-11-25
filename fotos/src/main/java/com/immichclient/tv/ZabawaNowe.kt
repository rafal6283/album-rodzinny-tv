package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Wiek na zdjęciu: „8 miesięcy” do 2 lat, potem „5 lat”. */
fun wiekNaZdjeciu(ur: LocalDate, a: Asset): String {
    val d = a.datum.toLocalDate()
    val lat = ChronoUnit.YEARS.between(ur, d).toInt()
    val mies = ChronoUnit.MONTHS.between(ur, d).toInt().coerceAtLeast(0)
    return if (lat < 2) Taal.ctx.resources.getQuantityString(R.plurals.miesiecy, mies, mies)
    else Taal.ctx.resources.getQuantityString(R.plurals.lat, lat, lat)
}

// ---------------- Kto bardziej podobny? ----------------

/** Runda: zdjęcie dziecka i zdjęcia starszych osób (rodzice, dziadkowie) z tego samego wieku. */
class RundaPodobni(val dziecko: Asset, val wiek: String, val inni: List<Triple<Persoon, Asset, String>>)

/**
 * „Kto bardziej podobny?”: losowe zdjęcie osoby (np. dziecka) i obok zdjęcia starszych członków rodziny z czasu, gdy
 * mieli tyle samo lat. Potrzebne daty urodzenia w Immich (osoby o co najmniej 14 lat starsze, do 4). OK = następna runda.
 */
class PodobniStaat(val osobaId: String, val imie: String, private val scope: CoroutineScope) {
    var runda by mutableStateOf<RundaPodobni?>(null)
        private set
    var laduje by mutableStateOf(true)
        private set
    /** komunikat zamiast rundy (brak daty urodzenia, brak starszych osób z datą, brak zdjęć) */
    var problem by mutableStateOf<Int?>(null)
        private set
    private var urodziny: LocalDate? = null
    private var starsi: List<Persoon> = emptyList()

    init {
        scope.launch {
            urodziny = runCatching { Immich.osoba(osobaId).first.urodziny }.getOrNull()
            val ur = urodziny
            if (ur == null) { problem = R.string.podobni_brak_daty; laduje = false; return@launch }
            starsi = runCatching { Immich.osobyZUrodzinami() }.getOrDefault(emptyList())
                .filter { p -> p.id != osobaId && !Instellingen.osobaUkryta(p.id) && p.urodziny?.let { it.isBefore(ur.minusYears(14)) } == true }
                .sortedByDescending { it.urodziny } // najpierw rodzice, potem dziadkowie
                .take(4)
            if (starsi.isEmpty()) { problem = R.string.podobni_brak_starszych; laduje = false; return@launch }
            nastepna()
        }
    }

    fun nastepna() {
        val ur = urodziny ?: return
        laduje = true
        scope.launch {
            val probka = runCatching { Immich.osobaLosowo(osobaId, ur, LocalDate.now(), 30) }.getOrDefault(emptyList())
            var wynik: RundaPodobni? = null
            // kilka prób: zdjęcie dziecka, do którego ktoś starszy ma zdjęcie z tego samego wieku
            for (d in Pokazane.wybierz(probka, 5) { it.id }) {
                val dni = ChronoUnit.DAYS.between(ur, d.datum.toLocalDate())
                if (dni < 0) continue
                // okno: u niemowląt ±1,5 miesiąca, potem ±6 miesięcy
                val okno = if (dni < 730) 45L else 183L
                val inni = starsi.map { p ->
                    async {
                        val srodek = p.urodziny!!.plusDays(dni)
                        val l = runCatching { Immich.osobaLosowo(p.id, srodek.minusDays(okno), srodek.plusDays(okno), 10) }.getOrDefault(emptyList())
                        Pokazane.wybierz(l, 1) { it.id }.firstOrNull()?.let { a -> Triple(p, a, wiekNaZdjeciu(p.urodziny!!, a)) }
                    }
                }.awaitAll().filterNotNull()
                if (inni.isNotEmpty()) { wynik = RundaPodobni(d, wiekNaZdjeciu(ur, d), inni); break }
            }
            wynik?.let { w -> Pokazane.zapamietaj(listOf(w.dziecko.id) + w.inni.map { it.second.id }) }
            runda = wynik
            problem = if (wynik == null) R.string.podobni_brak_zdjec else null
            laduje = false
        }
    }
}

@Composable
fun PodobniScherm(staat: PodobniStaat) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B12)).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!staat.laduje) staat.nastepna(); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }.padding(horizontal = 40.dp, vertical = 26.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.podobni_tytul).uppercase(Taal.locale), style = EtykietaStyl)
            val r = staat.runda
            Text(r?.let { "${staat.imie} · ${it.wiek}" } ?: staat.imie, style = TytulStyl, color = Color.White, maxLines = 1)
            Text(stringResource(R.string.podobni_hint), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(bottom = 12.dp))
            when {
                r == null && staat.laduje -> Laadpil(true)
                r == null -> Text(stringResource(staat.problem ?: R.string.podobni_brak_zdjec), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                else -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    ZdjecieZPodpisem(r.dziecko, staat.imie, r.wiek, Modifier.weight(1.25f), wyroznione = true)
                    // starsi: 1–2 obok siebie, 3–4 w dwóch rzędach
                    Column(Modifier.weight(if (r.inni.size > 2) 1.25f else r.inni.size * 0.9f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        r.inni.chunked(if (r.inni.size > 2) 2 else r.inni.size).forEach { rzad ->
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                rzad.forEach { (p, a, w) -> ZdjecieZPodpisem(a, p.naam, w, Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        if (staat.laduje && staat.runda != null) Box(Modifier.align(Alignment.TopEnd)) { Laadpil(true) }
    }
}

@Composable
private fun ZdjecieZPodpisem(a: Asset, kto: String, podpis: String, modifier: Modifier, wyroznione: Boolean = false) {
    Column(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Kleur.vlak)) {
            AsyncImage(preview(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Text(kto, style = if (wyroznione) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            color = if (wyroznione) Color(0xFFF5B83D) else Color.White, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(podpis + "  ·  " + a.datum.toLocalDate().format(Formaat.dagJaar), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht, maxLines = 1)
    }
}

// ---------------- Ten sam kadr ----------------

/** Para zdjęć z tego samego miejsca (te same ~70 m), z różnych lat. */
class ParaKadru(val stare: Asset, val nowe: Asset, val miejsce: String?)

/**
 * „Ten sam kadr”: zdjęcia z mapy pogrupowane w kratki ~70 m; z kratek, gdzie zdjęć jest kilka, losujemy próbkę i
 * szukamy dwóch z różnych lat (najlepiej odległych). Pokazane obok siebie: wtedy i teraz w tym samym miejscu.
 */
class KadrStaat(private val scope: CoroutineScope) {
    var para by mutableStateOf<ParaKadru?>(null)
        private set
    var laduje by mutableStateOf(true)
        private set
    var brak by mutableStateOf(false)
        private set
    private var kratki: ArrayDeque<List<Int>>? = null
    private var markery: Markers? = null
    private var nastepnaPara: kotlinx.coroutines.Deferred<ParaKadru?>? = null

    init { dalej() }

    private suspend fun przygotuj() {
        if (kratki != null) return
        val m = runCatching { Immich.markers() }.onFailure { Log.w("Fotos", "ten sam kadr", it) }.getOrNull()
        markery = m
        if (m == null) { kratki = ArrayDeque(); return }
        val grupy = HashMap<Long, MutableList<Int>>()
        for (i in 0 until m.aantal) {
            val klucz = (Math.round(m.lat[i] / 0.0006) shl 32) xor (Math.round(m.lon[i] / 0.0009) and 0xffffffffL)
            grupy.getOrPut(klucz) { ArrayList() }.add(i)
        }
        kratki = ArrayDeque(grupy.values.filter { it.size >= 4 }.shuffled())
    }

    private suspend fun szukaj(): ParaKadru? {
        przygotuj()
        val k = kratki ?: return null
        val m = markery ?: return null
        repeat(12) {
            val kratka = k.removeFirstOrNull() ?: return null
            val ids = kratka.shuffled().take(10).map { m.id[it] }
            val zdjecia = kotlinx.coroutines.coroutineScope {
                ids.map { id -> async { runCatching { Immich.asset(id) }.getOrNull() } }.awaitAll()
            }.filterNotNull().filter { !it.video }
            val lata = zdjecia.groupBy { it.datum.year }
            if (lata.size >= 2) {
                val stary = lata.keys.min(); val nowy = lata.keys.max()
                val s = Pokazane.wybierz(lata.getValue(stary), 1) { it.id }.first()
                val n = Pokazane.wybierz(lata.getValue(nowy), 1) { it.id }.first()
                Pokazane.zapamietaj(listOf(s.id, n.id))
                return ParaKadru(s, n, m.stad[kratka.first()]?.let { st -> listOfNotNull(st, m.land[kratka.first()]).joinToString(", ") })
            }
        }
        return null
    }

    fun dalej() {
        laduje = true
        scope.launch {
            val p = (nastepnaPara ?: scope.async { szukaj() }).await()
            nastepnaPara = null
            para = p ?: para
            brak = p == null && para == null
            laduje = false
            // następna para w tle, żeby OK działało od razu
            if (p != null) nastepnaPara = scope.async { szukaj() }
        }
    }
}

@Composable
fun KadrScherm(staat: KadrStaat) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xFF0B0B12)).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!staat.laduje) staat.dalej(); true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        }.padding(horizontal = 40.dp, vertical = 26.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.kadr_tytul).uppercase(Taal.locale), style = EtykietaStyl)
            val p = staat.para
            Text(p?.miejsce ?: stringResource(R.string.kadr_tytul), style = TytulStyl, color = Color.White, maxLines = 1)
            Text(stringResource(R.string.kadr_hint), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(bottom = 12.dp))
            when {
                p == null && staat.brak -> Text(stringResource(R.string.kadr_brak), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, textAlign = TextAlign.Start)
                p == null -> Laadpil(true)
                else -> Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    listOf(p.stare, p.nowe).forEach { a ->
                        Column(Modifier.weight(1f).fillMaxSize()) {
                            Text(a.datum.year.toString(), style = MaterialTheme.typography.headlineMedium, color = Color(0xFFF5B83D))
                            Text(a.datum.toLocalDate().format(Formaat.dagJaar), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht)
                            Box(Modifier.padding(top = 8.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(18.dp)).background(Kleur.vlak)) {
                                AsyncImage(preview(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            }
                        }
                    }
                }
            }
        }
        if (staat.laduje && staat.para != null) Box(Modifier.align(Alignment.TopEnd)) { Laadpil(true) }
    }
}
