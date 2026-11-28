package com.immichclient.tv

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Klatka animacji: zdjęcie i ramka twarzy osoby (ułamki x1, y1, x2, y2); null = twarzy nie udało się odczytać. */
class Klatka(val zdjecie: Asset, val twarz: FloatArray?)

/**
 * „Jak rosła Ola”: zdjęcia osoby z każdego roku (do 3 na rok, losowo z różnych miesięcy, najpierw jeszcze niepokazane), wykadrowane tak, że twarz jest zawsze
 * w środku ekranu i tej samej wielkości; podpis = wiek (gdy w Immich jest data urodzenia) albo rok. Klatki dochodzą
 * w trakcie wczytywania, animacja startuje od razu.
 */
class RosniecieStaat(val osobaId: String, val imie: String, scope: CoroutineScope) {
    val klatki = mutableStateListOf<Klatka>()
    var urodziny by mutableStateOf<LocalDate?>(null)
        private set
    var gotowe by mutableStateOf(false)
        private set
    var nr by mutableIntStateOf(0)
    var pauza by mutableStateOf(false)
    /** diagnostyka: ile zdjęć osoby znaleziono i w ilu odczytano twarz */
    var zdjec by mutableIntStateOf(0)
        private set
    var twarzy by mutableIntStateOf(0)
        private set
    var blad by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            urodziny = runCatching { Immich.osoba(osobaId).first.urodziny }.getOrNull()
            val najstarsze = runCatching { Immich.osobaWOkresie(osobaId, null, null, 1, rosnaco = true).firstOrNull() }
                .onFailure { blad = it.message; android.util.Log.w("Fotos", "jak rosła", it) }.getOrNull()
            val odRoku = maxOf(najstarsze?.datum?.year ?: LocalDate.now().year, urodziny?.year ?: 0)
            for (r in odRoku..LocalDate.now().year) {
                // losowa próbka z całego roku (nie pierwsze zdjęcia od stycznia), za każdym razem inna
                val zRoku = runCatching { Immich.osobaLosowo(osobaId, LocalDate.of(r, 1, 1), LocalDate.of(r, 12, 31), 40) }
                    .recoverCatching { Immich.osobaWOkresie(osobaId, LocalDate.of(r, 1, 1), LocalDate.of(r, 12, 31), 60, rosnaco = true) }
                    .onFailure { blad = it.message }.getOrDefault(emptyList())
                    .filter { !it.przedUrodzeniem(urodziny) } // sprzed urodzenia = błędne rozpoznanie, nie psuje filmu
                if (zRoku.isEmpty()) continue
                zdjec += zRoku.size
                // kandydaci: po jednym z różnych miesięcy (najpierw jeszcze niepokazane), do 7 na rok
                val kandydaci = zRoku.groupBy { it.datum.monthValue }.values
                    .map { m -> Pokazane.wybierz(m, 1) { it.id }.first() }
                    .let { Pokazane.wybierz(it, 7) { a -> a.id } }
                val zTwarza = kandydaci.map { it to Immich.twarzOsoby(it.id, osobaId) }
                twarzy += zTwarza.count { it.second != null }
                // dobre = twarz wyraźna; z nich losowo do 3 (różne miesiące), w kolejności dat
                val dobre = Pokazane.wybierz(zTwarza.filter { (_, t) -> t != null && t[3] - t[1] >= 0.03f }, 3) { it.first.id }
                    .sortedBy { it.first.datum }
                val wybrane = if (dobre.isNotEmpty()) dobre.map { (a, t) -> Klatka(a, t) }
                    // twarzy nie da się odczytać (np. serwer ich nie podaje): losowe zdjęcie i tak trafia do animacji, bez wyrównania
                    else listOf(Klatka(Pokazane.wybierz(zRoku, 1) { it.id }.first(), null))
                klatki.addAll(wybrane)
                Pokazane.zapamietaj(wybrane.map { it.zdjecie.id })
            }
            gotowe = true
        }
    }

    fun podpis(a: Asset): String {
        val ur = urodziny ?: return a.datum.year.toString()
        val lat = ChronoUnit.YEARS.between(ur, a.datum.toLocalDate()).toInt()
        val mies = ChronoUnit.MONTHS.between(ur, a.datum.toLocalDate()).toInt()
        return if (lat < 2) Taal.ctx.resources.getQuantityString(R.plurals.miesiecy, mies.coerceAtLeast(0), mies.coerceAtLeast(0))
        else Taal.ctx.resources.getQuantityString(R.plurals.lat, lat, lat)
    }
}

