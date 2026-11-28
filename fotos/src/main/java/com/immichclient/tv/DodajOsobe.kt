package com.immichclient.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/**
 * „Dodaj osobę…” z menu zdjęcia (oś czasu, Filmy, wszędzie): zdjęcie trafia do wybranej osoby z sekcji Ludzie.
 * Na zdjęciu jedna nieprzypisana twarz → ta twarz; kilka → wybór twarzy (przy jednym zdjęciu; zbiorczo pomijane);
 * brak wolnej twarzy (Immich jej nie wykrył) → nowa twarz dodana ręcznie na środku kadru (uprawnienie face.create).
 */
object DodajOsobe {
    /** zdjęcia, do których dodać osobę (otwiera wybór osoby) */
    var zdjecia by mutableStateOf<List<Asset>?>(null)
    /** jedno zdjęcie z kilkoma wolnymi twarzami: którą przypisać (osoba id, imię) */
    var wyborTwarzy by mutableStateOf<Triple<Asset, List<Twarz>, Pair<String, String>>?>(null)

    val otwarte get() = zdjecia != null || wyborTwarzy != null

    fun dodaj(lista: List<Asset>, osobaId: String, imie: String) {
        Edycja.zakres.launch {
            var dodane = 0; var juz = 0; var pominiete = 0
            var blad: Throwable? = null
            for (a in lista) {
                runCatching {
                    val twarze = Immich.twarze(a.id)
                    if (twarze.any { it.osoba?.id == osobaId }) { juz++; return@runCatching }
                    val wolne = twarze.filter { it.osoba == null }
                    when {
                        wolne.size == 1 -> { Immich.przypiszTwarz(wolne[0].id, osobaId); dodane++ }
                        wolne.isEmpty() -> { Immich.dodajTwarz(a.id, osobaId, a.ratio); dodane++ }
                        lista.size == 1 -> { wyborTwarzy = Triple(a, wolne, osobaId to imie); return@launch }
                        else -> pominiete++
                    }
                }.onFailure { blad = it; android.util.Log.w("Fotos", "dodaj osobę", it) }
            }
            Edycja.wersjaOsob++
            Edycja.komunikat = blad?.takeIf { dodane == 0 }?.let { Edycja.blad(it) } ?: buildList {
                add(Taal.s(R.string.dodaj_osobe_ok, imie, dodane))
                if (juz > 0) add(Taal.s(R.string.dodaj_osobe_juz, juz))
                if (pominiete > 0) add(Taal.s(R.string.dodaj_osobe_pominiete, pominiete))
            }.joinToString("  ·  ")
        }
    }

    fun przypiszTwarz(t: Twarz?, a: Asset, osobaId: String, imie: String) {
        Edycja.zakres.launch {
            runCatching { if (t != null) Immich.przypiszTwarz(t.id, osobaId) else Immich.dodajTwarz(a.id, osobaId, a.ratio) }
                .onSuccess { Edycja.wersjaOsob++; Edycja.komunikat = Taal.s(R.string.dodaj_osobe_ok, imie, 1) }
                .onFailure { Edycja.komunikat = Edycja.blad(it) }
        }
    }
}

@Composable
fun DodajOsobeWarstwa() {
    DodajOsobe.zdjecia?.let { l ->
        KiesLijst(stringResource(R.string.dodaj_osobe_tytul, l.size), persoonKeuzes(), "", sluit = { DodajOsobe.zdjecia = null }) { id, naam ->
            DodajOsobe.zdjecia = null
            DodajOsobe.dodaj(l, id, naam)
        }
    }
    DodajOsobe.wyborTwarzy?.let { (a, twarze, osoba) ->
        val (oid, imie) = osoba
        MenuWarstwa(stringResource(R.string.dodaj_osobe_ktora, imie), stringResource(R.string.dodaj_osobe_ktora_sub), { DodajOsobe.wyborTwarzy = null }) { pierwszy ->
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                itemsIndexed(twarze, key = { _, t -> t.id }) { i, t ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(
                            onClick = { DodajOsobe.wyborTwarzy = null; DodajOsobe.przypiszTwarz(t, a, oid, imie) },
                            modifier = (if (i == 0) pierwszy else Modifier).size(130.dp),
                            shape = CardDefaults.shape(CircleShape),
                            scale = CardDefaults.scale(focusedScale = 1.1f),
                        ) { WycinekTwarzy(a.id, a.ratio, t, Modifier.size(130.dp)) }
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Knop(stringResource(R.string.dodaj_osobe_bez_twarzy), null, Modifier.width(380.dp)) {
                    DodajOsobe.wyborTwarzy = null; DodajOsobe.przypiszTwarz(null, a, oid, imie)
                }
                Knop(stringResource(R.string.anuluj), null, Modifier.width(180.dp)) { DodajOsobe.wyborTwarzy = null }
            }
        }
    }
}
