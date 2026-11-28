package com.immichclient.tv

import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PlayArrow
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Pagina's met resultaten (zoeken, plaats): laadt de volgende pagina als je bij het einde komt. */
class Resultaten(private val scope: CoroutineScope) {
    var assets by mutableStateOf<List<Asset>>(emptyList())
    var bezig by mutableStateOf(false)
    var fout by mutableStateOf<String?>(null)
    var gezocht by mutableStateOf(false)
    private var volgende: Int? = null
    /** są jeszcze kolejne strony do wczytania */
    val jestWiecej get() = volgende != null && fout == null
    private var laad: (suspend (Int) -> Pagina)? = null
    private var job: Job? = null
    val raster = RasterStaat()

    fun start(l: suspend (Int) -> Pagina) {
        job?.cancel()
        laad = l; assets = emptyList(); volgende = 1; fout = null; gezocht = true; bezig = false
        scope.launch { raster.lijst.scrollToItem(0) }
        meer()
    }

    /** Terug naar de beginstand (suggesties). */
    fun wis() {
        job?.cancel()
        laad = null; assets = emptyList(); volgende = null; fout = null; gezocht = false; bezig = false
    }

    fun meer() {
        val p = volgende ?: return
        val l = laad ?: return
        if (bezig) return
        bezig = true
        job = scope.launch {
            val uit = runCatching { l(p) }
            if (laad !== l) return@launch  // intussen een nieuwe zoekopdracht of gewist (ook: geannuleerd)
            uit.onSuccess { r -> assets = assets + r.assets.filter { a -> assets.none { it.id == a.id } }; volgende = r.volgende }
                .onFailure { fout = it.message; volgende = null; Log.w("Fotos", "zoeken", it) }
            bezig = false
        }
    }
}

private const val TOP_PERSONEN = 10

enum class ZoekModus(@StringRes val label: Int) { SLIM(R.string.modus_slim), TEKST(R.string.modus_tekst), NAAM(R.string.modus_naam) }
enum class ZoekType(@StringRes val label: Int, val video: Boolean?) { ALLES(R.string.type_alles, null), FOTO(R.string.type_fotos, false), VIDEO(R.string.type_videos, true) }
enum class ZoekVolgorde(@StringRes val label: Int) { RELEVANTIE(R.string.volg_relevantie), NIEUW(R.string.volg_nieuw), OUD(R.string.volg_oud) }

class ZoekStaat(scope: CoroutineScope) {
    var tekst by mutableStateOf("")
    var modus by mutableStateOf(ZoekModus.SLIM)
    var type by mutableStateOf(ZoekType.ALLES)
    var volgorde by mutableStateOf(ZoekVolgorde.RELEVANTIE)
    val res = Resultaten(scope)
    /** na een nieuwe zoekopdracht het eerste resultaat focus geven */
    var focusOpResultaat = false
    var personen by mutableStateOf<List<Persoon>>(emptyList())
    /** filtry: osoba + miejsce + rok (np. „Ola, Antalya, 2025”); działają z tekstem i bez */
    var filtr by mutableStateOf(ZoekFiltr())

    init {
        scope.launch { runCatching { Immich.personen() }.onSuccess { personen = it.filter { p -> !Instellingen.osobaUkryta(p.id) } } }
        scope.launch { Immich.warmZoeken() }
        scope.launch {
            glosMiasta = runCatching { Immich.plaatsen().map { it.stad } }.getOrDefault(emptyList())
            glosKraje = runCatching { Immich.sugestie("country").map { it to Landnaam.vertaal(it) } }.getOrDefault(emptyList())
        }
    }

    /** Zoekopdracht zonder tekst, alleen het type (alle video's / alle foto's, op datum). */
    val alleenType get() = tekst.isBlank() && (type != ZoekType.ALLES || !filtr.pusty)

    fun zoek(c: Context) {
        val t = tekst.trim()
        val ty = type
        val f = filtr
        if (t.isEmpty()) {
            // geen tekst: met Foto's/Video's of een filter gekozen = alles daarvan op datum, anders niets te zoeken
            if (ty.video == null && f.pusty) { if (res.gezocht) res.wis(); return }
            if (volgorde == ZoekVolgorde.RELEVANTIE) volgorde = ZoekVolgorde.NIEUW
            focusOpResultaat = true
            res.start { p -> Immich.zoekTekst(null, "", p, ty.video, f) }
            return
        }
        Recent.voegToe(c, t)
        focusOpResultaat = true
        val m = modus
        res.start { p ->
            when (m) {
                ZoekModus.SLIM -> Immich.zoekSlim(t, p, ty.video, f)
                ZoekModus.TEKST -> Immich.zoekTekst(t, "ocr", p, ty.video, f)
                ZoekModus.NAAM -> Immich.zoekTekst(t, "originalFileName", p, ty.video, f)
            }
        }
    }

