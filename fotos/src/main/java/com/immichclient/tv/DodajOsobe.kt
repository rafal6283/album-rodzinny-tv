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
 * „Dodaj osobę…” z menu zdjęcia (oś czasu, Filmy, wszędzie): zdjęcie trafia do osób z sekcji Ludzie — także kilku
 * naraz (po każdej osobie lista wraca, osoby już na zdjęciu mają ✓, „Gotowe” kończy).
 * Jedno zdjęcie: przy wolnych (nieopisanych) twarzach zawsze wybór, która to twarz — żeby pierwsza dodana osoba nie
 * zajęła twarzy kogoś innego; „Żadna z nich” albo brak wolnych twarzy → twarz dodana ręcznie (face.create), każda
 * kolejna w innym miejscu kadru. Zbiorczo: jedna wolna twarz → przypisana, brak → ręczna, kilka → pominięte.
 */
object DodajOsobe {
    const val GOTOWE = "__gotowe"
    /** zdjęcia, do których dodawać osoby (otwiera listę osób) */
    var zdjecia by mutableStateOf<List<Asset>?>(null)
    /** jedno zdjęcie z wolnymi twarzami: którą przypisać (osoba id, imię) */
    var wyborTwarzy by mutableStateOf<Triple<Asset, List<Twarz>, Pair<String, String>>?>(null)
    /** osoby dodane w tym oknie albo już opisane na zdjęciu (✓ na liście) */
    val obecne = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    val otwarte get() = zdjecia != null || wyborTwarzy != null

    fun otworz(l: List<Asset>) {
        obecne.clear(); wyborTwarzy = null; zdjecia = l
        // jedno zdjęcie: kto już jest na nim opisany
        if (l.size == 1) Edycja.zakres.launch {
            runCatching { Immich.twarze(l[0].id) }.getOrNull()?.mapNotNull { it.osoba?.id }?.forEach { obecne[it] = true }
        }
    }
    fun zamknij() { zdjecia = null; wyborTwarzy = null; obecne.clear() }

    /** miejsce ręcznej twarzy: kolejne obok siebie (środek, lewo, prawo, …), by nie nachodziły na siebie */
    private fun miejsce(k: Int): Float = listOf(0.4f, 0.12f, 0.68f, 0.26f, 0.54f)[k % 5]

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
                        lista.size == 1 && wolne.isNotEmpty() -> { wyborTwarzy = Triple(a, wolne, osobaId to imie); return@launch }
                        wolne.size == 1 -> { Immich.przypiszTwarz(wolne[0].id, osobaId); dodane++ }
                        wolne.isEmpty() -> { Immich.dodajTwarz(a.id, osobaId, a.ratio, miejsce(twarze.size)); dodane++ }
                        else -> pominiete++
                    }
                }.onFailure { blad = it; android.util.Log.w("Fotos", "dodaj osobę", it) }
            }
            if (dodane > 0 || juz > 0) obecne[osobaId] = true
            Edycja.wersjaOsob++
            Edycja.komunikat = blad?.takeIf { dodane == 0 }?.let { Edycja.blad(it) } ?: buildList {
                add(Taal.s(R.string.dodaj_osobe_ok, imie, dodane))
                if (juz > 0) add(Taal.s(R.string.dodaj_osobe_juz, juz))
                if (pominiete > 0) add(Taal.s(R.string.dodaj_osobe_pominiete, pominiete))
            }.joinToString("  ·  ")
        }
    }

    /** wybór z okna twarzy: ta twarz albo (null) nowa ręczna twarz; potem z powrotem lista osób */
    fun przypiszTwarz(t: Twarz?, a: Asset, osobaId: String, imie: String) {
        Edycja.zakres.launch {
            runCatching {
                if (t != null) Immich.przypiszTwarz(t.id, osobaId)
                else Immich.dodajTwarz(a.id, osobaId, a.ratio, miejsce(runCatching { Immich.twarze(a.id).size }.getOrDefault(0)))
            }
                .onSuccess { obecne[osobaId] = true; Edycja.wersjaOsob++; Edycja.komunikat = Taal.s(R.string.dodaj_osobe_ok, imie, 1) }
                .onFailure { Edycja.komunikat = Edycja.blad(it) }
        }
    }
}

@Composable
fun DodajOsobeWarstwa() {
    val l = DodajOsobe.zdjecia
    if (l != null) androidx.compose.runtime.key(l) {
      // lista osób wczytywana przy każdym otwarciu (nowe imiona z „Kto to jest?” od razu widoczne)
      val osoby = persoonKeuzes()
      if (DodajOsobe.wyborTwarzy == null) {
        val gotowe = stringResource(R.string.dodaj_osobe_gotowe)
        val podpis = stringResource(R.string.dodaj_osobe_gotowe_sub)
        val items = androidx.compose.runtime.remember(osoby) { osoby?.let { listOf(KiesItem(DodajOsobe.GOTOWE, "✓  $gotowe", podpis, null)) + it } }
        KiesLijst(stringResource(R.string.dodaj_osobe_tytul, l.size), items, DodajOsobe.obecne.keys.joinToString(","), sluit = { DodajOsobe.zamknij() }) { id, naam ->
            if (id == DodajOsobe.GOTOWE) DodajOsobe.zamknij()
            else if (DodajOsobe.obecne[id] == true && l.size == 1) Edycja.komunikat = Taal.s(R.string.dodaj_osobe_juz_jest, naam)
            else DodajOsobe.dodaj(l, id, naam)
        }
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
