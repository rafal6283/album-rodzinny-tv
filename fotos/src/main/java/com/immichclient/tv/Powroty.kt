package com.immichclient.tv

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Miejsce, do którego wracaliście: miasto i po jednym zdjęciu z każdego roku. */
class Powrot(val miasto: String, val kraj: String?, val lata: List<Pair<Int, Asset>>)

/**
 * Powroty: miasta, w których są zdjęcia z co najmniej dwóch różnych lat (te same wakacje, dom dziadków, góry),
 * pokazane rok obok roku. Kandydaci: miasta z największą liczbą zdjęć na mapie; lata z losowej próbki każdego miasta.
 */
class PowrotyStaat(scope: CoroutineScope) {
    val lista = androidx.compose.runtime.mutableStateListOf<Powrot>()
    var gotowe by mutableStateOf(false)
        private set

    init {
        scope.launch {
            val m = runCatching { Immich.markers() }.onFailure { Log.w("Fotos", "powroty", it) }.getOrNull()
            if (m == null) { gotowe = true; return@launch }
            val miasta = (0 until m.aantal).mapNotNull { i -> m.stad[i]?.let { it to m.land[i] } }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(40).map { it.key }
            for ((miasto, kraj) in miasta) {
                val probka = runCatching { Immich.losoweZMiasta(miasto, 200) }.getOrDefault(emptyList())
                val lata = probka.groupBy { it.datum.year }.toSortedMap().map { (r, l) -> r to l.random() }
                if (lata.size >= 2) {
                    lista.add(Powrot(miasto, kraj, lata))
                    lista.sortByDescending { it.lata.size }
                }
            }
            gotowe = true
        }
    }
}