    /**
     * Polecenie głosowe: „pokaż Olę w Turcji w 2025” → osoba Ola + kraj Turcja + rok 2025, a reszta słów (jeśli coś
     * zostanie, np. „na plaży”) jako wyszukiwanie inteligentne. Odmiana polskich nazw: porównanie po rdzeniu
     * („Olę”/„Ola”, „Turcji”/„Turcja”, „Krakowie”/„Kraków”).
     */
    fun polecenie(t: String, c: Context) {
        val slowa = t.split(Regex("\\s+")).filter { it.isNotBlank() }
        fun norm(s: String) = zonderAccenten(s).lowercase(Taal.locale).trim(',', '.', '!', '?')
        fun pasuje(w: String, nazwa: String): Boolean {
            val n = norm(nazwa); val x = norm(w)
            if (n.length < 3 || x.length < 3) return x == n
            return x.startsWith(n) || (x.length >= n.length - 1 && x.length <= n.length + 3 && x.startsWith(n.dropLast(1)))
        }
        val uzyte = HashSet<Int>()
        var osoba: Persoon? = null; var miasto: String? = null; var kraj: String? = null; var rok: Int? = null
        slowa.forEachIndexed { i, w ->
            val x = norm(w)
            x.toIntOrNull()?.takeIf { it in 1900..2100 }?.let { rok = it; uzyte += i; return@forEachIndexed }
            if (osoba == null) personen.firstOrNull { p -> pasuje(w, p.naam.substringBefore(' ')) }?.let { osoba = it; uzyte += i; return@forEachIndexed }
            if (miasto == null) glosMiasta.firstOrNull { pasuje(w, it) }?.let { miasto = it; uzyte += i; return@forEachIndexed }
            if (kraj == null) glosKraje.firstOrNull { (en, lokalnie) -> pasuje(w, lokalnie) || pasuje(w, en) }?.let { kraj = it.first; uzyte += i; return@forEachIndexed }
        }
        val zbedne = setOf("pokaz", "pokaze", "zdjecia", "zdjec", "foto", "filmy", "z", "w", "we", "na", "do", "i", "oraz", "roku", "rok", "show", "photos", "of", "in", "from", "the", "me")
        val reszta = slowa.filterIndexed { i, w -> i !in uzyte && norm(w) !in zbedne }.joinToString(" ")
        filtr = ZoekFiltr(osoba = osoba, miasto = miasto, rok = rok, kraj = kraj)
        tekst = reszta
        zoek(c)
    }

    /** nazwy miast i krajów do poleceń głosowych (kraje: angielska nazwa z Immich → nazwa w języku aplikacji) */
    private var glosMiasta: List<String> = emptyList()
    private var glosKraje: List<Pair<String, String>> = emptyList()

    /** Opis filtrów do tytułu wyników („Ola · Antalya · 2025”). */
    fun opisFiltra() = listOfNotNull(filtr.osoba?.naam, filtr.miasto, filtr.kraj?.let { Landnaam.vertaal(it) }, filtr.rok?.toString()).joinToString(" · ")
}

object Recent {
    private const val MAX = 10
    fun lijst(c: Context): List<String> =
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun voegToe(c: Context, t: String) {
        val l = (listOf(t) + lijst(c).filter { !it.equals(t, true) }).take(MAX)
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
    }
}

