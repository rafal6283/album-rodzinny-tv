package com.immichclient.tv

import androidx.compose.material.icons.filled.Place
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** Een regel in een fotoraster (LazyColumn). */
sealed interface Rij { val sleutel: String }
data class RijKop(override val sleutel: String, val titel: String, val sub: String?, val plaatsen: String? = null) : Rij
data class RijFotos(val regel: Regel) : Rij { override val sleutel get() = regel.sleutel }
/** Maand die nog geladen wordt; focusbaar, zodat ingedrukt houden van "omlaag" nooit vastloopt. */
data class RijWacht(override val sleutel: String, val maand: String, val hoogte: Float) : Rij

const val RIJ_HOOGTE = 138f
/** kwadratowe kafelki są nieco większe niż wiersz „justified” (jak w Immichin: ~6 w rzędzie) */
const val KWADRAT_SKALA = 0.88f
const val TUSSEN = 12f
const val GROEP_TUSSEN = 26f
const val KOP_HOOGTE = 30f

class RasterStaat {
    val lijst = LazyListState()
    /** Asset die focus moet krijgen zodra hij in beeld is (terug uit de viewer / terug naar dit scherm). */
    var focusDoel by mutableStateOf<String?>(null)
    var laatsteFocus: Asset? = null
    /** Viewer gesloten op deze foto: daarheen scrollen en focus geven. */
    var terugNaar by mutableStateOf<String?>(null)

    suspend fun gaNaar(id: String, rijen: List<Rij>, voorKop: Int = 0) {
        val i = rijen.indexOfFirst { r -> r is RijFotos && r.regel.stukken.any { s -> s.vakken.any { it.asset.id == id } } }
        if (i < 0) return
        val zicht = lijst.layoutInfo.visibleItemsInfo
        val al = zicht.any { it.index == i + voorKop && it.offset >= 0 && it.offset + it.size <= lijst.layoutInfo.viewportEndOffset }
        if (!al) lijst.scrollToItem(i + voorKop, -(lijst.layoutInfo.viewportSize.height * 0.3f).toInt())
        focusDoel = id
    }
}

/** Bron voor de viewer: kan meer zijn dan wat er geladen is (tijdlijn laadt per maand bij). */
interface BeeldBron {
    val aantal: Int
    fun op(i: Int): Asset?
}

class LijstBron(val lijst: List<Asset>) : BeeldBron {
    override val aantal get() = lijst.size
    override fun op(i: Int) = lijst.getOrNull(i)
}

/**
 * Losowa, „nieskończona” mieszanka zdjęć i filmów (przycisk „Losowo”): porcje z /search/random, kolejna doczytuje się,
 * gdy zostaje mniej niż 10 do końca. Powtórki z poprzednich porcji są pomijane.
 */
class LosowaBron(private val scope: kotlinx.coroutines.CoroutineScope, private val ulubione: Boolean, private val tylkoFilmy: Boolean = false) : BeeldBron {
    private val lista = androidx.compose.runtime.mutableStateListOf<Asset>()
    private val widziane = HashSet<String>()
    private var laduje = false
    private var pustych = 0

    suspend fun doladuj() {
        if (laduje || pustych >= 3) return
        laduje = true
        val nowe = runCatching { Immich.losowe(PORCJA, ulubione, tylkoFilmy) }.getOrDefault(emptyList()).filter { widziane.add(it.id) }
        if (nowe.isEmpty()) pustych++ else { pustych = 0; lista.addAll(nowe) }
        laduje = false
    }

    override val aantal get() = lista.size
    override fun op(i: Int): Asset? {
        if (i >= lista.size - 10) scope.launch { doladuj() }
        return lista.getOrNull(i)
    }

    private companion object { const val PORCJA = 60 }
}

/**
 * Gekozen foto's blijven op ~30 % van de hoogte staan (zoals Android TV-rijen), met een zachte scroll;
 * standaard schuift Compose alleen net genoeg, dan zit je steeds tegen de rand. Geldt voor alle lijsten en rasters
 * (via het thema).
 *
 * De spec is een spring, geen tween: Compose past de spec bij "bring into view" elk frame opnieuw toe op de
 * resterende afstand (UpdatableAnimationState, playTime = één frame). Een tween(260, FastOutSlowIn) kwam daardoor
 * per frame maar ~1 % dichterbij (de easing begint vlak): een kruipende scroll die als een bewuste vertraging voelde.
 * Een spring integreert wél correct per frame (met snelheid) en volgt ingedrukt houden soepel zonder achterstand.
 */