@Composable
fun PowrotyScherm(staat: PowrotyStaat, nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    PamiecEkranu("powroty")
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize(),
    ) {
        item {
            SchermKop(stringResource(R.string.powroty_tytul),
                if (!staat.gotowe) stringResource(R.string.powroty_szukam, staat.lista.size) else stringResource(R.string.powroty_sub, staat.lista.size),
                etykieta = stringResource(R.string.sectie_zabawa))
            if (!staat.gotowe) Laadpil(true)
            else if (staat.lista.isEmpty()) Text(stringResource(R.string.powroty_brak), color = Kleur.zacht)
        }
        items(staat.lista.toList(), key = { it.miasto + it.kraj }) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(p.miasto, style = MaterialTheme.typography.headlineMedium)
                    Text("   " + listOfNotNull(p.kraj, pluralStringResource(R.plurals.lat, p.lata.size, p.lata.size)).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(bottom = 4.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                    items(p.lata, key = { it.first }) { (rok, a) ->
                        Card(
                            onClick = { nav.open(Scherm.Lijst(p.miasto, rok.toString(), true, nav.scope) { s -> Immich.zoekTekst(null, "", s, null, ZoekFiltr(miasto = p.miasto, rok = rok)) }) },
                            modifier = Modifier.pamiecFokusu("powroty", p.miasto + rok, pierwszy = p === staat.lista.firstOrNull() && rok == p.lata.first().first).width(220.dp).height(150.dp),
                            shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
                            scale = CardDefaults.scale(focusedScale = 1.06f),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                AsyncImage(kaartBeeld(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB0000000)))))
                                Text(rok.toString(), style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Szukaj po kolorze ----------------

/** Kolor z palety: nazwa, kolor do kółka i dopasowanie (odcień albo jasność). */
class KolorWzor(val nazwa: Int, val rgb: Int, val odcien: Float?, val jasny: Boolean? = null)

/**
 * Szukaj po kolorze: średni kolor każdego zdjęcia liczony z jego ThumbHash (bez pobierania obrazów), dla ~4000
 * najnowszych zdjęć z osi czasu; wybór koloru = zdjęcia najbardziej w tym kolorze.
 */
object Kolory {
    val PALETA = listOf(
        KolorWzor(R.string.kolor_czerwony, 0xE53935, 0f), KolorWzor(R.string.kolor_pomaranczowy, 0xFB8C00, 30f),
        KolorWzor(R.string.kolor_zolty, 0xFDD835, 55f), KolorWzor(R.string.kolor_zielony, 0x43A047, 120f),
        KolorWzor(R.string.kolor_turkus, 0x00ACC1, 185f), KolorWzor(R.string.kolor_niebieski, 0x1E88E5, 215f),
        KolorWzor(R.string.kolor_fioletowy, 0x8E24AA, 280f), KolorWzor(R.string.kolor_rozowy, 0xEC407A, 335f),
        KolorWzor(R.string.kolor_jasny, 0xF5F5F5, null, jasny = true), KolorWzor(R.string.kolor_ciemny, 0x212121, null, jasny = false),
    )

    private var kandydaci: List<Pair<Asset, FloatArray>>? = null
    var przeanalizowano by mutableStateOf(0)
        private set
    private val blokada = kotlinx.coroutines.sync.Mutex()

    /** Zdjęcia z kolorem w HSV (h 0..360, s, v 0..1), wczytywane raz (miesiące od najnowszych, do ~4000 zdjęć). */
    private suspend fun kandydaci(): List<Pair<Asset, FloatArray>> = blokada.withLockKolory {
        kandydaci ?: run {
            val wynik = ArrayList<Pair<Asset, FloatArray>>()
            val miesiace = runCatching { Immich.maanden(Filter(), true) }.getOrDefault(emptyList())
            for (m in miesiace) {
                if (wynik.size >= 4000) break
                val l = runCatching { Immich.maand(Filter(), true, m.sleutel) }.getOrDefault(emptyList())
                for (a in l) {
                    if (a.video) continue
                    val c = a.thumbhash?.let { ThumbHash.sredniKolor(it) } ?: continue
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, hsv)
                    wynik.add(a to hsv)
                }
                przeanalizowano = wynik.size
            }
            wynik.also { kandydaci = it }
        }
    }

    /** Zdjęcia najlepiej pasujące do koloru (do 300), od najbardziej pasujących. */
    suspend fun dopasuj(w: KolorWzor): List<Asset> {
        val k = kandydaci()
        val oceny = k.mapNotNull { (a, hsv) ->
            val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
            val ocena = when {
                w.jasny == true -> if (v > 0.75f && s < 0.25f) v - s else null
                w.jasny == false -> if (v < 0.25f) 1f - v else null
                else -> {
                    val d = minOf(kotlin.math.abs(h - w.odcien!!), 360f - kotlin.math.abs(h - w.odcien))
                    if (d < 22f && s > 0.3f && v > 0.25f) s * v - d / 60f else null
                }
            }
            ocena?.let { a to it }
        }
        return oceny.sortedByDescending { it.second }.take(300).map { it.first }
    }
}

private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockKolory(blok: suspend () -> T): T { lock(); try { return blok() } finally { unlock() } }

@Composable
fun KoloryScherm(nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { pierwszy.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
        SchermKop(stringResource(R.string.kolory_tytul), stringResource(R.string.kolory_sub), etykieta = stringResource(R.string.sectie_zabawa))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 20.dp)) {
            Kolory.PALETA.forEachIndexed { i, w ->
                val nazwa = stringResource(w.nazwa)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = {
                            nav.open(Scherm.Lijst(nazwa, Taal.s(R.string.kolory_tytul), false, nav.scope) { s -> if (s == 1) Pagina(Kolory.dopasuj(w), null) else Pagina(emptyList(), null) })
                        },
                        modifier = (if (i == 0) Modifier.focusRequester(pierwszy) else Modifier).size(64.dp),
                        shape = CardDefaults.shape(CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.2f),
                    ) { Box(Modifier.fillMaxSize().background(Color(0xFF000000 or w.rgb.toLong())).border(2.dp, Color(0x66FFFFFF), CircleShape)) }
                    Text(nazwa, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Text(stringResource(R.string.kolory_info), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht, modifier = Modifier.padding(top = 28.dp))
    }
}
