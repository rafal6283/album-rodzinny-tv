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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/** Zdjęcie zrobione przed datą urodzenia osoby (Immich przy rozpoznawaniu twarzy daty urodzenia nie bierze pod uwagę). */
fun Asset.przedUrodzeniem(ur: LocalDate?): Boolean = ur != null && datum.toLocalDate().isBefore(ur)

/** Daty urodzenia wszystkich osób (pamięć podręczna; po zmianie daty w aplikacji unieważniana przez [Urodziny]). */
object DatyUrodzin {
    @Volatile private var mapa: Map<String, LocalDate>? = null
    private val blokada = Mutex()

    suspend fun wszystkie(): Map<String, LocalDate> = mapa ?: blokada.withLock {
        mapa ?: runCatching { Immich.osobyZUrodzinami().mapNotNull { p -> p.urodziny?.let { p.id to it } }.toMap() }
            .onSuccess { mapa = it }.getOrDefault(emptyMap())
    }

    suspend fun dla(osobaId: String): LocalDate? = wszystkie()[osobaId]
    fun uniewaznij() { mapa = null }
}

/** Porządki → „Sprzed urodzenia”: osoby z datą urodzenia, które mają zdjęcia sprzed niej (z liczbą takich zdjęć). */
class PrzedUrodzeniemStaat(private val scope: CoroutineScope) {
    class Wpis(val osoba: Persoon, val ur: LocalDate, val ile: Int, val wiecej: Boolean)

    val lista = mutableStateListOf<Wpis>()
    var sprawdzono by mutableIntStateOf(0)
        private set
    var wszystkich by mutableIntStateOf(0)
        private set
    var gotowe by mutableStateOf(false)
        private set
    var blad by mutableStateOf<String?>(null)
        private set

    init { laduj() }

    fun laduj() {
        lista.clear(); sprawdzono = 0; gotowe = false; blad = null
        scope.launch {
            val osoby = runCatching { Immich.osobyZUrodzinami() }.onFailure { blad = it.message }.getOrNull()
            if (osoby == null) { gotowe = true; return@launch }
            wszystkich = osoby.size
            val sem = Semaphore(4)
            osoby.map { p ->
                async {
                    sem.withPermit {
                        val ur = p.urodziny ?: return@withPermit
                        runCatching { Immich.przedUrodzeniem(p.id, ur, 1, 250) }.onSuccess { s ->
                            val ile = s.assets.count { it.przedUrodzeniem(ur) }
                            if (ile > 0) {
                                lista += Wpis(p, ur, ile, s.volgende != null)
                                lista.sortByDescending { it.ile }
                            }
                        }
                        sprawdzono++
                    }
                }
            }.awaitAll()
            gotowe = true
        }
    }
}

@Composable
fun PrzedUrodzeniemScherm(staat: PrzedUrodzeniemStaat, nav: Nav) {
    PamiecEkranu("przedur")
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                SchermKop(stringResource(R.string.przed_ur_tytul), stringResource(R.string.przed_ur_sub))
                when {
                    staat.blad != null -> Text(stringResource(R.string.laden_mislukt, staat.blad.orEmpty()), color = Kleur.zacht)
                    !staat.gotowe -> Text(stringResource(R.string.przed_ur_sprawdzam, staat.sprawdzono, staat.wszystkich), color = Kleur.zacht)
                    staat.lista.isEmpty() -> Text(stringResource(R.string.przed_ur_brak), color = Kleur.zacht)
                }
            }
        }
        items(staat.lista.toList(), key = { it.osoba.id }) { w ->
            val imie = Edycja.imie(w.osoba)
            val dataUr = w.ur.format(Formaat.dagJaar)
            Card(
                onClick = {
                    nav.open(Scherm.Lijst(
                        Taal.s(R.string.przed_ur_lista, imie), Taal.s(R.string.przed_ur_lista_sub, dataUr), true, nav.scope,
                        osoba = w.osoba.id to imie,
                    ) { s -> Immich.przedUrodzeniem(w.osoba.id, w.ur, s) })
                },
                modifier = Modifier.pamiecFokusu("przedur", w.osoba.id, pierwszy = w === staat.lista.firstOrNull()).fillMaxWidth().height(130.dp)
                    .langeOk { Podreczne.osoba(w.osoba.id, imie) },
                shape = CardDefaults.shape(RoundedCornerShape(20.dp)),
                scale = CardDefaults.scale(focusedScale = 1.05f),
            ) {
                Row(Modifier.fillMaxSize().background(Color(0xFF221C14)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(beeld(Immich.gezicht(w.osoba.id)), null, Modifier.size(90.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(imie, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                        Text(
                            pluralStringResource(R.plurals.items, w.ile, w.ile) + (if (w.wiecej) "+" else ""),
                            style = MaterialTheme.typography.bodyLarge, color = Color(0xFFF5B83D),
                        )
                        Text(stringResource(R.string.przed_ur_data, dataUr), style = MaterialTheme.typography.bodySmall, color = Color(0xCCFFFFFF), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Lista osób do „To jest ktoś inny…”: gdy znamy datę urodzenia bieżącej osoby, na górze podpowiedzi — osoby urodzone
 * wcześniej (najpierw najbliżej: rodzeństwo, potem rodzice), bo to je Immich zwykle myli na zdjęciach sprzed urodzenia.
 */
@Composable
fun persoonKeuzesZPodpowiedzia(ur: LocalDate?, bez: String): List<KiesItem>? {
    val l by produceState<List<KiesItem>?>(null, ur, bez) {
        value = runCatching {
            val osoby = Immich.personen().filter { it.id != bez }
            val daty = if (ur != null) DatyUrodzin.wszystkie() else emptyMap()
            val podp = osoby.filter { p -> daty[p.id]?.isBefore(ur) == true }.sortedByDescending { daty[it.id] }.take(6)
            val ids = podp.map { it.id }.toSet()
            podp.map { p -> KiesItem(p.id, p.naam, Taal.s(R.string.przed_ur_podpowiedz, daty[p.id]!!.year.toString()), Immich.gezicht(p.id), rond = true) } +
                osoby.filter { it.id !in ids }.map { KiesItem(it.id, it.naam, null, Immich.gezicht(it.id), rond = true) }
        }.getOrDefault(emptyList())
    }
    return l
}
