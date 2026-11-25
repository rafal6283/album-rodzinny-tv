package com.immichclient.tv

import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * Rok w skrócie: ile zdjęć w każdym miesiącu, najczęstsze miejsca i osoby (z losowej próbki ~500 zdjęć roku) oraz
 * pokaz najlepszych ujęć (najpierw ulubione z tego roku, potem losowe).
 */
/** [osobaId] = rok jednej osoby („Ola w 2026”): liczby, miejsca, osoby obok niej i pokaz tylko z jej zdjęć. */
class RokStaat(val scope: CoroutineScope, rok: Int, val osobaId: String? = null, val imie: String? = null) {
    var rok by mutableStateOf(rok)
        private set
    var miesiace by mutableStateOf<IntArray?>(null)
        private set
    var miejsca by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set
    var osoby by mutableStateOf<List<Pair<Persoon, Int>>>(emptyList())
        private set
    var najlepsze by mutableStateOf<List<Asset>>(emptyList())
        private set
    var laduje by mutableStateOf(true)
        private set

    init { laduj() }

    fun ustawRok(r: Int) { rok = r; laduj() }

    private fun laduj() {
        val r = rok
        laduje = true; miesiace = null; miejsca = emptyList(); osoby = emptyList(); najlepsze = emptyList()
        scope.launch {
            runCatching {
                val m = IntArray(12)
                Immich.maanden(Filter(persoonId = osobaId), true).forEach { b -> val ym = runCatching { YearMonth.parse(b.sleutel.take(7)) }.getOrNull(); if (ym?.year == r) m[ym.monthValue - 1] += b.aantal }
                miesiace = m
            }.onFailure { Log.w("Fotos", "rok: miesiące", it) }
            val probka = runCatching { Immich.probkaRoku(r, 500, osobaId = osobaId) }.getOrDefault(emptyList())
            miejsca = probka.mapNotNull { it.stad }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10).map { it.key to it.value }
            osoby = probka.flatMap { a -> a.osoby.filter { it.naam.isNotBlank() && !Instellingen.osobaUkryta(it.id) && it.id != osobaId } }
                .groupBy { it.id }.values.map { it.first() to it.size }.sortedByDescending { it.second }.take(10)
            val ulubione = runCatching { Immich.probkaRoku(r, 60, ulubione = true, osobaId = osobaId) }.getOrDefault(emptyList())
            // pokaz: ulubione + reszta próbki, chronologicznie, najwyżej 120
            najlepsze = (ulubione + probka.filter { a -> ulubione.none { it.id == a.id } }.take(120 - ulubione.size)).sortedBy { it.datum }
            laduje = false
        }
    }
}

@Composable
fun RokWSkrocieScherm(staat: RokStaat, nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    var wyborRoku by remember { mutableStateOf(false) }
    val suma = staat.miesiace?.sum() ?: 0
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            val tytul = staat.imie?.let { stringResource(R.string.rok_osoby_tytul, it, staat.rok) } ?: stringResource(R.string.rok_tytul, staat.rok)
            SchermKop(
                tytul,
                if (staat.miesiace == null) stringResource(R.string.laden) else pluralStringResource(R.plurals.items, suma, suma),
                etykieta = stringResource(R.string.etyk_wspomnienia),
            ) {
                val tytulPokazu = tytul
                Keuze(
                    selected = false, modifier = Modifier.focusRequester(pierwszy),
                    onClick = { if (staat.najlepsze.isNotEmpty()) nav.bekijk(LijstBron(staat.najlepsze), 0, tytulPokazu, true, null) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                    tekst = stringResource(R.string.rok_pokaz),
                )
                Keuze(
                    selected = false, onClick = { wyborRoku = true },
                    leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                    tekst = stringResource(R.string.rok_inny),
                )
            }
        }
        // miesiące: słupki z liczbą zdjęć
        staat.miesiace?.let { m ->
            item {
                val max = (m.maxOrNull() ?: 0).coerceAtLeast(1)
                val f = java.time.format.DateTimeFormatter.ofPattern("LLL", Taal.locale)
                Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                    m.forEachIndexed { i, n ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (n > 0) Taal.getal(n) else "", style = MaterialTheme.typography.labelSmall, color = Kleur.zacht)
                            Box(Modifier.fillMaxWidth().height((110f * n / max).coerceAtLeast(2f).dp).background(if (n == max) Color(0xFFF5B83D) else Kleur.primair, RoundedCornerShape(6.dp)))
                            Text(LocalDate.of(2000, i + 1, 1).format(f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        if (staat.miejsca.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rok_miejsca), style = MaterialTheme.typography.headlineSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(staat.miejsca, key = { it.first }) { (m, n) ->
                        Keuze(
                            selected = false,
                            onClick = { nav.open(Scherm.Lijst(m, staat.rok.toString(), true, nav.scope) { p -> Immich.zoekTekst(null, "", p, null, ZoekFiltr(miasto = m, rok = staat.rok, osoba = staat.osobaId?.let { Persoon(it, staat.imie.orEmpty(), false) })) }) },
                            leadingIcon = { Icon(Icons.Filled.Place, null, Modifier.width(18.dp)) }, tekst = "$m · ${Taal.getal(n)}",
                        )
                    }
                }
            }
        }
        if (staat.osoby.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rok_osoby), style = MaterialTheme.typography.headlineSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(staat.osoby, key = { it.first.id }) { (p, n) ->
                        Keuze(
                            selected = false,
                            onClick = { nav.open(Scherm.Lijst(p.naam, staat.rok.toString(), true, nav.scope) { s -> Immich.zoekTekst(null, "", s, null, ZoekFiltr(osoba = p, rok = staat.rok)) }) },
                            leadingIcon = { Gezicht(p, 26.dp) }, tekst = "${p.naam} · ${Taal.getal(n)}",
                        )
                    }
                }
            }
        }
        if (staat.laduje) item { Laadpil(true) }
        else if (staat.miejsca.isEmpty() && staat.osoby.isEmpty()) item { Text(stringResource(R.string.rok_probka_info), color = Kleur.zacht) }
        else item { Text(stringResource(R.string.rok_probka_info), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht) }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { pierwszy.requestFocus() } }
    if (wyborRoku) {
        val teraz = LocalDate.now().year
        KiesLijst(
            stringResource(R.string.rok_inny), (teraz downTo 1990).map { KiesItem(it.toString(), it.toString(), null, null) },
            staat.rok.toString(), sluit = { wyborRoku = false },
        ) { id, _ -> wyborRoku = false; staat.ustawRok(id.toInt()) }
    }
}
