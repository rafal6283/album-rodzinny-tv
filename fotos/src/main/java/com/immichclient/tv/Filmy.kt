package com.immichclient.tv

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Sekcja Filmy: wszystkie filmy z osi czasu, od najnowszych lub najstarszych, z przejściem do miesiąca i trybem losowym. */
class FilmyStaat(val scope: CoroutineScope) {
    val res = Resultaten(scope)
    var nieuwsteEerst by mutableStateOf(Instellingen.nieuwsteEerst)
        private set
    /** od którego miesiąca (null = od początku listy) */
    var odMiesiaca by mutableStateOf<YearMonth?>(null)
        private set

    init { laduj() }

    private fun laduj() {
        val n = nieuwsteEerst; val od = odMiesiaca
        res.raster.laatsteFocus = null
        res.start { p -> Immich.filmy(p, n, od) }
    }

    fun odwroc() { nieuwsteEerst = !nieuwsteEerst; laduj() }
    fun idzDo(m: YearMonth?) { odMiesiaca = m; laduj() }
}

@Composable
fun FilmyScherm(staat: FilmyStaat, nav: Nav) {
    val res = staat.res
    val tytul = stringResource(R.string.sectie_filmy)
    val scope = rememberCoroutineScope()
    var wybor by remember { mutableStateOf<String?>(null) }
    var rok by remember { mutableStateOf<Int?>(null) }
    val pierwszy = remember { FocusRequester() }
    val datumKnop = remember { FocusRequester() }
    var byl by remember { mutableStateOf(false) }
    LaunchedEffect(wybor) { if (byl && wybor == null && res.raster.focusDoel == null) { delay(16); runCatching { datumKnop.requestFocus() } }; byl = wybor != null }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(res.assets, breedte, rijHoogte) { rijenVoor(res.assets, breedte, perDag = true, hoogte = rijHoogte) }
        val bron = remember(res.assets) { LijstBron(res.assets) }
        LaunchedEffect(rijen) {
            snapshotFlow { res.raster.lijst.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rijen.isNotEmpty()) res.meer() }
        }
        LaunchedEffect(rijen.isNotEmpty()) {
            if (rijen.isNotEmpty() && res.raster.laatsteFocus == null) res.raster.focusDoel = res.assets.firstOrNull()?.id
        }
        FotoRaster(
            rijen = rijen, staat = res.raster, bovenAantal = 1,
            onKlik = { a -> nav.bekijk(bron, res.assets.indexOf(a), tytul, false, res.raster) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                val sub = listOfNotNull(
                    staat.odMiesiaca?.let { stringResource(R.string.filmy_od, maandTitel(it.atDay(1).toString())) } ?: stringResource(R.string.filmy_sub),
                    if (res.assets.isNotEmpty()) pluralStringResource(R.plurals.items, res.assets.size, res.assets.size) + (if (res.bezig) "+" else "") else null,
                ).joinToString("  ·  ")
                SchermKop(tytul, sub, etykieta = stringResource(R.string.etyk_biblioteka)) {
                    Keuze(
                        selected = false, onClick = { staat.odwroc() }, modifier = Modifier.focusRequester(pierwszy),
                        leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.width(18.dp)) },
                        tekst = stringResource(if (staat.nieuwsteEerst) R.string.nieuwste_eerst else R.string.oudste_eerst),
                    )
                    Keuze(
                        selected = staat.odMiesiaca != null, onClick = { rok = null; wybor = "rok" }, modifier = Modifier.focusRequester(datumKnop),
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                        tekst = stringResource(R.string.ga_naar_datum),
                    )
                    val tytulLosowo = stringResource(R.string.losowo)
                    var ladowanie by remember { mutableStateOf(false) }
                    Keuze(
                        selected = ladowanie,
                        onClick = {
                            if (!ladowanie) scope.launch {
                                ladowanie = true
                                val b = LosowaBron(nav.scope, ulubione = false, tylkoFilmy = true)
                                b.doladuj()
                                ladowanie = false
                                if (b.aantal > 0) nav.bekijk(b, 0, tytulLosowo, true, null)
                            }
                        },
                        leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.width(18.dp)) },
                        tekst = tytulLosowo,
                    )
                }
                if (res.fout != null && res.assets.isEmpty()) Text(stringResource(R.string.laden_mislukt, res.fout.orEmpty()), color = Kleur.zacht)
            }
        }
        LijstBalk(res.raster, rijen, res.assets.size, res.bezig, Modifier.align(Alignment.CenterEnd))
    }
    LaunchedEffect(Unit) { delay(400); if (res.assets.isEmpty()) runCatching { pierwszy.requestFocus() } }

    // przejście do daty: najpierw rok, potem miesiąc
    when (wybor) {
        "rok" -> {
            val teraz = LocalDate.now().year
            KiesLijst(
                stringResource(R.string.ga_naar_jaar),
                listOf(KiesItem("", stringResource(R.string.filmy_od_poczatku), null, null)) + (teraz downTo 1990).map { KiesItem(it.toString(), it.toString(), null, null) },
                staat.odMiesiaca?.year?.toString().orEmpty(), sluit = { wybor = null },
            ) { id, _ ->
                val r = id.toIntOrNull()
                if (r == null) { wybor = null; staat.idzDo(null) } else { rok = r; wybor = "miesiac" }
            }
        }
        "miesiac" -> {
            val r = rok ?: return
            val f = java.time.format.DateTimeFormatter.ofPattern("LLLL", Taal.locale)
            KiesLijst(
                r.toString(),
                (1..12).map { m -> KiesItem(m.toString(), YearMonth.of(r, m).atDay(1).format(f).replaceFirstChar { it.titlecase(Taal.locale) }, null, null) },
                staat.odMiesiaca?.takeIf { it.year == r }?.monthValue?.toString().orEmpty(), sluit = { wybor = "rok" },
            ) { id, _ -> wybor = null; staat.idzDo(YearMonth.of(r, id.toInt())) }
        }
    }
}
