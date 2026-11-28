package com.immichclient.tv

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type

/** Kleuren w stylu Immichin: granatowo-czarne tło z miękkim, kolorowym poblaskiem. */
object Kleur {
    val achtergrond = Color(0xFF09090F)
    val vlak = Color(0xFF181822)
    val vlakHoog = Color(0xFF242431)
    // accentkleur = instelling (Compose-state): wie hem leest, hercomponeert bij een andere keuze
    val primair get() = Instellingen.accent.primair
    val primairDonker get() = Instellingen.accent.donker
    /** donkere tekst op de accentkleur */
    val opPrimair get() = Instellingen.accent.opPrimair
    val tekst = Color(0xFFF1F1F6)
    val zacht = Color(0xFFA3A3B5)
    /** kolor pinezki miejsca i małych etykiet (jak w Immichin) */
    val pinezka = Color(0xFF9EA3FF)
}

@OptIn(ExperimentalTextApi::class)
private fun overpass(w: Int) =
    Font(R.font.overpass, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Overpass = FontFamily(overpass(400), overpass(500), overpass(600), overpass(700))

/** Szeryfowy krój nagłówków (Instrument Serif, OFL) — „September 2026”, „Albums”. */
val Szeryf = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal))

/** Tekst zwykły: bezszeryfowy systemowy (Roboto), bliski Inter z Immichin. */
private val basis = TextStyle(fontFamily = FontFamily.SansSerif, color = Kleur.tekst)
private val kop = TextStyle(fontFamily = Szeryf, color = Kleur.tekst, fontWeight = FontWeight.Normal)

/** Styl wielkiego tytułu ekranu („Albums”, „Search”). */
val TytulStyl = kop.copy(fontSize = 46.sp, lineHeight = 50.sp, letterSpacing = (-0.3).sp)
/** Mała etykieta nad tytułem („COLLECTIONS”). */
val EtykietaStyl = basis.copy(fontSize = 11.sp, fontWeight = FontWeight(600), letterSpacing = 1.6.sp, color = Kleur.pinezka)

/**
 * Tło całej aplikacji: ciemny granat z rozmytą, kolorową poświatą u góry (każda sekcja ma swój odcień, jak w Immichin:
 * bordo na osi czasu, łupek w albumach, brąz w wyszukiwaniu).
 */
@Composable
fun Tlo(odcien: Color, modifier: Modifier = Modifier) {
    val kolor by androidx.compose.animation.animateColorAsState(odcien, tween(700), label = "tlo")
    Box(
        modifier.fillMaxSize()
            .background(Kleur.achtergrond)
            .background(
                Brush.radialGradient(
                    0f to kolor.copy(alpha = 0.55f), 0.55f to kolor.copy(alpha = 0.18f), 1f to Color.Transparent,
                    center = androidx.compose.ui.geometry.Offset(1100f, -200f), radius = 1900f,
                ),
            )
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0x66000000))),
    )
}

/** Odcień tła per sekcja. */
fun odcienSekcji(s: Sectie): Color = when (s) {
    Sectie.FOTOS, Sectie.FAVORIETEN -> Color(0xFF5A2230)
    Sectie.FILMY -> Color(0xFF1F3A4F)
    Sectie.HERINNERINGEN, Sectie.ROCZNICE, Sectie.ZABAWA -> Color(0xFF4A2A55)
    Sectie.ZOEKEN -> Color(0xFF5C3418)
    Sectie.ALBUMS, Sectie.MAPPEN, Sectie.SYNOLOGY -> Color(0xFF2C3E58)
    Sectie.VERKENNEN, Sectie.LUDZIE -> Color(0xFF22404A)
    Sectie.KAART -> Color(0xFF1E2050)
    Sectie.INSTELLINGEN, Sectie.PORZADKI -> Color(0xFF2A2A36)
}

/**
 * Logo „Album Rodzinny”: wachlarz trzech zdjęć (koralowe, morskie, bursztynowe) z białym serduszkiem na przednim.
 * Ten sam znak rysuje banner_album_rodzinny.py dla banera i ikony launchera.
 */