@Composable
fun ZoekScherm(staat: ZoekStaat, nav: Nav) {
    val ctx = LocalContext.current
    var invoer by remember { mutableStateOf(false) }
    var filtrOkno by remember { mutableStateOf<String?>(null) }
    val veld = remember { FocusRequester() }
    val spraak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) staat.polecenie(t, ctx)
    }
    val spraakPrompt = stringResource(R.string.spraak_prompt)
    val kanSpraak = remember {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val gesorteerd = remember(staat.res.assets, staat.volgorde) {
            when (staat.volgorde) {
                ZoekVolgorde.RELEVANTIE -> staat.res.assets
                ZoekVolgorde.NIEUW -> staat.res.assets.sortedByDescending { it.datum }
                ZoekVolgorde.OUD -> staat.res.assets.sortedBy { it.datum }
            }
        }
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(gesorteerd, breedte, rijHoogte) { rijenVoor(gesorteerd, breedte, perDag = staat.volgorde != ZoekVolgorde.RELEVANTIE, hoogte = rijHoogte) }
        val bron = remember(gesorteerd) { LijstBron(gesorteerd) }
        LaunchedEffect(rijen) {
            if (staat.focusOpResultaat && gesorteerd.isNotEmpty()) { staat.focusOpResultaat = false; staat.res.raster.focusDoel = gesorteerd.first().id }
        }
        // volgende pagina als het einde in zicht komt
        LaunchedEffect(rijen) {
            snapshotFlow { staat.res.raster.lijst.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rijen.isNotEmpty()) staat.res.meer() }
        }
        FotoRaster(
            rijen = rijen, staat = staat.res.raster, bovenAantal = 1,
            onKlik = { a -> nav.bekijk(bron, gesorteerd.indexOf(a), if (staat.alleenType) staat.opisFiltra().ifEmpty { ctx.getString(staat.type.label) } else ctx.getString(R.string.zoeken_titel, staat.tekst), false, staat.res.raster) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SchermKop(stringResource(R.string.zoeken), stringResource(R.string.zoek_hint), etykieta = stringResource(R.string.modus_slim))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { invoer = true },
                            modifier = Modifier.weight(1f).height(56.dp).focusRequester(veld),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlakHoog, focusedContainerColor = Color(0xFF34343A)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Kleur.primair), shape = RoundedCornerShape(28.dp)),
                            ),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, null, tint = Kleur.zacht)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    staat.tekst.ifEmpty { stringResource(R.string.zoek_hint) },
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    color = if (staat.tekst.isEmpty()) Kleur.zacht else Kleur.tekst,
                                )
                            }
                        }
                        if (kanSpraak) Surface(
                            onClick = {
                                runCatching {
                                    spraak.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Taal.locale.toLanguageTag())
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, spraakPrompt),
                                    )
                                }
                            },
                            modifier = Modifier.size(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlakHoog, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, stringResource(R.string.spraak)) } }
                        // wyczyść: tekst, filtry, rodzaj i wyniki — od nowa jednym przyciskiem
                        if (staat.tekst.isNotEmpty() || !staat.filtr.pusty || staat.type != ZoekType.ALLES || staat.res.gezocht) Surface(
                            onClick = {
                                staat.tekst = ""; staat.filtr = ZoekFiltr(); staat.type = ZoekType.ALLES; staat.volgorde = ZoekVolgorde.RELEVANTIE
                                staat.res.wis()
                                runCatching { veld.requestFocus() }
                            },
                            modifier = Modifier.height(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlakHoog, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
                        ) {
                            Row(Modifier.fillMaxHeight().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Close, null)
                                Text("  " + stringResource(R.string.toets_wissen).replaceFirstChar { it.titlecase(Taal.locale) }, style = MaterialTheme.typography.titleMedium, color = androidx.tv.material3.LocalContentColor.current)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (m in ZoekModus.entries) Keuze(selected = staat.modus == m, onClick = { staat.modus = m; staat.zoek(ctx) }, tekst = stringResource(m.label))
                        Spacer(Modifier.width(18.dp))
                        for (t in ZoekType.entries) Keuze(selected = staat.type == t, onClick = { staat.type = t; staat.zoek(ctx) }, tekst = stringResource(t.label))
                    }
                    // filtry: osoba / miejsce / rok (łączą się z tekstem i ze sobą)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Keuze(selected = staat.filtr.osoba != null, onClick = { filtrOkno = "osoba" },
                            leadingIcon = { Icon(Icons.Filled.Person, null, Modifier.width(18.dp)) },
                            tekst = staat.filtr.osoba?.naam ?: stringResource(R.string.filtr_osoba))
                        Keuze(selected = staat.filtr.miasto != null || staat.filtr.kraj != null, onClick = { filtrOkno = "miejsce" },
                            leadingIcon = { Icon(Icons.Filled.Place, null, Modifier.width(18.dp)) },
                            tekst = staat.filtr.miasto ?: staat.filtr.kraj?.let { Landnaam.vertaal(it) } ?: stringResource(R.string.filtr_miejsce))
                        Keuze(selected = staat.filtr.rok != null, onClick = { filtrOkno = "rok" },
                            leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                            tekst = staat.filtr.rok?.toString() ?: stringResource(R.string.filtr_rok))
                        if (!staat.filtr.pusty) Keuze(selected = false, onClick = { staat.filtr = ZoekFiltr(); staat.zoek(ctx) },
                            leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.width(18.dp)) }, tekst = stringResource(R.string.filtr_wyczysc))
                    }
                    if (staat.res.assets.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (v in ZoekVolgorde.entries) Keuze(selected = staat.volgorde == v, onClick = { staat.volgorde = v }, tekst = stringResource(v.label))
                    }
                    when {
                        staat.res.bezig && staat.res.assets.isEmpty() -> Text(stringResource(R.string.zoeken_bezig), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                        staat.res.fout != null && staat.res.assets.isEmpty() -> Text(stringResource(R.string.zoeken_mislukt, staat.res.fout.orEmpty()), color = Kleur.zacht)
                        staat.res.gezocht && staat.res.assets.isEmpty() -> Text(stringResource(R.string.niets_gevonden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                        staat.res.gezocht -> Text(pluralStringResource(R.plurals.resultaten, gesorteerd.size, Taal.getal(gesorteerd.size) + if (staat.res.bezig) "+" else ""), style = MaterialTheme.typography.bodySmall)
                        else -> Suggesties(staat, nav) { runCatching { veld.requestFocus() }; staat.tekst = it; staat.zoek(ctx) } // fokus na pole: podpowiedzi znikają (inaczej fokus spadał do bocznego menu), potem na pierwszy wynik
                    }
                }
            }
        }
        LijstBalk(staat.res.raster, rijen, gesorteerd.size, staat.res.bezig, Modifier.align(Alignment.CenterEnd))
    }
    LaunchedEffect(Unit) { delay(30); if (!staat.res.gezocht) runCatching { veld.requestFocus() } }
    // laag dicht: focus terug op het zoekveld (anders springt Android naar de lade); resultaten nemen hem daarna over
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(invoer) { if (wasOpen && !invoer) { delay(16); runCatching { veld.requestFocus() } }; wasOpen = invoer }
    var filtrByl by remember { mutableStateOf(false) }
    LaunchedEffect(filtrOkno) { if (filtrByl && filtrOkno == null && staat.res.assets.isEmpty()) { delay(16); runCatching { veld.requestFocus() } }; filtrByl = filtrOkno != null }
    if (invoer) TekstInvoer(stringResource(R.string.zoeken), staat.tekst, sluit = { invoer = false }) { staat.tekst = it; invoer = false; staat.zoek(ctx) }
    FiltrWybor(filtrOkno, staat, sluit = { filtrOkno = null }) { filtrOkno = null; staat.zoek(ctx) }
}

