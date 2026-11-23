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
                    modifier = Modifier.width(250.dp).height(140.dp),
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

/** „Tego dnia” jako pokaz na start (Ustawienia): raz na uruchomienie aplikacji. */
object TegoDniaStart {
    @Volatile var pokazano = false

    suspend fun zasoby(): List<Asset> = runCatching { Immich.herinneringen(LocalDate.now()).flatMap { it.assets } }
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
    val teraz by produceState<Asset?>(null, osobaId) { value = runCatching { Immich.osobaWOkresie(osobaId, null, null, 1).firstOrNull() }.getOrNull() }
    val wtedy by produceState<Pair<Boolean, Asset?>>(false to null, osobaId, lat) {
        value = false to null
        val srodek = dzis.minusYears(lat.toLong())
        val a = runCatching {
            Immich.osobaWOkresie(osobaId, srodek.minusDays(45), srodek.plusDays(45), 1).firstOrNull()
                ?: Immich.osobaWOkresie(osobaId, LocalDate.of(srodek.year, 1, 1), LocalDate.of(srodek.year, 12, 31), 1).firstOrNull()
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
