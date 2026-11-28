package com.immichclient.tv

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import java.time.Instant

/** Sekcja Porządki: przegląd nowych, duplikaty, kosz, archiwum, najlepsze (4–5 gwiazdek). */
@Composable
fun PorzadkiScherm(nav: Nav) {
    class Poz(val klucz: String, val ikona: ImageVector, val tytul: Int, val opis: Int, val kolor: Color)
    val pozycje = listOf(
        Poz("przeglad", Icons.Filled.FiberNew, R.string.przeglad_tytul, R.string.przeglad_opis, Color(0xFF1F4A3A)),
        Poz("przedur", Icons.Filled.ChildCare, R.string.przed_ur_tytul, R.string.przed_ur_opis, Color(0xFF4A3A1F)),
        Poz("duplikaty", Icons.Filled.ContentCopy, R.string.duplikaty_tytul, R.string.duplikaty_opis, Color(0xFF3E3358)),
        Poz("kosz", Icons.Filled.Delete, R.string.kosz_tytul, R.string.kosz_opis, Color(0xFF5A2230)),
        Poz("archiwum", Icons.Filled.Archive, R.string.archiwum_tytul, R.string.archiwum_opis, Color(0xFF2C3E58)),
        Poz("gwiazdki", Icons.Filled.Star, R.string.gwiazdki_tytul, R.string.gwiazdki_opis, Color(0xFF5C3418)),
    )
    PamiecEkranu("porzadki")
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SchermKop(stringResource(R.string.sectie_porzadki), stringResource(R.string.porzadki_sub)) }
        items(pozycje, key = { it.klucz }) { p ->
            Card(
                onClick = {
                    when (p.klucz) {
                        "przeglad" -> nav.open(Scherm.Przeglad(nav.scope))
                        "duplikaty" -> nav.open(Scherm.Duplikaty(nav.scope))
                        "przedur" -> nav.open(Scherm.PrzedUrodzeniem(nav.scope))
                        "kosz" -> nav.open(Scherm.Kosz(nav.scope))
                        "archiwum" -> nav.open(Scherm.Lijst(Taal.s(R.string.archiwum_tytul), Taal.s(R.string.archiwum_sub), true, nav.scope) { s -> Immich.archiwum(s) })
                        "gwiazdki" -> nav.open(Scherm.Lijst(Taal.s(R.string.gwiazdki_tytul), null, true, nav.scope, losowyPokaz = true) { s -> Immich.zOcena(4, s) })
                    }
                },
                modifier = Modifier.pamiecFokusu("porzadki", p.klucz, pierwszy = p.klucz == "przeglad").fillMaxWidth().height(150.dp),
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
}

// ---------------- Przegląd nowych ----------------

/**
 * Przegląd nowych: zdjęcia dodane do Immich od ostatniego przeglądu (pierwszy raz: z 30 dni), jedno po drugim.
 * ▲ ulubione, ▼ do kosza, ◀ ▶ dalej, OK do albumu, przytrzymanie OK = menu (data, miejsce, opis…). Przejrzane
 * zapamiętywane na telewizorze; po dojściu do końca następny przegląd zaczyna się od teraz.
 */
class PrzegladStaat(private val scope: CoroutineScope) {
    val lista = mutableStateListOf<Asset>()
    var nr by mutableIntStateOf(0)
    var gotowe by mutableStateOf(false)
        private set
    var blad by mutableStateOf<String?>(null)
        private set
    private val prefs get() = Taal.ctx.getSharedPreferences("przeglad", Context.MODE_PRIVATE)
    private val przejrzane: MutableSet<String> = runCatching { prefs.getStringSet("przejrzane", emptySet()).orEmpty().toMutableSet() }.getOrDefault(HashSet())
    private val start = Instant.now()

    init {
        scope.launch {
            val od = prefs.getLong("od", 0L).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: Instant.now().minusSeconds(30L * 24 * 3600)
            var s = 1
            while (lista.size < 1500) {
                val p = runCatching { Immich.noweOd(od, s) }.onFailure { blad = Edycja.blad(it) }.getOrNull() ?: break
                lista.addAll(p.assets.filter { it.id !in przejrzane })
                s = p.volgende ?: break
            }
            gotowe = true
        }
    }

    val biezace get() = lista.getOrNull(nr)

    fun oznacz(a: Asset) {
        przejrzane += a.id
        runCatching { prefs.edit().putStringSet("przejrzane", HashSet(przejrzane)).apply() }
    }

    fun dalej(kier: Int) {
        biezace?.let { oznacz(it) }
        val n = nr + kier
        if (n >= lista.size && gotowe) koniec()
        nr = n.coerceIn(0, (lista.size - 1).coerceAtLeast(0))
    }

    /** cały przegląd zrobiony: następny od teraz, lista przejrzanych od nowa */
    private fun koniec() {
        runCatching { prefs.edit().putLong("od", start.toEpochMilli()).putStringSet("przejrzane", emptySet()).apply() }
        przejrzane.clear()
        Edycja.komunikat = Taal.s(R.string.przeglad_koniec)
    }

    fun doKosza() {
        val a = biezace ?: return
        oznacz(a)
        lista.removeAt(nr)
        if (nr >= lista.size) nr = (lista.size - 1).coerceAtLeast(0)
        Edycja.zakres.launch {
            runCatching { Immich.doKosza(listOf(a.id)) }
                .onSuccess { Edycja.komunikat = Taal.s(R.string.przeglad_do_kosza) }
                .onFailure { Edycja.komunikat = Edycja.blad(it); lista.add(nr.coerceAtMost(lista.size), a) }
        }
        if (lista.isEmpty() && gotowe) koniec()
    }
}

@Composable
fun PrzegladScherm(staat: PrzegladStaat, nav: Nav) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    val a = staat.biezace
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr)
            .langeOk { staat.biezace?.let { Podreczne.zdjecie(it) } }
            .focusable().onPreviewKeyEvent { e ->
                // OK przy puszczeniu: przytrzymanie (menu) połyka puszczenie, więc nie otworzy też wyboru albumu
                val ok = e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.key.nativeKeyCode == KeyEvent.KEYCODE_ENTER
                if (ok) { if (e.type == KeyEventType.KeyUp) staat.biezace?.let { Podreczne.doAlbumu = listOf(it.id) }; return@onPreviewKeyEvent true }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.dalej(1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { staat.dalej(-1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { staat.biezace?.let { x -> Edycja.zakres.launch { Ulubione.przelacz(x)?.let { Edycja.komunikat = Taal.s(it) } } }; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { staat.doKosza(); true }
                    else -> false
                }
            },
    ) {
        when {
            a != null -> AsyncImage(preview(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            !staat.gotowe -> Box(Modifier.align(Alignment.Center)) { Laadpil(true) }
            else -> Text(staat.blad ?: stringResource(R.string.przeglad_pusto), style = MaterialTheme.typography.headlineSmall, color = Kleur.zacht, modifier = Modifier.align(Alignment.Center))
        }
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0xC0000000), Color.Transparent))))
        Column(Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 24.dp)) {
            Text(stringResource(R.string.przeglad_tytul).uppercase(Taal.locale), style = EtykietaStyl)
            Text(
                listOfNotNull(if (staat.lista.isNotEmpty()) "${staat.nr + 1} / ${staat.lista.size}" + (if (!staat.gotowe) "+" else "") else null,
                    a?.datum?.toLocalDate()?.format(Formaat.dagJaar), a?.stad).joinToString("  ·  "),
                style = MaterialTheme.typography.titleLarge, color = Color.White,
            )
        }
        if (a != null && Ulubione.jest(a)) Icon(Icons.Filled.Favorite, null, Modifier.align(Alignment.TopEnd).padding(28.dp).size(34.dp), tint = Color(0xFFF87171))
        Text(stringResource(R.string.przeglad_hint), style = MaterialTheme.typography.labelLarge, color = Color(0xCCFFFFFF),
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

// ---------------- Duplikaty ----------------

/** Grupy podobnych zdjęć wykrytych przez Immich; jedna grupa na ekranie, OK = zostaw to, resztę do kosza. */
class DuplikatyStaat(private val scope: CoroutineScope) {
    val grupy = mutableStateListOf<Pair<String, List<Asset>>>()
    var nr by mutableIntStateOf(0)
    var gotowe by mutableStateOf(false)
        private set
    var blad by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            runCatching { Immich.duplikaty() }.onSuccess { grupy.addAll(it) }.onFailure { blad = Edycja.blad(it) }
            gotowe = true
        }
    }

    private fun nastepna() { if (nr < grupy.size) grupy.removeAt(nr); if (nr >= grupy.size) nr = (grupy.size - 1).coerceAtLeast(0) }

    fun zostaw(a: Asset) {
        val g = grupy.getOrNull(nr) ?: return
        val reszta = g.second.filter { it.id != a.id }.map { it.id }
        nastepna()
        Edycja.zakres.launch {
            runCatching { Immich.doKosza(reszta); runCatching { Immich.odrzucDuplikat(g.first) } }
                .onSuccess { Edycja.komunikat = Taal.s(R.string.duplikaty_usunieto, reszta.size) }
                .onFailure { Edycja.komunikat = Edycja.blad(it) }
        }
    }

    fun toNieDuplikaty() {
        val g = grupy.getOrNull(nr) ?: return
        nastepna()
        Edycja.zakres.launch { runCatching { Immich.odrzucDuplikat(g.first) }.onFailure { Edycja.komunikat = Edycja.blad(it) } }
    }

    fun pomin() { if (grupy.isNotEmpty()) nr = (nr + 1) % grupy.size }
}