/** Wybór filtra wyszukiwania: osoba (z imieniem), miejsce (miasta z biblioteki) albo rok. */
@Composable
private fun FiltrWybor(okno: String?, staat: ZoekStaat, sluit: () -> Unit, wybrano: () -> Unit) {
    when (okno) {
        "osoba" -> KiesLijst(
            stringResource(R.string.filtr_osoba),
            listOf(KiesItem("", stringResource(R.string.filtr_dowolna), null, null)) + staat.personen.map { KiesItem(it.id, it.naam, null, Immich.gezicht(it.id), rond = true) },
            staat.filtr.osoba?.id.orEmpty(), sluit = sluit,
        ) { id, _ -> staat.filtr = staat.filtr.copy(osoba = staat.personen.firstOrNull { it.id == id }); wybrano() }
        "miejsce" -> {
            val miejsca by produceState<List<KiesItem>?>(null) {
                value = runCatching { Immich.plaatsen() }.getOrDefault(emptyList())
                    .sortedWith(compareBy(java.text.Collator.getInstance(Taal.locale)) { it.stad })
                    .map { KiesItem(it.stad, it.stad, it.land, Immich.thumb(it.omslag)) }.distinctBy { it.id }
            }
            KiesLijst(
                stringResource(R.string.filtr_miejsce), miejsca?.let { listOf(KiesItem("", stringResource(R.string.filtr_dowolne), null, null)) + it },
                staat.filtr.miasto.orEmpty(), sluit = sluit,
            ) { id, _ -> staat.filtr = staat.filtr.copy(miasto = id.ifEmpty { null }, kraj = null); wybrano() }
        }
        "rok" -> {
            val teraz = java.time.LocalDate.now().year
            KiesLijst(
                stringResource(R.string.filtr_rok),
                listOf(KiesItem("", stringResource(R.string.filtr_dowolny), null, null)) + (teraz downTo 1980).map { KiesItem(it.toString(), it.toString(), null, null) },
                staat.filtr.rok?.toString().orEmpty(), sluit = sluit,
            ) { id, _ -> staat.filtr = staat.filtr.copy(rok = id.toIntOrNull()); wybrano() }
        }
    }
}

@Composable
private fun Suggesties(staat: ZoekStaat, nav: Nav, zoek: (String) -> Unit) {
    val ctx = LocalContext.current
    val recent = remember { Recent.lijst(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
        if (recent.isNotEmpty()) {
            Text(stringResource(R.string.recent_gezocht), style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent) { t ->
                    Keuze(selected = false, onClick = { zoek(t) }, leadingIcon = { Icon(Icons.Filled.History, null, Modifier.width(18.dp)) }, tekst = t)
                }
            }
        }
        Text(stringResource(R.string.probeer), style = MaterialTheme.typography.titleMedium)
        val suggesties = stringArrayResource(R.array.suggesties).toList()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(suggesties) { t ->
                Keuze(selected = false, onClick = { zoek(t) }, tekst = t)
            }
        }
        if (staat.personen.isNotEmpty()) {
            Text(stringResource(R.string.personen), style = MaterialTheme.typography.titleMedium)
            val persoon = stringResource(R.string.persoon)
            // de tien die het vaakst voorkomen (volgorde van Immich: favorieten, dan het aantal foto's) + "Alle personen"
            // (raster met gezichten, zoeken en sorteren); vroeger 30 chips in één rij = veel scrollen
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(staat.personen.take(TOP_PERSONEN), key = { it.id }) { p ->
                    Keuze(
                        selected = false,
                        onClick = { nav.open(Scherm.Tijdlijn(p.naam, persoon, Filter(persoonId = p.id), true, false, nav.scope)) },
                        leadingIcon = { Gezicht(p, 26.dp) }, tekst = p.naam,
                    )
                }
                if (staat.personen.size > TOP_PERSONEN) item(key = "alle") {
                    Keuze(
                        selected = false, onClick = { nav.open(Scherm.Personen(PersonenStaat(staat.personen))) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                        tekst = stringResource(R.string.alle_personen, staat.personen.size),
                    )
                }
            }
        }
    }
}

/**
 * Eigen schermtoetsenbord: het standaard-invoerpaneel van Google TV is de telefoon-brug ("gebruik het toetsenbord op je
 * mobiele apparaat"), niet iets wat je met de afstandsbediening bedient. Alleen a-z en cijfers (slim zoeken en de
 * albumfilter zijn ongevoelig voor accenten); een fysiek toetsenbord typt gewoon mee, met accenten.
 * [live] krijgt elke wijziging (albums filteren tijdens het typen).
 */