@Composable
fun Logo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(30.dp)) {
        val g = size.width
        val kw = g * 0.56f; val kh = g * 0.68f
        val r = androidx.compose.ui.geometry.CornerRadius(g * 0.09f)
        fun karta(c: Color, kat: Float, dx: Float) = rotate(kat, pivot = androidx.compose.ui.geometry.Offset(g / 2 + dx * g, g * 0.92f)) {
            drawRoundRect(c, androidx.compose.ui.geometry.Offset(g / 2 - kw / 2 + dx * g, g * 0.16f), androidx.compose.ui.geometry.Size(kw, kh), r)
        }
        karta(Color(0xFFEF5B5B), -16f, -0.08f)
        karta(Color(0xFF34B3A0), 16f, 0.08f)
        karta(Color(0xFFF5B83D), 0f, 0f)
        // serduszko na przedniej karcie
        val cx = g / 2; val cy = g * 0.50f; val s = g * 0.15f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, cy + s * 0.95f)
            cubicTo(cx - s * 1.5f, cy + s * 0.05f, cx - s * 0.95f, cy - s * 0.95f, cx, cy - s * 0.35f)
            cubicTo(cx + s * 0.95f, cy - s * 0.95f, cx + s * 1.5f, cy + s * 0.05f, cx, cy + s * 0.95f)
            close()
        }
        drawPath(p, Color.White)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FotosThema(inhoud: @Composable () -> Unit) {
    // één scrollgedrag voor alle lijsten en rasters (tijdlijn, albums, verkennen, zoeken, rijen): zie focusOp30
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides focusOp30) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kleur.primair, onPrimary = Kleur.opPrimair,
            background = Kleur.achtergrond, surface = Kleur.vlak, onSurface = Kleur.tekst,
            surfaceVariant = Kleur.vlakHoog, onSurfaceVariant = Kleur.zacht, border = Kleur.primair,
        ),
        typography = Typography(
            displayLarge = kop.copy(fontSize = 46.sp, lineHeight = 50.sp),
            displayMedium = kop.copy(fontSize = 34.sp, lineHeight = 38.sp),
            displaySmall = kop.copy(fontSize = 30.sp, lineHeight = 34.sp),
            headlineSmall = kop.copy(fontSize = 24.sp, lineHeight = 28.sp),
            titleLarge = basis.copy(fontSize = 20.sp, fontWeight = FontWeight(600)),
            titleMedium = basis.copy(fontSize = 16.sp, fontWeight = FontWeight(600)),
            titleSmall = basis.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
            bodyLarge = basis.copy(fontSize = 16.sp),
            bodyMedium = basis.copy(fontSize = 14.sp),
            bodySmall = basis.copy(fontSize = 12.sp, color = Kleur.zacht),
            labelLarge = basis.copy(fontSize = 14.sp, fontWeight = FontWeight(600)),
            labelMedium = basis.copy(fontSize = 12.sp, fontWeight = FontWeight(600)),
            labelSmall = basis.copy(fontSize = 11.sp, fontWeight = FontWeight(500)),
        ),
        content = inhoud,
    )
    }
}

/** Beeldverzoek met vaste cachesleutel (de URL zonder sleutel); thumbnails zonder schaling, die zijn al klein. */
@Composable
fun beeld(url: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(url) { ImageRequest.Builder(ctx).data(url).size(Size.ORIGINAL).crossfade(90).build() }
}

/**
 * Preview (1440 px) voor de viewer, altijd met dezelfde vaste maat zodat vooraf laden en tonen één cache-item delen;
 * nooit groter dan het scherm gedecodeerd.
 */
fun previewVerzoek(ctx: android.content.Context, id: String): ImageRequest =
    ImageRequest.Builder(ctx).data(Immich.preview(id)).size(1920, 1080).precision(Precision.INEXACT).build()

@Composable
fun preview(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { previewVerzoek(ctx, id) }
}

/** Preview voor een kaart (herinneringen): gedecodeerd op de helft (720 px, 1,5 MB) in plaats van 6 MB per kaart. */
@Composable
fun kaartBeeld(id: String): ImageRequest {
    val ctx = LocalContext.current
    return remember(id) { ImageRequest.Builder(ctx).data(Immich.preview(id)).size(400, 500).precision(Precision.INEXACT).crossfade(120).build() }
}