@Composable
fun DuplikatyScherm(staat: DuplikatyStaat) {
    val pierwszy = remember { FocusRequester() }
    val g = staat.grupy.getOrNull(staat.nr)
    LaunchedEffect(g?.first) { delay(80); runCatching { pierwszy.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 32.dp, top = 20.dp, bottom = 24.dp)) {
        SchermKop(
            stringResource(R.string.duplikaty_tytul),
            when {
                !staat.gotowe -> stringResource(R.string.laden)
                g == null -> staat.blad ?: stringResource(R.string.duplikaty_brak)
                else -> stringResource(R.string.duplikaty_grupa, staat.nr + 1, staat.grupy.size) + "  ·  " + stringResource(R.string.duplikaty_hint)
            },
            etykieta = stringResource(R.string.sectie_porzadki),
        ) {
            if (g != null) {
                Keuze(selected = false, onClick = { staat.toNieDuplikaty() }, tekst = stringResource(R.string.duplikaty_nie))
                Keuze(selected = false, onClick = { staat.pomin() }, tekst = stringResource(R.string.duplikaty_pomin))
            }
        }
        if (!staat.gotowe) Laadpil(true)
        if (g != null) Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            g.second.take(4).forEachIndexed { i, a ->
                Column(Modifier.weight(1f).fillMaxSize()) {
                    Card(
                        onClick = { staat.zostaw(a) },
                        modifier = (if (i == 0) Modifier.focusRequester(pierwszy) else Modifier).fillMaxWidth().weight(1f),
                        shape = CardDefaults.shape(RoundedCornerShape(18.dp)), scale = CardDefaults.scale(focusedScale = 1.04f),
                        border = CardDefaults.border(focusedBorder = androidx.tv.material3.Border(BorderStroke(4.dp, Color(0xFF86EFAC)), shape = RoundedCornerShape(18.dp))),
                    ) {
                        Box(Modifier.fillMaxSize().background(Kleur.vlak)) {
                            AsyncImage(preview(a.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                    }
                    Text(a.datum.toLocalDate().format(Formaat.dagJaar) + (a.stad?.let { "  ·  $it" } ?: "") + (if (a.favoriet) "  ·  ♥" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// ---------------- Kosz ----------------

@Composable
fun KoszScherm(scherm: Scherm.Kosz, nav: Nav) {
    val res = scherm.res
    var potwierdz by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rijen = remember(res.assets, maxWidth) { rijenVoor(res.assets, breedteVoor(maxWidth.value), perDag = false, hoogte = Instellingen.rijMaat.hoogte) }
        FotoRaster(
            rijen = rijen, staat = res.raster,
            // OK na zdjęciu w koszu: przywróć albo obejrzyj
            onKlik = { a ->
                Podreczne.menu = a.datum.toLocalDate().format(Formaat.dagJaar) to listOf(
                    Podreczne.Pozycja(Taal.s(R.string.kosz_przywroc)) {
                        Edycja.zakres.launch {
                            runCatching { Immich.przywrocZKosza(listOf(a.id)) }
                                .onSuccess { res.assets = res.assets.filter { it.id != a.id }; Edycja.komunikat = Taal.s(R.string.kosz_przywrocono, 1) }
                                .onFailure { Edycja.komunikat = Edycja.blad(it) }
                        }
                    },
                    Podreczne.Pozycja(Taal.s(R.string.kosz_obejrzyj)) { nav.bekijk(LijstBron(res.assets), res.assets.indexOf(a), Taal.s(R.string.kosz_tytul), false, res.raster) },
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                SchermKop(stringResource(R.string.kosz_tytul), if (res.assets.isEmpty() && !res.bezig) stringResource(R.string.kosz_pusty) else pluralStringResource0(res.assets.size) + "  ·  " + stringResource(R.string.kosz_sub), etykieta = stringResource(R.string.sectie_porzadki)) {
                    if (res.assets.isNotEmpty()) {
                        Keuze(selected = false, onClick = {
                            val ids = res.assets.map { it.id }
                            Edycja.zakres.launch {
                                runCatching { Immich.przywrocZKosza(ids) }
                                    .onSuccess { res.assets = emptyList(); Edycja.komunikat = Taal.s(R.string.kosz_przywrocono, ids.size) }
                                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
                            }
                        }, tekst = stringResource(R.string.kosz_przywroc_wszystkie))
                        Keuze(selected = false, onClick = { potwierdz = true }, tekst = stringResource(R.string.kosz_oproznij))
                    }
                }
            }
        }
    }
    if (potwierdz) MenuWarstwa(stringResource(R.string.kosz_oproznij_pytanie), stringResource(R.string.kosz_oproznij_info), { potwierdz = false }) { pierwszy ->
        Knop(stringResource(R.string.anuluj), null, pierwszy.width(560.dp)) { potwierdz = false }
        Knop(stringResource(R.string.kosz_oproznij_tak), null, Modifier.width(560.dp)) {
            potwierdz = false
            Edycja.zakres.launch {
                runCatching { Immich.oproznijKosz() }
                    .onSuccess { res.assets = emptyList(); Edycja.komunikat = Taal.s(R.string.kosz_oprozniono) }
                    .onFailure { Edycja.komunikat = Edycja.blad(it) }
            }
        }
    }
}

private fun pluralStringResource0(n: Int) = Taal.ctx.resources.getQuantityString(R.plurals.items, n, n)
