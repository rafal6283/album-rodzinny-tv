package com.immichclient.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sekcja Zabawa: quiz, tryb imprezy, film z podróży, jak rosła…, powroty, szukaj po kolorze. */
@Composable
fun ZabawaScherm(nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    var wybor by remember { mutableStateOf(ZabawaPamiec.wybor.also { ZabawaPamiec.wybor = null }) }
    class Pozycja(val klucz: String, val ikona: ImageVector, val tytul: Int, val opis: Int, val kolor: Color)
    val pozycje = listOf(
        Pozycja("quiz", Icons.Filled.Quiz, R.string.quiz_tytul, R.string.quiz_opis, Color(0xFF5B2A4A)),
        Pozycja("impreza", Icons.Filled.Celebration, R.string.impreza_tytul, R.string.impreza_opis, Color(0xFF5C3418)),
        Pozycja("podroz", Icons.Filled.FlightTakeoff, R.string.podroz_tytul, R.string.podroz_opis, Color(0xFF1E2050)),
        Pozycja("rosla", Icons.Filled.ChildCare, R.string.rosla_tytul, R.string.rosla_opis, Color(0xFF22404A)),
        Pozycja("powroty", Icons.Filled.Replay, R.string.powroty_tytul, R.string.powroty_opis, Color(0xFF2C3E58)),
        Pozycja("kolory", Icons.Filled.Palette, R.string.kolory_tytul, R.string.kolory_opis, Color(0xFF4A2A55)),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SchermKop(stringResource(R.string.sectie_zabawa), stringResource(R.string.zabawa_sub), etykieta = stringResource(R.string.etyk_wspomnienia)) }
        items(pozycje, key = { it.klucz }) { p ->
            Card(
                onClick = {
                    when (p.klucz) {
                        "quiz" -> nav.open(Scherm.Quiz(nav.scope))
                        "impreza" -> nav.open(Scherm.Impreza)
                        "powroty" -> nav.open(Scherm.Powroty(nav.scope))
                        "kolory" -> nav.open(Scherm.Kolory(nav.scope))
                        else -> wybor = p.klucz // podróż: wybór albumu; jak rosła: wybór osoby
                    }
                },
                modifier = Modifier.pamiecFokusu("zabawa", p.klucz, pierwszy = p.klucz == "quiz").fillMaxWidth().height(150.dp),
                shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                scale = CardDefaults.scale(focusedScale = 1.05f),
            ) {
                Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(p.kolor, Color(0xFF181822)))).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(p.ikona, null, Modifier.size(44.dp), tint = Color.White)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(stringResource(p.tytul), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                        Text(stringResource(p.opis), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), maxLines = 3)
                    }
                }
            }
        }
    }
    // powrót do listy wyboru: kafelki Zabawy nie zabierają jej fokusu
    if (wybor == null) PamiecEkranu("zabawa")
    when (wybor) {
        "podroz" -> KiesLijst(stringResource(R.string.podroz_wybierz), albumKeuzes(), "", sluit = { wybor = null }) { id, naam ->
            ZabawaPamiec.wybor = wybor; wybor = null; nav.open(Scherm.Podroz(id, naam, nav.scope))
        }
        "rosla" -> KiesLijst(stringResource(R.string.rosla_wybierz), persoonKeuzes(), "", sluit = { wybor = null }) { id, naam ->
            ZabawaPamiec.wybor = wybor; wybor = null; nav.open(Scherm.Rosniecie(id, naam, nav.scope))
        }
    }
}

// ---------------- Quiz ----------------

/** Pytanie quizu: zdjęcie, rodzaj, 4 odpowiedzi i indeks dobrej. */
class Pytanie(val zdjecie: Asset, val rodzaj: Int, val odpowiedzi: List<String>, val dobra: Int)

/**
 * Rodzinny quiz: 10 pytań z losowych zdjęć — gdzie to było (miasto z GPS), który to rok, kto jest na zdjęciu. Jedna
 * wspólna punktacja (gra cała kanapa), na końcu wynik i pokaz zdjęć z pytań.
 */
class QuizStaat(private val scope: CoroutineScope) {
    var pytania by mutableStateOf<List<Pytanie>?>(null)
        private set
    var nr by mutableIntStateOf(0)
    var wybrana by mutableStateOf<Int?>(null)
    var punkty by mutableIntStateOf(0)
    var blad by mutableStateOf(false)
        private set

    init { nowa() }

