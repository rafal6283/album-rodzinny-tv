package com.immichclient.tv

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** Podsumowanie mapy w nagłówku: „14 770 elementów · 220 miejsc · 10 krajów”. */
@Immutable data class KaartSamenvatting(val elementy: Int, val miejsca: Int, val kraje: Int)

fun maakSamenvatting(m: Markers): KaartSamenvatting {
    val miejsca = HashSet<String>()
    val kraje = HashSet<String>()
    for (i in 0 until m.aantal) {
        val iso = m.iso[i]
        if (iso != null) kraje.add(iso)
        m.stad[i]?.let { miejsca.add(it + "|" + (iso ?: m.land[i].orEmpty())) }
    }
    return KaartSamenvatting(m.aantal, miejsca.size, kraje.size)
}

/** Co karta wie o grupie: liczba miejsc, kraje (w języku aplikacji) i najczęstsze miejsce. */
@Immutable private data class GrupaInfo(val miejsca: Int, val kraje: List<String>, val miejsce: String?)

private fun grupaInfo(c: Cluster, m: Markers): GrupaInfo {
    val miejsca = HashMap<String, Int>()
    val kraje = LinkedHashMap<String, Int>()
    for (i in c.leden) {
        m.stad[i]?.let { miejsca[it] = (miejsca[it] ?: 0) + 1 }
        m.land[i]?.let { kraje[it] = (kraje[it] ?: 0) + 1 }
    }
    return GrupaInfo(miejsca.size, kraje.entries.sortedByDescending { it.value }.map { it.key }, miejsca.maxByOrNull { it.value }?.key)
}

/** Daty najnowszego i najstarszego zdjęcia grupy (mapa zna tylko id: dwa zapytania, zapamiętane). */
private val datyGrup = object : LinkedHashMap<String, Pair<LocalDateTime, LocalDateTime>>(32, 0.75f, true) {
    override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<LocalDateTime, LocalDateTime>>?) = size > 200
}

private fun okres(d: Pair<LocalDateTime, LocalDateTime>): String {
    val (nowe, stare) = d
    val a = maandTitel(stare.toLocalDate().toString())
    val b = maandTitel(nowe.toLocalDate().toString())
    return when {
        a == b -> a
        stare.year == nowe.year -> a.substringBefore(' ') + " – " + b
        else -> "$a – $b"
    }
}

/**
 * Karta wybranej grupy na mapie (jak w Immichin): duże zdjęcie, rodzaj (kontynent / region / kraj / miejsce), nazwa,
 * kraj i liczba miejsc, liczba elementów i okres, cztery miniatury z „+N” oraz podpowiedzi pilota.
 */