@Composable
fun RosniecieScherm(staat: RosniecieStaat) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    val n = staat.klatki.size
    LaunchedEffect(staat.nr, n, staat.pauza) {
        if (staat.pauza || n < 2) return@LaunchedEffect
        delay(1400)
        staat.nr = (staat.nr + 1) % n
    }
    MuzykaPokazu(grac = n > 1 && !staat.pauza)
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { staat.pauza = !staat.pauza; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (n > 0) { staat.pauza = true; staat.nr = (staat.nr + 1) % n }; true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (n > 0) { staat.pauza = true; staat.nr = (staat.nr - 1 + n) % n }; true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        },
    ) {
        val szer = constraints.maxWidth.toFloat(); val wys = constraints.maxHeight.toFloat()
        val k = staat.klatki.getOrNull(staat.nr.coerceAtMost((n - 1).coerceAtLeast(0)))
        AnimatedContent(k, transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(450)) }, label = "rosniecie") { kl ->
            if (kl != null && kl.twarz == null) {
                AsyncImage(preview(kl.zdjecie.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else if (kl != null) {
                // twarz = 42 % wysokości ekranu, jej środek w środku ekranu (lekko powyżej, jak w portrecie)
                val t = kl.twarz!!
                val fh = (t[3] - t[1]).coerceAtLeast(0.02f)
                val hObrazu = 0.42f * wys / fh
                val wObrazu = hObrazu * kl.zdjecie.ratio.coerceIn(0.2f, 5f)
                val cx = (t[0] + t[2]) / 2 * wObrazu; val cy = (t[1] + t[3]) / 2 * hObrazu
                AsyncImage(
                    preview(kl.zdjecie.id), null,
                    Modifier.layout { m, _ ->
                        val p = m.measure(Constraints.fixed(wObrazu.roundToInt(), hObrazu.roundToInt()))
                        layout(szer.roundToInt(), wys.roundToInt()) { p.place((szer / 2 - cx).roundToInt(), (wys * 0.45f - cy).roundToInt()) }
                    },
                    contentScale = ContentScale.FillBounds,
                )
            } else Box(Modifier.fillMaxSize())
        }
        // dolny pasek: imię, wiek / rok, postęp
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD0000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp)) {
            Text(stringResource(R.string.rosla_nazwa, staat.imie).uppercase(Taal.locale), style = EtykietaStyl)
            Text(k?.let { staat.podpis(it.zdjecie) } ?: if (staat.gotowe) stringResource(R.string.rosla_brak) else stringResource(R.string.laden),
                style = TytulStyl, color = Color.White)
            // gdy nic nie wyszło: co dokładnie się udało (do zgłoszenia problemu)
            if (staat.gotowe && n == 0 || (staat.gotowe && staat.twarzy == 0 && n > 0)) Text(
                stringResource(R.string.rosla_diagnoza, staat.zdjec, staat.twarzy) + (staat.blad?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Kleur.zacht,
            )
            Text(
                listOfNotNull(k?.zdjecie?.datum?.toLocalDate()?.format(Formaat.dagJaar), if (n > 0) "${staat.nr + 1} / $n" + (if (!staat.gotowe) "+" else "") else null,
                    if (staat.pauza) "⏸" else null).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht,
            )
        }
        Text(stringResource(R.string.rosla_hint), style = MaterialTheme.typography.labelLarge, color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.BottomEnd).padding(28.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