    fun nowa() {
        pytania = null; nr = 0; wybrana = null; punkty = 0; blad = false
        scope.launch {
            val probka = runCatching { Immich.losoweZOsobami(150) }.getOrDefault(emptyList())
            val miasta = probka.mapNotNull { it.stad }.distinct()
            val osoby = probka.flatMap { it.osoby }.filter { it.naam.isNotBlank() && !Instellingen.osobaUkryta(it.id) }.distinctBy { it.id }
            val lista = ArrayList<Pytanie>()
            for (a in probka.shuffled()) {
                if (lista.size >= 10) break
                val rodzaje = buildList {
                    if (a.stad != null && miasta.size >= 4) add(0)
                    add(1)
                    if (a.osoby.any { it.naam.isNotBlank() && !Instellingen.osobaUkryta(it.id) } && osoby.size >= 4) add(2)
                }
                // różnorodność: rodzaj, którego było najmniej
                val r = rodzaje.minByOrNull { k -> lista.count { it.rodzaj == k } } ?: continue
                val (dobra, zle) = when (r) {
                    0 -> a.stad!! to miasta.filter { it != a.stad }.shuffled().take(3)
                    1 -> a.datum.year.toString() to (listOf(-3, -2, -1, 1, 2, 3).shuffled().take(3).map { (a.datum.year + it).toString() })
                    else -> {
                        val kto = a.osoby.first { it.naam.isNotBlank() && !Instellingen.osobaUkryta(it.id) }
                        kto.naam to osoby.filter { o -> a.osoby.none { it.id == o.id } }.shuffled().take(3).map { it.naam }
                    }
                }
                if (zle.size < 3) continue
                val odp = (zle + dobra).shuffled()
                lista.add(Pytanie(a, r, odp, odp.indexOf(dobra)))
            }
            if (lista.isEmpty()) blad = true
            pytania = lista
        }
    }

    fun odpowiedz(i: Int) {
        if (wybrana != null) return
        wybrana = i
        if (pytania?.getOrNull(nr)?.dobra == i) punkty++
    }

    fun dalej() { wybrana = null; nr++ }
}

@Composable
fun QuizScherm(staat: QuizStaat, nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    val lista = staat.pytania
    val p = lista?.getOrNull(staat.nr)
    LaunchedEffect(staat.nr, lista, staat.wybrana) { delay(80); runCatching { pierwszy.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 32.dp, top = 20.dp, bottom = 24.dp)) {
        when {
            lista == null -> Column {
                SchermKop(stringResource(R.string.quiz_tytul), stringResource(R.string.laden), etykieta = stringResource(R.string.sectie_zabawa))
                Laadpil(true)
            }
            staat.blad || lista.isEmpty() -> Melding(stringResource(R.string.quiz_brak), null)
            p == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                // koniec gry
                Text(stringResource(R.string.quiz_wynik, staat.punkty, lista.size), style = TytulStyl, color = Color.White)
                Text(stringResource(when { staat.punkty >= 9 -> R.string.quiz_brawo; staat.punkty >= 6 -> R.string.quiz_dobrze; else -> R.string.quiz_jeszcze }),
                    style = MaterialTheme.typography.headlineSmall, color = Kleur.zacht, modifier = Modifier.padding(vertical = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Knop(stringResource(R.string.quiz_jeszcze_raz), null, Modifier.width(240.dp).focusRequester(pierwszy)) { staat.nowa() }
                    Knop(stringResource(R.string.quiz_pokaz), null, Modifier.width(240.dp)) {
                        nav.bekijk(LijstBron(lista.map { it.zdjecie }), 0, stringResource0Quiz(), true, null)
                    }
                }
            }
            else -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                AsyncImage(preview(p.zdjecie.id), null, Modifier.weight(1.4f).fillMaxSize().clip(RoundedCornerShape(20.dp)).background(Kleur.vlak), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.quiz_pytanie_nr, staat.nr + 1, lista.size) + "  ·  " + stringResource(R.string.quiz_punkty, staat.punkty),
                        style = EtykietaStyl)
                    Text(stringResource(when (p.rodzaj) { 0 -> R.string.quiz_gdzie; 1 -> R.string.quiz_kiedy; else -> R.string.quiz_kto }),
                        style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                    p.odpowiedzi.forEachIndexed { i, o ->
                        val w = staat.wybrana
                        val znak = when { w == null -> ""; i == p.dobra -> "✓  "; i == w -> "✗  "; else -> "" }
                        Knop(znak + o, null, (if (i == 0 && w == null) Modifier.focusRequester(pierwszy) else Modifier).fillMaxWidth()) {
                            if (w == null) staat.odpowiedz(i) else staat.dalej()
                        }
                    }
                    staat.wybrana?.let { w ->
                        Text(stringResource(if (w == p.dobra) R.string.quiz_dobra else R.string.quiz_zla, p.odpowiedzi[p.dobra]),
                            style = MaterialTheme.typography.titleLarge, color = if (w == p.dobra) Color(0xFF86EFAC) else Color(0xFFFCA5A5), modifier = Modifier.padding(top = 6.dp))
                        Knop(stringResource(R.string.quiz_dalej), null, Modifier.fillMaxWidth().focusRequester(pierwszy)) { staat.dalej() }
                    }
                }
            }
        }
    }
}

private fun stringResource0Quiz() = Taal.s(R.string.quiz_tytul)

/** Wybór osoby / albumu otwarty przed wejściem do „Jak rosła” / „Podróży”: po Wstecz wracamy do tej listy, nie do Zabawy. */
object ZabawaPamiec { var wybor: String? = null }