@Composable
fun KaartKarta(c: Cluster, staat: KaartStaat, modifier: Modifier = Modifier) {
    val m = staat.markers ?: return
    val info by produceState<GrupaInfo?>(null, c.sleutel) { value = withContext(Dispatchers.Default) { grupaInfo(c, m) } }
    val daty by produceState(synchronized(datyGrup) { datyGrup[c.sleutel] }, c.sleutel) {
        if (value != null) return@produceState
        delay(350) // przy szybkim przeskakiwaniu po grupach nie pytamy serwera o każdą
        runCatching {
            val nowe = Immich.asset(m.id[c.leden.first()]).datum
            val stare = if (c.aantal > 1) Immich.asset(m.id[c.leden.last()]).datum else nowe
            (if (nowe >= stare) nowe to stare else stare to nowe).also { synchronized(datyGrup) { datyGrup[c.sleutel] = it } }
        }.onSuccess { value = it }.onFailure { Log.w("Fotos", "kaart: daty grupy", it) }
    }
    val rodzaj = stringResource(
        when (c.niveau) {
            KaartNiveau.WERELDDEEL -> R.string.kaart_rodzaj_kontynent
            KaartNiveau.REGIO -> R.string.kaart_rodzaj_region
            KaartNiveau.LAND -> R.string.kaart_rodzaj_kraj
            KaartNiveau.PLEK -> R.string.kaart_rodzaj_miejsce
        },
    )
    val i = info
    val tytul = when {
        c.niveau != KaartNiveau.PLEK -> c.naam ?: staat.naam(c)
        else -> i?.miejsce ?: staat.naam(c)
    }.ifEmpty { stringResource(R.string.kaart) }
    val podtytul = i?.let {
        val miejsca = if (it.miejsca > 0) pluralStringResource(R.plurals.kaart_miejsca, it.miejsca, Taal.getal(it.miejsca)) else null
        when {
            c.niveau == KaartNiveau.PLEK -> listOfNotNull(it.kraje.firstOrNull(), miejsca.takeIf { _ -> it.miejsca > 1 })
            c.niveau == KaartNiveau.LAND || it.kraje.size == 1 -> listOfNotNull(it.kraje.firstOrNull().takeIf { k -> k != tytul }, miejsca)
            else -> listOfNotNull(pluralStringResource(R.plurals.kaart_kraje, it.kraje.size, Taal.getal(it.kraje.size)), miejsca)
        }.joinToString(" · ")
    }.orEmpty()

    Column(
        modifier.width(360.dp)
            .background(Color(0xF0141A3A), RoundedCornerShape(26.dp))
            .border(1.5.dp, Color(0x664D6BFF), RoundedCornerShape(26.dp))
            .padding(14.dp),
    ) {
        // okładka: zdjęcie „reprezentujące” grupę, płynnie zmieniane przy przechodzeniu między grupami
        AnimatedContent(c.hoofd, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "okladka") { h ->
            AsyncImage(
                preview(m.id[h]), null,
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Kleur.vlakHoog),
                contentScale = ContentScale.Crop,
            )
        }
        Text(rodzaj.uppercase(Taal.locale), style = EtykietaStyl, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        Text(
            tytul, style = TytulStyl.copy(fontSize = 34.sp, lineHeight = 38.sp), color = Color.White, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        if (podtytul.isNotEmpty()) Text(
            podtytul, style = MaterialTheme.typography.titleSmall, color = Kleur.zacht, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = Kleur.zacht)
            Text(
                pluralStringResource(R.plurals.kaart_elementy, c.aantal, Taal.getal(c.aantal)),
                style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 6.dp, end = 16.dp),
            )
            daty?.let {
                Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp), tint = Kleur.zacht)
                Text(okres(it), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
            }
        }
        // cztery miniatury rozłożone po całej grupie (od najnowszej); na ostatniej „+N”
        if (c.aantal > 1) Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = minOf(4, c.aantal)
            for (k in 0 until n) {
                val idx = c.leden[(k.toLong() * c.aantal / n).toInt().coerceAtMost(c.aantal - 1)]
                Box(Modifier.weight(1f).height(66.dp).clip(RoundedCornerShape(12.dp)).background(Kleur.vlakHoog)) {
                    AsyncImage(beeld(Immich.thumb(m.id[idx])), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    if (k == n - 1 && c.aantal > n) Box(Modifier.fillMaxSize().background(Color(0x8C000000)), contentAlignment = Alignment.Center) {
                        Text("+" + Taal.getal(c.aantal - n + 1), style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }
            }
        }
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Klawisz { Text("OK", style = MaterialTheme.typography.labelMedium, color = Color.White) }
            Text(
                stringResource(if (c.aantal == 1) R.string.kaart_karta_otworz else R.string.kaart_karta_przybliz),
                style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp, end = 18.dp),
            )
            Klawisz { Icon(Icons.Filled.Slideshow, null, Modifier.size(14.dp), tint = Color.White) }
            Text(stringResource(R.string.diashow), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
        if (c.aantal > 1) Text(
            stringResource(R.string.kaart_karta_wszystkie, Taal.getal(c.aantal)),
            style = MaterialTheme.typography.labelMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
    }
}

@Composable
private fun Klawisz(tresc: @Composable () -> Unit) {
    Box(
        Modifier.background(Color(0x40FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) { tresc() }
}