@Composable
fun TekstInvoer(titel: String, begin: String, live: ((String) -> Unit)? = null, actie: String = stringResource(R.string.actie_zoeken), wielkie: Boolean = false, sluit: () -> Unit, klaar: (String) -> Unit) {
    Lagen.Open()
    var tekst by remember { mutableStateOf(begin) }
    /** pozycja kursora (0..długość); ◀ ▶ na klawiaturze przesuwają go po wpisanym tekście */
    var kursor by remember { mutableIntStateOf(begin.length) }
    /** 0 = małe litery, 1 = jedna wielka (⇧), 2 = same wielkie (⇧ dwa razy) */
    var shift by remember { mutableIntStateOf(0) }
    /** plansza ze znakami i cyframi (?123) zamiast liter */
    var znaki by remember { mutableStateOf(false) }
    val eerste = remember { FocusRequester() }
    fun zet(t: String, k: Int = t.length) { tekst = t; kursor = k.coerceIn(0, t.length); live?.invoke(t); PisanieTelefonem.ustawTekst(t) }
    fun wstaw(x: String) { zet(tekst.take(kursor) + x + tekst.drop(kursor), kursor + x.length) }
    fun usun() { if (kursor > 0) zet(tekst.take(kursor - 1) + tekst.drop(kursor), kursor - 1) }
    // pisanie z telefonu: kod QR do prostej strony z polem tekstowym (sieć lokalna, tylko gdy to okno jest otwarte)
    val klaarNu = androidx.compose.runtime.rememberUpdatedState(klaar)
    var adresTelefonu by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        adresTelefonu = PisanieTelefonem.start(titel, begin) { t, gotowe -> zet(t); if (gotowe) klaarNu.value(t) }
        onDispose { PisanieTelefonem.stop() }
    }
    // wielkie litery: ⇧, a przy imionach / nazwach albumów sama wielka litera na początku każdego słowa
    val poczatekSlowa = kursor == 0 || tekst.getOrNull(kursor - 1) == ' '
    val duze = shift > 0 || (wielkie && poczatekSlowa)
    fun litera(c: Char) = if (duze && c.isLetter()) c.uppercaseChar() else c
    fun pisz(c: Char) { wstaw(litera(c).toString()); if (shift == 1) shift = 0 }
    /** przytrzymanie OK na klawiszu: warianty (wielka litera, znaki narodowe, cyfra / symbol z tego klawisza) */
    var warianty by remember { mutableStateOf<List<String>?>(null) }
    fun wariantyDla(c: Char): List<String> {
        val narodowe = mapOf(
            'a' to "ąäàáâ", 'c' to "ćç", 'e' to "ęéèêë", 'l' to "ł", 'n' to "ńñ", 'o' to "óöôò", 's' to "śß", 'z' to "żź", 'u' to "üúùû", 'i' to "íîï", 'y' to "ý",
        )
        val cyfry = "qwertyuiop".zip("1234567890").toMap()
        val symbole = "1234567890".zip("!@#$%^&*()").toMap()
        return buildList {
            if (c.isLetter()) { add(c.uppercaseChar().toString()); add(c.lowercaseChar().toString()) }
            narodowe[c.lowercaseChar()]?.forEach { n -> add(n.toString()); if (n.isLetter() && n.uppercaseChar() != n) add(n.uppercaseChar().toString()) }
            cyfry[c.lowercaseChar()]?.let { add(it.toString()) }
            symbole[c]?.let { add(it.toString()) }
            if (c == '.') addAll(listOf(",", "?", "!", ":", ";", "…"))
            if (c == '-') addAll(listOf("_", "–", "/"))
        }.distinct()
    }
    val ctxT = LocalContext.current
    val spraakT = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) wstaw(if (wielkie) t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Taal.locale) } } else t)
    }
    val kanSpraakT = remember { ctxT.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }
    // litery narodowe języka aplikacji (polskie znaki w imionach: Łucja, Józef)
    val extra = when (Taal.code) { "pl" -> "ąćęłńóśźż"; "de" -> "äöüß"; "fr" -> "éèêàçùôî"; "es" -> "ñáéíóúü"; else -> "" }
    BackHandler { sluit() }
    Box(
        Modifier.fillMaxSize().background(Color(0xF00A0A0A)).focusVangen().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = e.nativeKeyEvent
            when {
                n.keyCode == android.view.KeyEvent.KEYCODE_DEL -> { usun(); true }
                n.keyCode == android.view.KeyEvent.KEYCODE_FORWARD_DEL -> { if (kursor < tekst.length) zet(tekst.take(kursor) + tekst.drop(kursor + 1), kursor); true }
                n.keyCode == android.view.KeyEvent.KEYCODE_SEARCH -> { klaar(tekst); true }
                // ⏪ ⏩ i Home/End z pilota lub klawiatury: kursor
                n.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { kursor = (kursor - 1).coerceAtLeast(0); true }
                n.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { kursor = (kursor + 1).coerceAtMost(tekst.length); true }
                n.keyCode == android.view.KeyEvent.KEYCODE_MOVE_HOME -> { kursor = 0; true }
                n.keyCode == android.view.KeyEvent.KEYCODE_MOVE_END -> { kursor = tekst.length; true }
                n.unicodeChar > 0 && n.keyCode !in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER) -> {
                    wstaw(n.unicodeChar.toChar().toString()); true
                }
                else -> false
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        // kod QR „pisz z telefonu” po prawej stronie klawiatury
        adresTelefonu?.let { adres ->
            val qr = remember(adres) { Koppel.qr(adres).asImageBitmap() }
            Column(Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 24.dp).width(180.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(qr, null, Modifier.size(160.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp))
                Text(stringResource(R.string.pisz_telefon), style = MaterialTheme.typography.labelLarge, color = Kleur.zacht,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Column(Modifier.align(Alignment.TopStart).padding(start = 40.dp, top = 40.dp).width(640.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(titel, style = MaterialTheme.typography.headlineSmall)
            // pole z tekstem i kursorem (długi tekst: okno wokół kursora)
            Row(
                Modifier.fillMaxWidth().height(56.dp).background(Kleur.vlakHoog, RoundedCornerShape(28.dp))
                    .border(2.dp, Kleur.primair, RoundedCornerShape(28.dp)).padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Kleur.zacht)
                Spacer(Modifier.width(14.dp))
                val od = (kursor - 34).coerceAtLeast(0)
                val przed = (if (od > 0) "…" else "") + tekst.substring(od, kursor)
                val po = tekst.drop(kursor).let { if (it.length > 40 - (kursor - od)) it.take((40 - (kursor - od)).coerceAtLeast(4)) + "…" else it }
                Text(przed, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false)
                Box(Modifier.width(2.dp).height(26.dp).background(Kleur.primair))
                Text(po, style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false, color = Kleur.tekst)
            }
            // warianty klawisza (po przytrzymaniu OK): wybór wstawia znak; Wstecz zamyka
            warianty?.let { w ->
                val frW = remember(w) { FocusRequester() }
                LaunchedEffect(w) { delay(30); repeat(6) { if (runCatching { frW.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
                BackHandler { warianty = null; (Podreczne.zrodlo ?: eerste).let { f -> runCatching { f.requestFocus() } }; Podreczne.zrodlo = null }
                Row(Modifier.background(Kleur.vlakHoog, RoundedCornerShape(14.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    w.forEachIndexed { i, z ->
                        Toets(z, Modifier.width(54.dp).then(if (i == 0) Modifier.focusRequester(frW) else Modifier), hoofd = true) {
                            wstaw(z); warianty = null; if (shift == 1) shift = 0
                            // fokus z powrotem na klawisz, który przytrzymano
                            (Podreczne.zrodlo ?: eerste).let { f -> runCatching { f.requestFocus() } }; Podreczne.zrodlo = null
                        }
                    }
                }
            }
            Text(stringResource(R.string.toets_przytrzymaj), style = MaterialTheme.typography.labelSmall, color = Kleur.zacht)
            Spacer(Modifier.height(4.dp))
            @Composable
            fun rzad(znakiRzedu: String, przed: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null, po: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null, pierwszy: Boolean = false) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    przed?.invoke(this)
                    znakiRzedu.forEachIndexed { i, c ->
                        val w = wariantyDla(c)
                        Toets(litera(c).toString(), Modifier.weight(1f).then(if (pierwszy && i == 0) Modifier.focusRequester(eerste) else Modifier),
                            dlugi = if (w.size > 1) ({ warianty = w }) else null) { pisz(c) }
                    }
                    po?.invoke(this)
                }
            }
            if (!znaki) {
                rzad("1234567890", pierwszy = true)
                rzad("qwertyuiop")
                rzad("asdfghjkl-")
                rzad("zxcvbnm.,", przed = {
                    Toets(if (shift == 2) "⇪" else "⇧", Modifier.weight(1f), hoofd = shift > 0) { shift = (shift + 1) % 3 }
                })
                if (extra.isNotEmpty()) rzad(extra, po = { repeat((10 - extra.length).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) } })
            } else {
                rzad("1234567890", pierwszy = true)
                rzad("!?@#&*()-_")
                rzad("+=/:;'\".,%")
                rzad("\$€<>[]{}~^")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toets(if (znaki) "ABC" else "?123", Modifier.weight(1.4f), hoofd = znaki) { znaki = !znaki }
                if (kanSpraakT) Toets("🎤", Modifier.weight(1f)) {
                    runCatching {
                        spraakT.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Taal.locale.toLanguageTag()))
                    }
                }
                Toets("◀", Modifier.weight(1f)) { kursor = (kursor - 1).coerceAtLeast(0) }
                Toets(stringResource(R.string.toets_spatie), Modifier.weight(2.6f)) { wstaw(" ") }
                Toets("▶", Modifier.weight(1f)) { kursor = (kursor + 1).coerceAtMost(tekst.length) }
                Toets("⌫", Modifier.weight(1f)) { usun() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Toets("⇤", Modifier.weight(1f)) { kursor = 0 }
                Toets("⇥", Modifier.weight(1f)) { kursor = tekst.length }
                Toets(stringResource(R.string.toets_wissen), Modifier.weight(2f)) { zet("") }
                Toets(actie, Modifier.weight(4f), hoofd = true) { klaar(tekst) }
            }
        }
    }
    // kilka prób: pod spodem bywa okno, które właśnie oddaje fokus
    LaunchedEffect(Unit) { delay(40); repeat(8) { if (runCatching { eerste.requestFocus() }.isSuccess) return@LaunchedEffect; delay(40) } }
}

@Composable
private fun Toets(label: String, modifier: Modifier, hoofd: Boolean = false, dlugi: (() -> Unit)? = null, onKlik: () -> Unit) {
    Surface(
        onClick = onKlik, modifier = modifier.then(if (dlugi != null) Modifier.langeOk(dlugi) else Modifier).height(46.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (hoofd) Kleur.primair.copy(alpha = 0.25f) else Kleur.vlak, contentColor = Kleur.tekst,
            focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.titleMedium) }
    }
}

/** Platte lijst met pagina's (bijv. alle foto's uit één plaats), per dag met jaartal. */
@Composable
fun LijstScherm(scherm: Scherm.Lijst, nav: Nav) {
    val res = scherm.res
    // lista zdjęć jednej osoby (np. sprzed urodzenia): w menu zdjęcia „To nie jest …”, „To jest ktoś inny…”
    scherm.osoba?.let { (oid, imie) ->
        androidx.compose.runtime.DisposableEffect(oid) {
            Podreczne.osobaKontekst = Triple(oid, imie, scherm)
            onDispose { if (Podreczne.osobaKontekst?.third === scherm) { Podreczne.osobaKontekst = null; Podreczne.osobaUr = null } }
        }
        LaunchedEffect(oid) { Podreczne.osobaUr = DatyUrodzin.dla(oid) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val breedte = breedteVoor(maxWidth.value)
        val rijHoogte = Instellingen.rijMaat.hoogte
        val rijen = remember(res.assets, breedte, rijHoogte) { rijenVoor(res.assets, breedte, perDag = scherm.perDag, hoogte = rijHoogte) }
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
            onKlik = { a -> nav.bekijk(bron, res.assets.indexOf(a), scherm.titel, false, res.raster) },
            onPokaz = { a -> nav.bekijk(bron, res.assets.indexOf(a), scherm.titel, true, res.raster) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "boven") {
                SchermKop(scherm.titel, listOfNotNull(scherm.sub, if (res.assets.isNotEmpty()) pluralStringResource(R.plurals.items, res.assets.size, res.assets.size) + (if (res.bezig) "+" else "") else null).joinToString("  ·  ")) {
                    // pokaz w losowej kolejności (najpierw zdjęcia jeszcze niepokazane), np. urodziny z różnych lat
                    if (scherm.losowyPokaz && res.assets.isNotEmpty()) Keuze(
                        selected = false,
                        onClick = {
                            val lista = Pokazane.nowePierwsze(res.assets)
                            Pokazane.zapamietaj(lista.take(30).map { it.id })
                            nav.bekijk(LijstBron(lista), 0, scherm.titel, true, null)
                        },
                        leadingIcon = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Shuffle, null, Modifier.size(18.dp)) },
                        tekst = stringResource(R.string.pokaz_losowo),
                    )
                    scherm.osoba?.takeIf { res.assets.isNotEmpty() && !Instellingen.trybDzieciecy }?.let { (oid, imie) ->
                        // wszystkie naraz: zaznacz (potem przytrzymanie OK = operacje zbiorcze) albo odepnij od razu
                        Keuze(
                            selected = false,
                            onClick = {
                                res.assets.filter { !Udostepnianie.zawiera(it) && Edycja.odpiete[it.id] != true }.forEach { Udostepnianie.przelacz(it) }
                                if (Udostepnianie.koszyk.isNotEmpty()) Udostepnianie.tryb = true
                                Edycja.komunikat = Taal.s(R.string.podreczne_zaznaczone, Udostepnianie.koszyk.size)
                            },
                            leadingIcon = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.DoneAll, null, Modifier.size(18.dp)) },
                            tekst = stringResource(R.string.przed_ur_zaznacz),
                        )
                        Keuze(
                            selected = false,
                            onClick = {
                                val ids = res.assets.filter { Edycja.odpiete[it.id] != true }.map { it.id }
                                if (ids.isNotEmpty()) Podreczne.menu = Taal.s(R.string.przed_ur_odepnij_tytul, imie, ids.size) to listOf(
                                    Podreczne.Pozycja(Taal.s(R.string.przed_ur_odepnij_tak, ids.size)) { Podreczne.odepnijOsobeZ(ids, oid) },
                                    Podreczne.Pozycja(Taal.s(R.string.anuluj)) {},
                                )
                            },
                            leadingIcon = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.PersonOff, null, Modifier.size(18.dp)) },
                            tekst = stringResource(R.string.przed_ur_odepnij),
                        )
                    }
                }
                if (res.fout != null && res.assets.isEmpty()) Text(stringResource(R.string.laden_mislukt, res.fout.orEmpty()), color = Kleur.zacht)
            }
        }
        LijstBalk(res.raster, rijen, res.assets.size, res.bezig, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * Scrollbalk voor een platte lijst (zoekresultaten, plaats): dagkop van de eerste zichtbare rij + "N / M". Bedienbaar:
 * een stap = een dag (rij met kop), of bij een lijst zonder dagen 5 % van de lijst.
 */
@Composable
fun LijstBalk(raster: RasterStaat, rijen: List<Rij>, totaal: Int, bezig: Boolean, modifier: Modifier, boven: Int = 1) {
    if (rijen.isEmpty()) return
    val lijst = raster.lijst
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val eerste = remember(rijen) { eersteFotoPerRij(rijen) }
    fun rijNu() = (lijst.firstVisibleItemIndex - boven).coerceIn(0, rijen.lastIndex)
    fun kopVan(rij: Int): String? {
        var r = rij
        while (r > 0 && (rijen[r] as? RijFotos)?.regel?.metKop != true) r--
        return (rijen[r] as? RijFotos)?.regel?.stukken?.firstOrNull()?.kop
    }
    val stappen = remember(rijen, totaal) {
        val koppen = rijen.indices.filter { (rijen[it] as? RijFotos)?.regel?.metKop == true }
        val ankers = if (koppen.size >= 3) koppen else (0 until 20).map { it * rijen.size / 20 }.distinct()
        BalkStappen(
            aantal = ankers.size,
            fractie = { i -> eerste[ankers[i]].toFloat() / (totaal - 1).coerceAtLeast(1) },
            label = { i -> kopVan(ankers[i]) },
            sub = { i -> "${Taal.getal(eerste[ankers[i]] + 1)} / ${Taal.getal(totaal)}" },
            groot = grootPer(10, ankers.size),
            huidige = { ankers.indexOfLast { it <= rijNu() }.coerceAtLeast(0) },
            ga = { i ->
                val r = ankers[i]
                scope.launch {
                    lijst.scrollToItem(r + boven)
                    raster.focusDoel = (rijen[r] as? RijFotos)?.regel?.stukken?.firstOrNull()?.vakken?.firstOrNull()?.asset?.id
                }
            },
            terug = { raster.focusDoel = raster.laatsteFocus?.id },
        )
    }
    ScrolBalk(
        fractie = { fractieVan(lijst, boven) },
        beweging = { lijst.firstVisibleItemIndex * 7919 + lijst.firstVisibleItemScrollOffset / 200 },
        scrolbaar = { lijst.canScrollForward || lijst.canScrollBackward },
        label = { kopVan(rijNu()) },
        sub = { "${Taal.getal(eerste[rijNu()] + 1)} / ${Taal.getal(totaal)}" + if (bezig) "+" else "" },
        modifier = modifier,
        stappen = stappen,
    )
}

/** Herinneringen van de afgelopen week: per dag de jaren, zoals "Op deze dag" in Immich. */
@Composable
fun HerinneringenScherm(staat: HerinneringStaat, nav: Nav) {
    val eerste = remember { FocusRequester() }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            // wszystkie zdjęcia i filmy ze wspomnień tych dni w jednym pokazie (od najstarszego roku)
            val wszystko = staat.dagen.flatMap { it.second }.sortedBy { it.jaar }.flatMap { it.assets }
            val tytulPokazu = stringResource(R.string.sectie_herinneringen)
            SchermKop(
                stringResource(R.string.sectie_herinneringen),
                listOfNotNull(stringResource(R.string.herinneringen_sub), wszystko.takeIf { it.isNotEmpty() }?.let { licznikMix(it) }).joinToString("  ·  "),
                etykieta = stringResource(R.string.etyk_wspomnienia),
            ) {
                // Rok w skrócie: w styczniu i lutym poprzedni rok, potem bieżący
                val rokPodsumowania = java.time.LocalDate.now().let { if (it.monthValue <= 2) it.year - 1 else it.year }
                Keuze(
                    selected = false, onClick = { nav.open(Scherm.Rok(rokPodsumowania, nav.scope)) },
                    leadingIcon = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.Insights, null, Modifier.size(18.dp)) },
                    tekst = stringResource(R.string.rok_tytul, rokPodsumowania),
                )
                if (wszystko.isNotEmpty()) Keuze(
                    selected = false, onClick = { nav.bekijk(LijstBron(wszystko), 0, tytulPokazu, true, null) },
                    leadingIcon = { androidx.tv.material3.Icon(androidx.compose.material.icons.Icons.Filled.PlayArrow, null, Modifier.size(18.dp)) },
                    tekst = stringResource(R.string.odtworz_wszystkie),
                )
            }
        }
        // wspomnienia z tytułem z serwera (np. „Urodziny Daniel” — jak w aplikacji Immich na telefon)
        if (staat.inne.isNotEmpty()) item(key = "inne") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.wspomnienia_wyroznione), style = MaterialTheme.typography.displaySmall)
                LazyRow(
                    Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                ) {
                    items(staat.inne, key = { it.id }) { h -> HerinneringKaart(h, nav, breedte = 240f, hoogte = 135f, modifier = Modifier.pamiecFokusu("wspomnienia", h.id)) }
                }
            }
        }
        if (staat.klaar && staat.inne.isEmpty() && staat.dagen.all { it.second.isEmpty() }) item { Melding(stringResource(R.string.geen_herinneringen), null) }
        staat.dagen.forEachIndexed { di, (dag, lijst) ->
            if (lijst.isEmpty()) return@forEachIndexed
            item(key = dag.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(dagTitel(dag, false), style = MaterialTheme.typography.displaySmall)
                        Text("   " + licznikMix(lijst.flatMap { it.assets }), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    LazyRow(
                        Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    ) {
                        items(lijst, key = { it.id }) { h ->
                            Box(if (di == staat.dagen.indexOfFirst { it.second.isNotEmpty() } && h == lijst.first()) Modifier.focusRequester(eerste) else Modifier) {
                                HerinneringKaart(h, nav, breedte = 240f, hoogte = 135f, modifier = Modifier.pamiecFokusu("wspomnienia", h.id))
                            }
                        }
                    }
                }
            }
        }
    }
    PamiecEkranu("wspomnienia")
    // start: pierwsza karta — chyba że wracamy, wtedy karta, z której wyszliśmy (pamięć fokusu)
    LaunchedEffect(staat.klaar) { delay(50); if (PamiecFokusu.ostatni("wspomnienia") == null) runCatching { eerste.requestFocus() } }
}