@OptIn(ExperimentalFoundationApi::class)
val focusOp30 = object : BringIntoViewSpec {
    @Deprecated("") override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val doel = containerSize * 0.3f
        return if (size > containerSize * 0.7f) offset else offset - doel
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FotoRaster(
    rijen: List<Rij>,
    staat: RasterStaat,
    onKlik: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    bovenAantal: Int = 1,
    onWacht: (String) -> Unit = {},
    /** przytrzymanie OK → „Pokaz od tego miejsca” w menu podręcznym (null = bez tej pozycji) */
    onPokaz: ((Asset) -> Unit)? = null,
    boven: LazyListScope.() -> Unit = {},
) {
    androidx.compose.runtime.LaunchedEffect(staat.terugNaar, rijen) {
        val id = staat.terugNaar ?: return@LaunchedEffect
        staat.gaNaar(id, rijen, bovenAantal)
        staat.terugNaar = null
    }
    LazyColumn(
        state = staat.lijst,
        modifier = modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(TUSSEN.dp),
    ) {
        boven()
        items(rijen, key = { it.sleutel }, contentType = { it::class }) { r ->
            when (r) {
                is RijKop -> MaandKop(r)
                is RijFotos -> FotoRegel(r.regel, staat, onKlik, onPokaz)
                is RijWacht -> WachtVak(r, onWacht)
            }
        }
    }
}

/**
 * Nagłówek miesiąca w stylu Immichin: „September 2026” szeryfem (rok jaśniejszy), pinezka z miejscami, liczba po prawej.
 */
@Composable
private fun MaandKop(r: RijKop) {
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val ostatnia = r.titel.substringAfterLast(' ', "")
        val (miesiac, rok) = if (ostatnia.length == 4 && ostatnia.all { it.isDigit() }) r.titel.substringBeforeLast(' ') to ostatnia else r.titel to null
        Text(miesiac, style = MaterialTheme.typography.displayMedium, maxLines = 1)
        rok?.let { Text("  $it", style = MaterialTheme.typography.displayMedium, color = Kleur.zacht.copy(alpha = 0.75f), maxLines = 1) }
        r.plaatsen?.let {
            androidx.tv.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Place, null,
                Modifier.padding(start = 20.dp, end = 5.dp).width(16.dp).height(16.dp), tint = Kleur.pinezka,
            )
            Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Kleur.tekst.copy(alpha = 0.85f), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Box(Modifier.weight(1f))
        r.sub?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Kleur.zacht, modifier = Modifier.padding(start = 16.dp)) }
    }
}

/** „Big Sur · Yosemite · Portland +2”: najczęstsze miasta w kolejności liczby zdjęć. */
fun miejscaTekst(assets: List<Asset>, ile: Int = 3): String? {
    val miasta = assets.mapNotNull { it.stad }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    if (miasta.isEmpty()) return null
    val reszta = miasta.size - ile
    return miasta.take(ile).joinToString(" · ") + if (reszta > 0) "  +$reszta" else ""
}

@Composable
private fun FotoRegel(regel: Regel, staat: RasterStaat, onKlik: (Asset) -> Unit, onPokaz: ((Asset) -> Unit)? = null) {
    var focus by remember { mutableStateOf(false) }
    // vergrote focusfoto boven de volgende rij tekenen
    Row(
        Modifier.zIndex(if (focus) 1f else 0f).onFocusChanged { focus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(GROEP_TUSSEN.dp),
    ) {
        for (stuk in regel.stukken) {
            var stukFocus by remember { mutableStateOf(false) }
            Column(Modifier.zIndex(if (stukFocus) 1f else 0f).onFocusChanged { stukFocus = it.hasFocus }) {
                if (regel.metKop) Text(
                    stuk.kop.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    modifier = Modifier.height(KOP_HOOGTE.dp).padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TUSSEN.dp)) {
                    for (v in stuk.vakken) {
                        FotoVak(
                            v.asset, v.breedte.dp, regel.hoogte.dp,
                            onKlik = { onKlik(v.asset) },
                            focusDoel = staat.focusDoel == v.asset.id,
                            onFocusDoelKlaar = { staat.focusDoel = null },
                            onFocus = { staat.laatsteFocus = v.asset },
                            onDlugi = { Podreczne.zdjecie(v.asset, onPokaz) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WachtVak(r: RijWacht, onWacht: (String) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(r.maand) { onWacht(r.maand) }
    // zacht ademende plaatshouder (alleen laag-alpha: geen hertekenen)
    val adem = rememberInfiniteTransition(label = "adem")
    val a by adem.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(r.hoogte.dp).graphicsLayer { alpha = a }.onFocusChanged { if (it.isFocused) onWacht(r.maand) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak.copy(alpha = 0.5f), focusedContainerColor = Kleur.vlakHoog),
    ) { Box(Modifier) {} }
}

fun breedteVoor(totaal: Float) = totaal - 48f

/** Platte lijst (zoekresultaten, herinnering, plaats) als rijen: per dag, of als één blok op relevantie. */
fun rijenVoor(assets: List<Asset>, breedte: Float, perDag: Boolean, metJaar: Boolean = true, hoogte: Float = RIJ_HOOGTE): List<Rij> {
    if (Instellingen.kwadraty && !perDag) return Opmaak.kwadraty("l", assets, breedte, hoogte * KWADRAT_SKALA, TUSSEN).map { RijFotos(it) }
    val groepen = if (perDag) Opmaak.perDag(assets, metJaar) else listOf(Groep(null, assets))
    return Opmaak.regels("l", groepen, breedte, hoogte, TUSSEN, GROEP_TUSSEN).map { RijFotos(it) }
}
