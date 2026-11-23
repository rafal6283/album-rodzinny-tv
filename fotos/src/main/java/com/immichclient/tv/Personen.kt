package com.immichclient.tv

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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

/** Które osoby pokazać w sekcji Ludzie. */
enum class KtoPokazac { WSZYSCY, Z_IMIENIEM, BEZ_IMIENIA }

/**
 * Alle personen: raster met gezichten, standaard op hoe vaak iemand voorkomt (de volgorde van Immich: favorieten eerst,
 * dan het aantal foto's), of op naam; zoeken filtert tijdens het typen.
 * Z Szukaj → „Wszyscy ludzie” dostaje gotową listę osób z imieniem; sekcja Ludzie ([laad] != null) wczytuje sama
 * wszystkie rozpoznane twarze, także bez imienia, i ma filtr Wszyscy / Z imieniem / Bez imienia.
 */
class PersonenStaat(start: List<Persoon>, scope: CoroutineScope? = null, laad: (suspend () -> List<Persoon>)? = null) {
    var alle by mutableStateOf(start)
        private set
    /** sekcja Ludzie: lista także osób bez imienia (filtr widoczny) */
    val metNaamloos = laad != null
    var laadt by mutableStateOf(laad != null)
        private set
    var fout by mutableStateOf<String?>(null)
        private set
    var kto by mutableStateOf(KtoPokazac.WSZYSCY)
    var zoek by mutableStateOf("")
    var opNaam by mutableStateOf(false)
    val grid = LazyGridState()
    var laatsteFocus: String? = null

    init {
        if (laad != null && scope != null) scope.launch {
            runCatching { laad() }.onSuccess { alle = it }.onFailure { fout = it.message; android.util.Log.w("Fotos", "ludzie", it) }
            laadt = false
        }
    }

    val aantalMetNaam get() = alle.count { Edycja.widoczna(it) && Edycja.imie(it).isNotBlank() }

    fun zichtbaar(): List<Persoon> {
        val z = zonderAccenten(zoek.trim())
        // zmiany z telewizora: nowe imiona, ukryte i połączone osoby
        val teraz = alle.filter { Edycja.widoczna(it) }.map { p -> Edycja.imiona[p.id]?.let { p.copy(naam = it) } ?: p }
        var l = when (kto) {
            KtoPokazac.WSZYSCY -> teraz
            KtoPokazac.Z_IMIENIEM -> teraz.filter { it.naam.isNotBlank() }
            KtoPokazac.BEZ_IMIENIA -> teraz.filter { it.naam.isBlank() }
        }
        if (z.isNotEmpty()) l = l.filter { p -> zonderAccenten(p.naam).split(' ').any { it.startsWith(z, ignoreCase = true) } || zonderAccenten(p.naam).contains(z, ignoreCase = true) }
        // na nazwie: bez imienia na końcu (w kolejności Immich, czyli wg liczby zdjęć)
        return if (opNaam) l.sortedWith(compareBy<Persoon> { it.naam.isBlank() }.thenBy(java.text.Collator.getInstance(Taal.locale)) { it.naam }) else l
    }
}

fun zonderAccenten(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
fun PersonenScherm(staat: PersonenStaat, nav: Nav) {
    val lijst = remember(staat.alle, staat.zoek, staat.opNaam, staat.kto, Edycja.wersjaOsob) { staat.zichtbaar() }
    val persoon = stringResource(R.string.persoon)
    val bezImienia = stringResource(R.string.persoon_bez_imienia)
    var zoekOpen by remember { mutableStateOf(false) }
    val zoekKnop = remember { FocusRequester() }
    val terug = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(zoekOpen) { if (wasOpen && !zoekOpen) { delay(16); runCatching { zoekKnop.requestFocus() } }; wasOpen = zoekOpen }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = staat.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "kop") {
                Column {
                    val sub = when {
                        staat.laadt -> stringResource(R.string.laden)
                        staat.fout != null -> stringResource(R.string.kaart_niet_geladen, staat.fout.orEmpty())
                        staat.metNaamloos -> stringResource(R.string.ludzie_sub, Taal.getal(staat.alle.count { Edycja.widoczna(it) }), Taal.getal(staat.aantalMetNaam))
                        else -> Taal.getal(lijst.size)
                    } + if (staat.zoek.isNotBlank()) "  ·  “${staat.zoek}”  ·  ${Taal.getal(lijst.size)}" else ""
                    SchermKop(etykieta = stringResource(R.string.etyk_odkrywaj), titel = stringResource(R.string.personen), sub = sub)
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keuze(
                            selected = staat.zoek.isNotBlank(), onClick = { zoekOpen = true }, modifier = Modifier.focusRequester(zoekKnop),
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                            tekst = staat.zoek.ifBlank { stringResource(R.string.zoek_persoon) },
                        )
                        if (staat.metNaamloos) {
                            Keuze(selected = staat.kto == KtoPokazac.WSZYSCY, onClick = { staat.kto = KtoPokazac.WSZYSCY }, tekst = stringResource(R.string.ludzie_wszyscy))
                            Keuze(selected = staat.kto == KtoPokazac.Z_IMIENIEM, onClick = { staat.kto = KtoPokazac.Z_IMIENIEM }, tekst = stringResource(R.string.ludzie_z_imieniem))
                            Keuze(selected = staat.kto == KtoPokazac.BEZ_IMIENIA, onClick = { staat.kto = KtoPokazac.BEZ_IMIENIA }, tekst = stringResource(R.string.ludzie_bez_imienia))
                            Box(Modifier.width(14.dp))
                        }
                        Keuze(selected = !staat.opNaam, onClick = { staat.opNaam = false }, tekst = stringResource(R.string.volg_vaakst))
                        Keuze(selected = staat.opNaam, onClick = { staat.opNaam = true }, tekst = stringResource(R.string.volg_naam))
                    }
                }
            }
            items(lijst, key = { it.id }) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = { nav.open(Scherm.Tijdlijn(p.naam.ifBlank { bezImienia }, persoon, Filter(persoonId = p.id), true, false, nav.scope)) },
                        modifier = (if (p.id == staat.laatsteFocus) Modifier.focusRequester(terug) else Modifier)
                            .fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) staat.laatsteFocus = p.id },
                        shape = CardDefaults.shape(CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.08f),
                    ) { AsyncImage(beeld(Immich.gezicht(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (p.naam.isNotBlank()) Text(p.naam, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    else Text(bezImienia, style = MaterialTheme.typography.labelMedium, color = Kleur.zacht, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        ScrolBalk(
            fractie = { fractieVan(staat.grid, 1) },
            beweging = { staat.grid.firstVisibleItemIndex * 7919 + staat.grid.firstVisibleItemScrollOffset / 200 },
            scrolbaar = { staat.grid.canScrollForward || staat.grid.canScrollBackward },
            label = { lijst.getOrNull((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.naam?.take(1)?.uppercase()?.takeIf { staat.opNaam && it.isNotEmpty() } },
            sub = { "${Taal.getal((staat.grid.firstVisibleItemIndex - 1).coerceAtLeast(0) + 1)} / ${Taal.getal(lijst.size)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    LaunchedEffect(Unit) {
        delay(30)
        if (staat.laatsteFocus == null || runCatching { terug.requestFocus() }.isFailure) runCatching { zoekKnop.requestFocus() }
    }
    if (zoekOpen) TekstInvoer(stringResource(R.string.zoek_persoon), staat.zoek, live = { staat.zoek = it }, actie = stringResource(R.string.actie_klaar), sluit = { zoekOpen = false }) {
        staat.zoek = it; zoekOpen = false
    }
}