class HerinneringStaat(scope: CoroutineScope) {
    var dagen by mutableStateOf<List<Pair<java.time.LocalDate, List<Herinnering>>>>(emptyList())
    var klaar by mutableStateOf(false)
    /** pozostałe wspomnienia z serwera (np. urodziny z tytułem), których nie ma wśród „tego dnia” z ostatnich 2 tygodni */
    var inne by mutableStateOf<List<Herinnering>>(emptyList())

    init {
        scope.launch {
            runCatching { Immich.wszystkieWspomnienia() }.onSuccess { w ->
                val wDniach = dagen.flatMap { it.second }.map { it.id }.toSet()
                inne = w.filter { it.id !in wDniach && (it.typ != "on_this_day" || it.tytul != null) }
            }.onFailure { android.util.Log.w("Fotos", "wszystkie wspomnienia", it) }
        }
        scope.launch {
            val vandaag = java.time.LocalDate.now()
            // dwa ostatnie tygodnie (Immichin pokazuje tylko dzisiejszy dzień)
            val l = (0..13).map { vandaag.minusDays(it.toLong()) }
            dagen = l.map { it to emptyList() }
            l.forEachIndexed { i, d ->
                runCatching { Immich.herinneringen(d) }.onSuccess { h -> dagen = dagen.toMutableList().also { it[i] = d to h } }
            }
            klaar = true
        }
    }
}