/** Eén foto in een raster: thumbhash meteen, de echte thumbnail eroverheen zodra hij er is. */
@Composable
fun FotoVak(
    asset: Asset,
    breedte: Dp,
    hoogte: Dp,
    onKlik: () -> Unit,
    modifier: Modifier = Modifier,
    focusDoel: Boolean = false,
    onFocusDoelKlaar: () -> Unit = {},
    onFocus: () -> Unit = {},
    vorm: Shape = RoundedCornerShape(12.dp),
    /** przytrzymanie OK: menu podręczne zdjęcia */
    onDlugi: (() -> Unit)? = null,
) {
    val fr = remember { FocusRequester() }
    if (focusDoel) LaunchedEffect(asset.id) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusDoelKlaar(); return@LaunchedEffect }; delay(16) }
        onFocusDoelKlaar()
    }
    // Bewust geen tv-Surface: daarvan staan er tientallen tegelijk in beeld en bij snel scrollen telt elke ms.
    var focus by remember { mutableStateOf(false) }
    val schaal by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "schaal")
    // Focus en klik staan vóór de graphicsLayer: anders rekent "bring into view" met de geschaalde rechthoek, die tijdens
    // de schaalanimatie verandert, en schuift de hele lijst bij elke focuswissel een paar px heen en weer.
    // zIndex: de vergrote foto (en zijn witte rand) boven de buurfoto's tekenen.
    Box(
        modifier.size(breedte, hoogte)
            .zIndex(if (focus) 1f else 0f)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .then(if (onDlugi != null) Modifier.langeOk(onDlugi) else Modifier)
            // tryb zaznaczania: OK zaznacza / odznacza zamiast otwierać
            .clickable(interactionSource = null, indication = null, onClick = { if (Udostepnianie.tryb && !Synology.jestSyno(asset.id)) Udostepnianie.przelacz(asset) else onKlik() })
            .graphicsLayer {
                scaleX = schaal; scaleY = schaal; shape = vorm; clip = true
                // zachte blauwe gloed rond de gekozen foto (schaduw van de laag: één RenderNode-eigenschap, geen extra tekenwerk)
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = Kleur.primair; ambientShadowColor = Kleur.primair
            }
            .background(Kleur.vlak),
    ) {
        val hash = remember(asset.thumbhash) { asset.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null) Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        AsyncImage(beeld(Immich.thumb(asset.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (asset.video) VideoLabel(asset, Modifier.align(Alignment.TopEnd))
        else if (asset.live != null) Icon(
            androidx.compose.material.icons.Icons.Filled.MotionPhotosOn, null,
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color.White,
        )
        if (Ulubione.jest(asset)) Icon(
            Icons.Filled.Favorite, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(16.dp), tint = Color.White,
        )
        // oś czasu osoby: zdjęcie sprzed jej urodzenia — prawie na pewno błędne rozpoznanie (albo zła data zdjęcia)
        if (asset.przedUrodzeniem(Podreczne.osobaUr) && Edycja.odpiete[asset.id] != true) {
            Box(Modifier.matchParentSize().background(Color(0x59000000)))
            Icon(androidx.compose.material.icons.Icons.Filled.Warning, null, Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp)
                .background(Color(0xCC101018), androidx.compose.foundation.shape.CircleShape).padding(3.dp), tint = Color(0xFFF5B83D))
        }
        // odpięte od osoby (korekta z osi czasu osoby): mocno przyciemnione z ikoną
        if (Edycja.odpiete[asset.id] == true) {
            Box(Modifier.matchParentSize().background(Color(0xB3000000)))
            Icon(androidx.compose.material.icons.Icons.Filled.PersonOff, null, Modifier.align(Alignment.Center).size(30.dp), tint = Color(0xCCFFFFFF))
        }
        // zaznaczone: przyciemnienie, złota ramka i duży znaczek — widać z kanapy, także po wielu zdjęciach
        if (Udostepnianie.zawiera(asset)) {
            Box(Modifier.matchParentSize().background(Color(0x66000000)).border(4.dp, Color(0xFFF5B83D), vorm))
            Icon(androidx.compose.material.icons.Icons.Filled.CheckCircle, null, Modifier.align(Alignment.TopStart).padding(6.dp).size(28.dp)
                .background(Color(0xFF101018), androidx.compose.foundation.shape.CircleShape), tint = Color(0xFFF5B83D))
        } else if (Udostepnianie.tryb) Icon(
            androidx.compose.material.icons.Icons.Filled.RadioButtonUnchecked, null, Modifier.align(Alignment.TopStart).padding(6.dp).size(26.dp), tint = Color(0xCCFFFFFF),
        )
        if (focus) Box(Modifier.matchParentSize().border(3.dp, Color.White, vorm))
    }
}

@Composable
private fun VideoLabel(a: Asset, modifier: Modifier) {
    Row(
        modifier.padding(6.dp).background(Color(0x99000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        duurKort(a.duur)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White) }
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp), tint = Color.White)
    }
}

/**
 * Chip (sorteren, filters): leesbaar in elke toestand, ook gekozen + gefocust. De tekstkleur staat hier expliciet: de
 * tekststijlen van het thema hebben een eigen kleur (Kleur.tekst) en die wint van de content-kleur van de chip, dus
 * met alleen `FilterChipDefaults.colors` bleef de tekst lichtgrijs op de lichte accentkleur (onleesbaar bij focus).
 */
@Composable
fun Keuze(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    tekst: String,
) {
    val donker = Kleur.opPrimair
    var focus by remember { mutableStateOf(false) }
    val kleur = when { focus -> donker; selected -> Kleur.primair; else -> Kleur.tekst }
    FilterChip(
        selected = selected, onClick = onClick, modifier = modifier.onFocusChanged { focus = it.isFocused }, leadingIcon = leadingIcon,
        colors = FilterChipDefaults.colors(
            containerColor = Color.Transparent, contentColor = Kleur.tekst,
            focusedContainerColor = Kleur.primair, focusedContentColor = donker,
            selectedContainerColor = Kleur.primair.copy(alpha = 0.22f), selectedContentColor = Kleur.primair,
            focusedSelectedContainerColor = Kleur.primair, focusedSelectedContentColor = donker,
            pressedContainerColor = Kleur.primair, pressedContentColor = donker,
            pressedSelectedContainerColor = Kleur.primair, pressedSelectedContentColor = donker,
        ),
        content = { Text(tekst, color = kleur, maxLines = 1) },
    )
}

/** Ringetje dat volloopt (diashow, screensaver): [deel] 0..1. */
@Composable
fun VoortgangRing(deel: () -> Float, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(22.dp)) {
        val dikte = 2.2.dp.toPx()
        // donkere rand eronder: ook op een lichte foto te zien
        drawCircle(Color(0x59000000), style = androidx.compose.ui.graphics.drawscope.Stroke(dikte * 2.4f))
        drawCircle(Color(0x38FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(dikte))
        drawArc(Color(0xD9FFFFFF), -90f, 360f * deel(), false, style = androidx.compose.ui.graphics.drawscope.Stroke(dikte, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

/** Ronde gezichtsfoto van een persoon. */
@Composable
fun Gezicht(p: Persoon, grootte: Dp) {
    Box(Modifier.size(grootte).clip(CircleShape).background(Kleur.vlakHoog)) {
        AsyncImage(beeld(Immich.gezicht(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** liczba otwartych warstw z [focusVangen] (stos: wyższa warstwa może przejąć fokus od niższej) */
object Pulapki { var ile by androidx.compose.runtime.mutableIntStateOf(0) }

/**
 * Voor lagen over een scherm (toetsenbord, datumkiezer): focus blijft binnen de laag. Wyjątek: gdy nad tą warstwą
 * otworzy się kolejna (menu, lista wyboru, klawiatura), fokus może do niej przejść — wcześniej niższa warstwa go
 * „więziła” i przycisków nowego okna nie dało się wybrać (np. „Kto to jest?” → „To jest…”).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.compose.runtime.Composable
fun Modifier.focusVangen(): Modifier {
    val ponizej = androidx.compose.runtime.remember { Pulapki.ile }
    androidx.compose.runtime.DisposableEffect(Unit) { Pulapki.ile++; onDispose { Pulapki.ile-- } }
    return this
        .focusProperties { exit = { if (Pulapki.ile > ponizej + 1) androidx.compose.ui.focus.FocusRequester.Default else androidx.compose.ui.focus.FocusRequester.Cancel } }
        .focusGroup()
}

/** ▲▼ scrollen een lange tekstlaag (verticalScroll heeft zelf geen toetsbediening). */
@Composable
fun Modifier.toetsScroll(scroll: androidx.compose.foundation.ScrollState): Modifier {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this.onPreviewKeyEvent { e ->
        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val stap = when (e.key.nativeKeyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 220f
            android.view.KeyEvent.KEYCODE_DPAD_UP -> -220f
            else -> return@onPreviewKeyEvent false
        }
        scope.launch { scroll.animateScrollBy(stap) }
        true
    }
}

/** Donker verloop onder tekst op een foto. */
val onderVerloop = Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xD9000000))
