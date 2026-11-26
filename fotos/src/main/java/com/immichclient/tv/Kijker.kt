package com.immichclient.tv

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.SkipPrevious
import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Test (`--ez video_zonder_placeholder true`): doet alsof dit apparaat geen placeholder-surface mag gebruiken, zoals
 * Media3 dat voor o.a. veel Fire TV-modellen beslist (codecNeedsSetOutputSurfaceWorkaround). Dan start de decoder pas
 * als er een echt oppervlak is.
 */
@Volatile var videoZonderPlaceholder = false

class KijkerStaat(val bron: BeeldBron, start: Int, val titel: String?, diashow: Boolean) {
    var index by mutableIntStateOf(start)
    var richting = 1
    var diashow by mutableStateOf(diashow)
    var info by mutableStateOf(false)
    /** laatste toetsdruk; de balk onderin verdwijnt na een paar seconden */
    var actief by mutableLongStateOf(System.currentTimeMillis())
    var speeltVideo by mutableStateOf(false)
    var videoFout by mutableStateOf(false)
    /** szczegóły niezgodnego filmu („HEVC 3840×2160 · brak dekodera”), pokazywane pod komunikatem */
    var videoFoutOpis by mutableStateOf<String?>(null)
    /** Live Photo: odtwarzany właśnie krótki film zdjęcia */
    var liveGra by mutableStateOf(false)
    /** krótki komunikat na dole ekranu (np. brak uprawnienia do ulubionych) */
    var komunikat by mutableStateOf<String?>(null)
    /** otwarty wybór albumu dla tych zdjęć (▼ → Do albumu) */
    var doAlbumu by mutableStateOf<List<String>?>(null)
    /** „Osoby na tym zdjęciu” (korekta twarzy) otwarte */
    var twarze by mutableStateOf(false)
    /** zdjęcia usunięte z albumu w tej przeglądarce (przycisk zmienia się na „Przywróć”) */
    val usunieteZAlbumu = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var speler: ExoPlayer? = null
    /** chwila ostatniego przewinięcia filmu (pasek czasu jest wtedy widoczny przez kilka sekund) */
    var przewinieto by mutableLongStateOf(0L)
    /** pozycja docelowa przewijania: kolejne kroki sumują się, zanim odtwarzacz zdąży przeskoczyć (pokazywana na pasku) */
    var przewinDoel by mutableLongStateOf(0L)
        private set
    private var ostatniSkok = 0L
    /** strzałka wciśnięta w filmie (kod klawisza): krótko = poprzedni / następny, przytrzymana = przewijanie */
    var strzalka = 0
    var strzalkaPrzewija = false

    /** Przewiń film o [deltaMs] (ujemne = do tyłu), w granicach filmu; odtwarzacz skacze najwyżej co 300 ms. */
    fun przewin(deltaMs: Long) {
        val sp = speler ?: return
        val teraz = System.currentTimeMillis()
        val baza = if (teraz - przewinieto < 1200) przewinDoel else sp.currentPosition
        val dl = sp.duration.takeIf { it > 0 }
        przewinDoel = (baza + deltaMs).coerceAtLeast(0L).let { if (dl != null) it.coerceAtMost((dl - 500).coerceAtLeast(0L)) else it }
        if (teraz - ostatniSkok > 300) { sp.seekTo(przewinDoel); ostatniSkok = teraz }
        przewinieto = teraz
        actief = teraz
    }

    /** Koniec przewijania: skok dokładnie do pozycji docelowej. */
    fun przewinKoniec() {
        speler?.seekTo(przewinDoel)
        przewinieto = System.currentTimeMillis()
    }
    /** dolny pasek akcji (▼): pokaz, szczegóły, powiększ, mapa */
    var pasek by mutableStateOf(false)
    var pasekWybor by mutableIntStateOf(0)
    /** powiększenie zdjęcia (1 = brak); przesunięcie w px względem środka */
    var zoom by mutableFloatStateOf(1f)
    var przesX by mutableFloatStateOf(0f)
    var przesY by mutableFloatStateOf(0f)

    fun zoomUstaw(z: Float, szer: Float, wys: Float) {
        zoom = z.coerceIn(1f, ZOOM_MAX)
        if (zoom == 1f) { przesX = 0f; przesY = 0f } else przesun(0f, 0f, szer, wys)
    }

    /** Przesuń widok o (dx, dy) px ekranu, w granicach powiększonego zdjęcia. */
    fun przesun(dx: Float, dy: Float, szer: Float, wys: Float) {
        // granice wg zdjęcia, nie ekranu: pionowe zdjęcie jest węższe od ekranu, więc nie wolno wyjechać w czarne pasy
        val r = bron.op(index)?.ratio?.takeIf { it > 0f } ?: (szer / wys)
        val zdjW = minOf(szer, wys * r)
        val zdjH = minOf(wys, szer / r)
        val maxX = ((zoom * zdjW - szer) / 2f).coerceAtLeast(0f)
        val maxY = ((zoom * zdjH - wys) / 2f).coerceAtLeast(0f)
        przesX = (przesX + dx).coerceIn(-maxX, maxX)
        przesY = (przesY + dy).coerceIn(-maxY, maxY)
    }

    companion object { const val ZOOM_MAX = 4f }
    /** volgende foto van de diashow, alvast gekozen (en vooraf geladen) zodra de huidige in beeld staat */
    var diaVolgende = -1

    /** willekeurige diashow zonder herhaling (zie [Schudder]) */
    private val schudder = Schudder()

    /** Volgende foto voor de diashow: op volgorde of willekeurig, video's zo nodig overgeslagen; -1 = klaar. */
    fun diaDoel(): Int {
        val n = bron.aantal
        if (Instellingen.diaWillekeurig)
            return schudder.volgende(n, index) { Instellingen.diaVideos || bron.op(it)?.video != true }
        var i = index + 1
        while (i < n && !Instellingen.diaVideos && bron.op(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Diashow een stap verder (of stoppen aan het eind). */
    fun diaVerder() {
        val d = diaVolgende.takeIf { it >= 0 && it != index && it < bron.aantal } ?: diaDoel()
        diaVolgende = -1
        if (d < 0) { diashow = false; return }
        richting = if (Instellingen.diaSchuiven) 1 else 0
        index = d
        if (Instellingen.diaWillekeurig) schudder.markeer(d)
    }
}

@Composable
fun Kijker(staat: KijkerStaat, opKaart: ((Asset) -> Unit)? = null, sluit: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val huidig = staat.bron.op(staat.index)
    BackHandler(enabled = staat.doAlbumu == null && !staat.twarze) {
        when {
            staat.zoom > 1f -> { staat.zoom = 1f; staat.przesX = 0f; staat.przesY = 0f }
            staat.pasek -> staat.pasek = false
            staat.info -> staat.info = false
            else -> sluit(staat.bron.op(staat.index))
        }
    }
    // nowe zdjęcie = bez powiększenia
    LaunchedEffect(staat.index) { staat.zoom = 1f; staat.przesX = 0f; staat.przesY = 0f }
    // pasek akcji chowa się sam po chwili bez klawiszy
    LaunchedEffect(staat.pasek, staat.actief) { if (staat.pasek) { delay(15_000); staat.pasek = false } }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // po zamknięciu ekranu udostępniania fokus wraca do przeglądarki
    LaunchedEffect(Udostepnianie.otwarte) { if (!Udostepnianie.otwarte) { delay(50); runCatching { fr.requestFocus() } } }
    LaunchedEffect(staat.doAlbumu == null && !staat.twarze) { if (staat.doAlbumu == null && !staat.twarze) { delay(50); runCatching { fr.requestFocus() } } }
    // album, z którego coś usunięto: po zamknięciu przeglądarki jego oś czasu wczytuje się od nowa
    DisposableEffect(Unit) {
        onDispose { (staat.bron as? TijdlijnStaat)?.let { t -> t.filter.albumId?.let { if (Edycja.doOdswiezenia.remove(it)) t.herlaad() } } }
    }
    val albumZrodla = (staat.bron as? TijdlijnStaat)?.filter?.albumId
    // Tijdens een diashow het scherm aan houden: anders start de systeem-screensaver (op o.a. de Google TV
    // Streamer niet uit te zetten) midden in de diashow. Gepauzeerd of gestopt geldt weer de gewone timeout.
    val view = LocalView.current
    DisposableEffect(staat.diashow) {
        view.keepScreenOn = staat.diashow
        onDispose { view.keepScreenOn = false }
    }

    fun ga(d: Int) {
        val n = (staat.index + d)
        if (n < 0 || n >= staat.bron.aantal) return
        // video meteen stoppen (buffers vrij) in plaats van pas na de overgang: minder piek naast de nieuwe preview
        if (huidig?.video == true) { staat.speler?.stop(); staat.speeltVideo = false }
        staat.richting = d
        staat.index = n
        staat.actief = System.currentTimeMillis()
    }

    // Buren vooraf laden, zuinig: eerst de volgende, dan de vorige, en pas als je blijft nog eentje verder.
    // Niet tijdens een video (de speler heeft het geheugen nodig); previews ≈ 6 MB per stuk als hardware-bitmap.
    LaunchedEffect(staat.index, staat.speeltVideo, staat.diashow) {
        if (staat.speeltVideo) return@LaunchedEffect
        // willekeurige diashow: de buren zijn dan niet de volgende; die laadt de diashow-lus zelf vooraf
        if (staat.diashow && Instellingen.diaWillekeurig) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun laad(d: Int) { staat.bron.op(staat.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewVerzoek(ctx, it.id)) } }
        laad(1)
        delay(300); laad(-1)
        delay(700); laad(2)
    }
    // diashow: volgende alvast kiezen en vooraf laden (ook bij willekeurig of overgeslagen video's), dan verder
    LaunchedEffect(staat.diashow, staat.index, staat.speeltVideo) {
        if (!staat.diashow || staat.speeltVideo) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val doel = staat.diaDoel()
        staat.diaVolgende = doel
        if (doel >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // de maand van een willekeurige foto is misschien nog niet geladen (op() start dat): even later nog eens
            for (poging in 0 until 3) {
                val a = staat.bron.op(doel)
                if (a != null) {
                    if (!a.video) loader.enqueue(previewVerzoek(ctx, a.id))
                    // twarze następnego zdjęcia z wyprzedzeniem: punkt przybliżenia gotowy od pierwszej klatki
                    if (!a.video && Instellingen.kenBurns && Instellingen.kenBurnsTwarze) launch { Immich.srodekTwarzy(a.id) }
                    break
                }
                delay(500)
            }
        }
        delay((Instellingen.diaMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        staat.diaVerder()
    }

    val ekran = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp.dp.toPx() to it.screenHeightDp.dp.toPx() } }
    val akcje = akcjeDla(huidig, opKaart != null, albumZrodla != null)
    val zakres = androidx.compose.runtime.rememberCoroutineScope()
    fun wykonaj(ak: Akcja) {
        // ulubione i udostępnianie zostawiają pasek otwarty (widać zmianę na przycisku)
        if (ak != Akcja.ULUBIONE && ak != Akcja.UDOSTEPNIJ && ak != Akcja.Z_ALBUMU) staat.pasek = false
        when (ak) {
            Akcja.POKAZ -> staat.diashow = !staat.diashow
            Akcja.POPRZEDNI -> ga(-1)
            Akcja.NASTEPNY -> ga(1)
            Akcja.SZCZEGOLY -> staat.info = !staat.info
            Akcja.POWIEKSZ -> { staat.diashow = false; staat.info = false; staat.zoomUstaw(2f, ekran.first, ekran.second) }
            Akcja.MAPA -> if (opKaart != null && huidig != null) opKaart(huidig)
            Akcja.ULUBIONE -> if (huidig != null) zakres.launch { Ulubione.przelacz(huidig)?.let { staat.komunikat = ctx.getString(it) } }
            Akcja.LIVE -> { staat.diashow = false; staat.liveGra = true }
            Akcja.UDOSTEPNIJ -> if (huidig != null) Udostepnianie.przelacz(huidig)
            Akcja.KOD_QR -> { staat.diashow = false; Udostepnianie.otworz(ctx) }
            Akcja.ALBUM -> if (huidig != null) {
                staat.diashow = false
                // zaznaczone (koszyk z „Zaznacz”) razem z bieżącym; bez zaznaczenia samo bieżące zdjęcie
                staat.doAlbumu = (Udostepnianie.koszyk.map { it.id } + huidig.id).distinct()
            }
            Akcja.TWARZE -> if (huidig != null) { staat.diashow = false; staat.twarze = true }
            Akcja.WYCZYSC -> { Udostepnianie.wyczysc(); staat.komunikat = ctx.getString(R.string.zazn_wyczyszczono) }
            // to zdjęcie jako okładka albumu (w Immich i w aplikacji)
            Akcja.OKLADKA -> if (huidig != null && albumZrodla != null) {
                Edycja.zakres.launch {
                    runCatching { Immich.ustawOkladke(albumZrodla, huidig.id) }
                        .onSuccess { Edycja.wersjaAlbumow++; staat.komunikat = ctx.getString(R.string.album_okladka_ustawiona) }
                        .onFailure { staat.komunikat = Edycja.blad(it) }
                }
            }
            Akcja.Z_ALBUMU -> if (huidig != null && albumZrodla != null) {
                val bylo = staat.usunieteZAlbumu[huidig.id] == true
                zakres.launch {
                    runCatching { if (bylo) Immich.dodajDoAlbumu(albumZrodla, listOf(huidig.id)) else Immich.usunZAlbumu(albumZrodla, listOf(huidig.id)) }
                        .onSuccess {
                            staat.usunieteZAlbumu[huidig.id] = !bylo
                            Edycja.doOdswiezenia.add(albumZrodla); Edycja.wersjaAlbumow++
                            staat.komunikat = ctx.getString(if (bylo) R.string.album_przywrocono else R.string.album_usunieto)
                        }
                        .onFailure { staat.komunikat = Edycja.blad(it) }
                }
            }
        }
    }
    // Live Photo: krótki film odtwarza się sam raz po otwarciu zdjęcia (nie w trakcie pokazu)
    LaunchedEffect(staat.index) { staat.liveGra = huidig?.live != null && !staat.diashow }
    LaunchedEffect(staat.komunikat) { if (staat.komunikat != null) { delay(3500); staat.komunikat = null } }
    // muzyka w tle pokazu slajdów (Ustawienia → Pokaz slajdów → Muzyka)
    MuzykaPokazu(grac = staat.diashow && !staat.speeltVideo)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr)
            // przytrzymanie OK: szybko do ulubionych / z ulubionych (krótkie OK z początku naciśnięcia cofamy)
            .langeOk {
                val h = huidig
                if (h == null || staat.pasek || staat.info || staat.zoom > 1f || Synology.jestSyno(h.id)) return@langeOk
                val sp = staat.speler
                if (h.video && sp != null && !staat.diashow) sp.playWhenReady = !sp.playWhenReady else staat.diashow = !staat.diashow
                Edycja.zakres.launch { Ulubione.przelacz(h)?.let { staat.komunikat = ctx.getString(it) } }
            }
            .focusable().onPreviewKeyEvent { e ->
            if (staat.doAlbumu != null || staat.twarze) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            // --- film (poza pokazem slajdów): ◀ ▶ krótko = poprzedni / następny, przytrzymane = przewijanie. Działa dopiero
            // po własnym wciśnięciu w filmie (puszczenie strzałki, którą tu przyszliśmy ze zdjęcia, nic nie robi) ---
            val sp = staat.speler
            if ((k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && huidig?.video == true && sp != null &&
                !staat.diashow && !staat.videoFout && !staat.info && !staat.pasek) {
                val kier = if (k == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                staat.actief = System.currentTimeMillis()
                if (e.type == KeyEventType.KeyDown) {
                    val powt = e.nativeKeyEvent.repeatCount
                    if (powt == 0) { staat.strzalka = k; staat.strzalkaPrzewija = false }
                    else if (staat.strzalka == k) {
                        // ok. 20 powtórzeń na sekundę: cały film w ~6 s, po 3 s trzymania dwa razy szybciej
                        val dl = sp.duration.takeIf { it > 0 } ?: 600_000L
                        val krok = (dl / 120).coerceIn(1_000L, 60_000L) * (if (powt > 60) 2 else 1)
                        staat.strzalkaPrzewija = true
                        staat.przewin(kier * krok)
                    }
                } else if (e.type == KeyEventType.KeyUp && staat.strzalka == k) {
                    staat.strzalka = 0
                    if (staat.strzalkaPrzewija) { staat.strzalkaPrzewija = false; staat.przewinKoniec() } else ga(kier)
                }
                return@onPreviewKeyEvent true
            }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            staat.actief = System.currentTimeMillis()
            // --- tryb powiększenia: strzałki przesuwają, OK/⏩ bliżej, ⏪ dalej ---
            if (staat.zoom > 1f) {
                val krok = 0.18f
                val (sz, wy) = ekran
                return@onPreviewKeyEvent when (k) {
                    // w prawo = pokaż prawą część zdjęcia = przesuń obraz w lewo
                    KeyEvent.KEYCODE_DPAD_LEFT -> { staat.przesun(sz * krok, 0f, sz, wy); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.przesun(-sz * krok, 0f, sz, wy); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { staat.przesun(0f, wy * krok, sz, wy); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { staat.przesun(0f, -wy * krok, sz, wy); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                        { staat.zoomUstaw(if (staat.zoom >= KijkerStaat.ZOOM_MAX) 1f else staat.zoom + 1f, sz, wy); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { staat.zoomUstaw(staat.zoom - 1f, sz, wy); true }
                    else -> false
                }
            }
            // --- pasek akcji: ◀ ▶ wybór, OK wykonaj, ▲ schowaj ---
            if (staat.pasek) {
                return@onPreviewKeyEvent when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { staat.pasekWybor = (staat.pasekWybor - 1).coerceAtLeast(0); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.pasekWybor = (staat.pasekWybor + 1).coerceAtMost(akcje.lastIndex); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { staat.pasek = false; true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { akcje.getOrNull(staat.pasekWybor)?.let(::wykonaj); true }
                    else -> false
                }
            }
            // poprzedni / następny element zawsze: kanał ▲▼, ⏮ ⏭, strona w górę / w dół
            when (k) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> { staat.diashow = false; ga(1); return@onPreviewKeyEvent true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> { staat.diashow = false; ga(-1); return@onPreviewKeyEvent true }
            }
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { staat.diashow = false; ga(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.diashow = false; ga(1); true }
                // ▼ = pasek akcji (przy otwartych szczegółach: zamknij je)
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (staat.info) staat.info = false
                    else { staat.pasek = true; staat.pasekWybor = akcje.indexOf(Akcja.POKAZ).coerceAtLeast(0) }
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    staat.info = !staat.info; true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = staat.speler
                    // infopaneel open bij een foto met plek: OK springt naar de wereldkaart
                    if (staat.info && opKaart != null && huidig != null && huidig.lat != null) opKaart(huidig)
                    else if (huidig?.video == true && sp != null && !staat.diashow) sp.playWhenReady = !sp.playWhenReady
                    else staat.diashow = !staat.diashow
                    staat.actief = System.currentTimeMillis(); true
                }
                // ⏩ / ⏪: in een video 10 s, anders 10 foto's
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (huidig?.video == true && staat.speler != null) { staat.przewin(30_000); staat.przewinKoniec() } else ga(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { if (huidig?.video == true && staat.speler != null) { staat.przewin(-30_000); staat.przewinKoniec() } else ga(-10); true }
                else -> false
            }
        },
    ) {
        val dicht = LocalDensity.current
        val breedtePx = with(dicht) { maxWidth.roundToPx() }
        val hoogtePx = with(dicht) { maxHeight.roundToPx() }

        // Bewust geen fadeIn/fadeOut hier: die zetten een schermvullende offscreen-laag (8 MB) per pagina op en HWUI houdt
        // die lagen in zijn cache. De crossfade zit in Beeld (ModulateAlpha = alpha per tekenopdracht, zonder laag);
        // de slide/zoom bepaalt alleen hoe lang de oude pagina nog bestaat.
        AnimatedContent(
            targetState = staat.index,
            transitionSpec = {
                when (staat.richting) {
                    // diashow: de nieuwe foto vloeit erin en 'landt' met een kleine zoom
                    0 -> scaleIn(tween(1400), initialScale = 1.03f) togetherWith scaleOut(tween(900), targetScale = 1f)
                    else -> slideInHorizontally(tween(260)) { it / 6 * staat.richting.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 6 * staat.richting.coerceIn(-1, 1) }
                }
            },
            label = "foto",
        ) { i ->
            val a = staat.bron.op(i)
            val zicht by transition.animateFloat(transitionSpec = { tween(if (staat.richting == 0) 900 else 200) }, label = "zicht") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) Beeld(a, staat, actief = i == staat.index, zicht = { zicht })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht) }
        }

        // Landkaart (zoals in een fotolijst-screensaver): alleen bij foto's met GPS, niet bij video of info. De laag zelf wacht even
        // bij een nieuw land en laat bij hetzelfde land alleen de stip glijden. Instelling: uit / klein / groot.
        // "alleen de foto" tijdens een lopende diashow: geen kaart, balk, badge of ringetje (gepauzeerd komt alles terug)
        val schoon = staat.diashow && Instellingen.diaBeeld == DiaBeeld.ALLEEN_FOTO
        val kaartMaat = Instellingen.viewerKaart.schaal
        KaartLaag(
            doel = huidig?.takeIf { kaartMaat > 0f && !it.video && it.lat != null && it.lon != null && !staat.info && !schoon && staat.zoom == 1f && !staat.pasek },
            breedtePx = breedtePx, hoogtePx = hoogtePx, maat = kaartMaat,
        )

        // balk onderin: datum en plaats, verdwijnt vanzelf (instelbaar, 0 = altijd)
        var balk by remember { mutableStateOf(true) }
        LaunchedEffect(staat.actief, staat.index) { balk = true; val ms = Instellingen.balkMs; if (ms > 0) { delay(ms.toLong()); balk = false } }
        AnimatedVisibility(
            ((balk && !schoon) || staat.info || staat.pasek) && huidig != null && !staat.speeltVideo && staat.zoom == 1f, Modifier.align(Alignment.TopStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (huidig != null) OnderBalk(huidig, staat, metKaart = huidig.lat != null && !huidig.video && kaartMaat > 0f)
        }
        AnimatedVisibility(
            staat.info && huidig != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (huidig != null) InfoPaneel(huidig, staat, opKaart != null && huidig.lat != null)
        }
        AnimatedVisibility(
            staat.pasek && staat.zoom == 1f, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + androidx.compose.animation.slideInVertically { it / 3 }, exit = fadeOut(),
        ) { PasekAkcji(staat, akcje) }
        AnimatedVisibility(staat.zoom > 1f, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) { ZoomPodpowiedz(staat) }
        // opis zdjęcia z Immich jako napis w trakcie pokazu slajdów (Ustawienia → Pokaz slajdów → Opisy)
        val opis by produceState<String?>(null, huidig?.id, staat.diashow) {
            value = null
            if (staat.diashow && Instellingen.diaOpisy && huidig != null) { delay(300); value = runCatching { Immich.details(huidig.id).omschrijving }.getOrNull()?.trim()?.ifEmpty { null } }
        }
        AnimatedVisibility(opis != null && staat.diashow && !staat.speeltVideo, Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp, start = 80.dp, end = 80.dp), enter = fadeIn(tween(700)), exit = fadeOut()) {
            Text(opis.orEmpty(), style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3,
                modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 12.dp))
        }
        AnimatedVisibility(staat.komunikat != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp), enter = fadeIn(), exit = fadeOut()) {
            Text(staat.komunikat.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.background(Color(0xE6181822), RoundedCornerShape(20.dp)).padding(horizontal = 22.dp, vertical = 12.dp))
        }
        // Diashow rechtsboven: een badge (play-icoon + "Diashow") bij het starten, en kort "Diashow gepauzeerd" bij het
        // stoppen (OK, of handmatig bladeren); niet bij elke automatische wissel. Hij vervaagt na een paar seconden.
        // Het voortgangsringetje houdt zijn vaste plek: de badge vervaagt alleen (alpha) en houdt zijn ruimte.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRonde by remember { mutableIntStateOf(0) }
        var eersteKeer by remember { mutableStateOf(true) }
        LaunchedEffect(staat.diashow) {
            // viewer geopend zonder diashow: geen "gepauzeerd"
            if (eersteKeer) { eersteKeer = false; if (!staat.diashow) return@LaunchedEffect }
            // geen "▶ Diashow" bij alleen de foto; "gepauzeerd" wel (dat is een reactie op je eigen toets)
            badge = staat.diashow.takeIf { !(it && Instellingen.diaBeeld == DiaBeeld.ALLEEN_FOTO) }; badgeRonde++
        }
        LaunchedEffect(badgeRonde) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var laatste by remember { mutableStateOf(true) } // laatste inhoud, zodat hij met die tekst uitfadet
            if (b != null) laatste = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (laatste) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (laatste) R.string.diashow else R.string.diashow_pauze), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            AnimatedVisibility(staat.diashow && Instellingen.diaBeeld == DiaBeeld.ALLES, enter = fadeIn(), exit = fadeOut()) { DiaRing(staat) }
        }
    }
    // wybór albumu (▼ → Do albumu): to zdjęcie albo wszystkie zaznaczone
    staat.doAlbumu?.let { ids -> AlbumWybor(ids, komunikat = { staat.komunikat = it }) { staat.doAlbumu = null } }
    if (staat.twarze) staat.bron.op(staat.index)?.let { a -> TwarzeZdjecia(a) { staat.twarze = false } }
}

/** Ringetje dat in de diashow-tijd volloopt en bij elke foto opnieuw begint; staat stil zolang een video speelt. */
@Composable
private fun DiaRing(staat: KijkerStaat) {
    val deel = remember { Animatable(0f) }
    LaunchedEffect(staat.index, staat.speeltVideo) {
        deel.snapTo(0f)
        if (!staat.speeltVideo) deel.animateTo(1f, tween(Instellingen.diaMs.toInt(), easing = LinearEasing))
    }
    VoortgangRing({ deel.value })
}

@Composable
private fun Beeld(a: Asset, staat: KijkerStaat, actief: Boolean, zicht: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = zicht(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        // diashow: heel langzaam inzoomen (Ken Burns, instelbaar), anders stil
        // Alleen op de diashow-schakelaar: de vertrekkende pagina houdt zijn zoom (anders springt hij terug tijdens de
        // crossfade), en na het stoppen glijdt de foto terug naar 1,0 in plaats van te springen.
        val zoom = remember { Animatable(1f) }
        // twarze w kadrze: przybliżanie celuje w ludzi (środek rozpoznanych twarzy z Immich), wtedy trochę mocniej
        val twarz = remember(a.id) { mutableStateOf<Pair<Float, Float>?>(null) }
        LaunchedEffect(a.id, staat.diashow) {
            if (staat.diashow && Instellingen.kenBurns && Instellingen.kenBurnsTwarze && !a.video) twarz.value = Immich.srodekTwarzy(a.id)
        }
        LaunchedEffect(staat.diashow) {
            if (staat.diashow && Instellingen.kenBurns) { if (actief) zoom.animateTo(if (Instellingen.kenBurnsTwarze) 1.12f else 1.06f, tween((Instellingen.diaMs + 1500).toInt(), easing = LinearEasing)) }
            else if (zoom.value != 1f) zoom.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
        }
        // powiększenie ręczne (tylko bieżące zdjęcie) płynnie animowane, razem z Ken Burns
        val reczny by animateFloatAsState(if (actief) staat.zoom else 1f, tween(260), label = "zoom")
        val px by animateFloatAsState(if (actief) staat.przesX else 0f, tween(220), label = "px")
        val py by animateFloatAsState(if (actief) staat.przesY else 0f, tween(220), label = "py")
        val mod = Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.value * reczny; scaleY = zoom.value * reczny
            translationX = px; translationY = py
            // punkt, do którego przybliża pokaz: twarze (zdjęcie wpasowane w ekran → przeliczenie na ułamek ekranu)
            val t = twarz.value
            if (t != null && reczny <= 1.01f && size.height > 0f) {
                val s = size.width / size.height; val r = a.ratio.coerceIn(0.2f, 5f)
                val dw = minOf(1f, r / s); val dh = minOf(1f, s / r)
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f + (t.first - 0.5f) * dw, 0.5f + (t.second - 0.5f) * dh)
            }
        }
        // 1) thumbhash 2) thumbnail (al in de cache van het raster) 3) preview: er staat dus altijd meteen iets
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && Instellingen.wazig) {
            // achtergrond i.p.v. zwarte balken: de thumbhash (32 px) schermvullend opgerekt = gratis 'blur', verdonkerd
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            val vorm = Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)
            Image(hash, null, vorm, contentScale = ContentScale.FillBounds)
        }
        AsyncImage(beeld(Immich.thumb(a.id)), null, mod, contentScale = ContentScale.Fit)
        if (!a.video) AsyncImage(preview(a.id), null, mod, contentScale = ContentScale.Fit)
        // powiększone: wersja w pełnej rozdzielczości (Immich „fullsize”, do 4K) nad podglądem, gdy się wczyta
        if (!a.video && actief && staat.zoom > 1f) {
            val ctx = LocalContext.current
            val pelny = remember(a.id) {
                coil3.request.ImageRequest.Builder(ctx).data(Immich.pelny(a.id)).size(3840, 3840)
                    .precision(coil3.size.Precision.INEXACT).build()
            }
            AsyncImage(pelny, null, mod, contentScale = ContentScale.Fit)
        }
        if (a.video && actief) Video(a, staat)
        if (!a.video && actief && a.live != null && staat.liveGra) Video(a, staat, zrodlo = a.live, live = true)

    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(a: Asset, staat: KijkerStaat, zrodlo: String = a.id, live: Boolean = false) {
    val ctx = LocalContext.current
    val speler = remember {
        // Kleine buffers: standaard reserveert ExoPlayer tot ~140 MB (50 s) aan video; op een box met 2 GB is dat
        // genoeg om de app vooraan te laten opruimen. Een server in het eigen netwerk heeft aan 4-15 s genoeg.
        val buffers = DefaultLoadControl.Builder()
            .setBufferDurationsMs(4_000, 15_000, 1_000, 2_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        val renderers = if (!videoZonderPlaceholder) DefaultRenderersFactory(ctx) else object : DefaultRenderersFactory(ctx) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                out.add(object : MediaCodecVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    enableDecoderFallback, eventHandler, eventListener, 50) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
                })
            }
        }
        ExoPlayer.Builder(ctx, renderers).setLoadControl(buffers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Synology.polaczenia))).build()
    }
    // Eigen weergave i.p.v. PlayerView: verhouding uit onVideoSizeChanged (bij 90°/270° al omgewisseld) en voor
    // gedraaide video's (staande telefoonvideo's, vaak MOV) een TextureView. Met een SurfaceView laat Android de
    // draaiing aan de hardware-overlay over en die negeert hem op sommige tv-boxen: de video staat dan op zijn kant.
    // Een TextureView tekent via de GPU met de transformatie van de decoder, die klopt overal. Ongedraaide video's
    // houden de SurfaceView (zuiniger, en HDR werkt alleen daar).
    var verhouding by remember { mutableFloatStateOf(0f) }
    var gedraaid by remember { mutableStateOf(false) }
    DisposableEffect(a.id) {
        staat.videoFout = false; staat.videoFoutOpis = null
        verhouding = 0f; gedraaid = false
        var format: androidx.media3.common.Format? = null
        speler.setMediaItem(MediaItem.fromUri(Immich.video(zrodlo)))
        // wznawianie: przerwany wcześniej film zaczyna się tam, gdzie skończyłeś (nie w pokazie slajdów i nie w Live Photo)
        val wznow = if (!live && !staat.diashow) Wznawianie.pozycja(ctx, a.id) else 0L
        if (wznow > 0) { speler.seekTo(wznow); staat.komunikat = ctx.getString(R.string.wideo_wznowiono, czasFilmu(wznow)) }
        speler.prepare()
        speler.playWhenReady = true
        val luister = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { staat.speeltVideo = isPlaying }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) verhouding = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onTracksChanged(tracks: Tracks) {
                val f = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.getTrackFormat(0) ?: return
                format = f
                gedraaid = f.rotationDegrees == 90 || f.rotationDegrees == 270
                if (gedraaid) Log.i("Fotos", "video ${a.id}: gedraaid ${f.rotationDegrees}°, TextureView")
            }
            override fun onPlaybackStateChanged(s: Int) {
                // diashow: na de video door naar de volgende
                if (s == Player.STATE_ENDED) {
                    staat.speeltVideo = false
                    if (live) staat.liveGra = false else if (staat.diashow) staat.diaVerder()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.w("Fotos", "video ${a.id}", error)
                staat.speeltVideo = false
                if (live) { staat.liveGra = false; return } // Live Photo bez filmu: zostaje samo zdjęcie
                staat.videoFout = true // diashow loopt dan na de diashow-tijd gewoon door
                staat.videoFoutOpis = opisBleduWideo(format, error)
            }
        }
        speler.addListener(luister)
        staat.speler = speler
        // vrijgeven zodra deze pagina weg is; bij video→video heeft de nieuwe pagina staat.speler dan al overgenomen
        onDispose {
            if (!live) Wznawianie.zapisz(ctx, a.id, speler.currentPosition, speler.duration)
            speler.removeListener(luister); speler.stop(); speler.release()
            if (staat.speler === speler) { staat.speler = null; staat.speeltVideo = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        // Het oppervlak moet er METEEN zijn: op apparaten zonder placeholder-surface (Media3: o.a. veel Fire TV's) start de
        // decoder pas met een echt oppervlak, dus wachten op de videomaat bleef daar eeuwig wachten (2.7: geen enkele video
        // speelde). Tot de maat bekend is de verhouding uit Immich (staande video = staand), daarna die van de video zelf.
        val r = if (verhouding > 0f) verhouding else a.ratio
        key(gedraaid) {
            AndroidView(
                factory = { c ->
                    if (gedraaid) TextureView(c).also { speler.setVideoTextureView(it) }
                    else SurfaceView(c).also { speler.setVideoSurfaceView(it) }
                },
                onRelease = { v ->
                    if (v is TextureView) speler.clearVideoTextureView(v)
                    else if (v is SurfaceView) speler.clearVideoSurfaceView(v)
                },
                // past binnen het scherm, staand of liggend (aspectRatio probeert eerst de hoogte, anders de breedte)
                modifier = Modifier.align(Alignment.Center).aspectRatio(r.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true),
            )
        }
        if (staat.videoFout) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = Kleur.zacht)
            Text(stringResource(R.string.video_fout), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
            staat.videoFoutOpis?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Kleur.tekst, modifier = Modifier.padding(top = 6.dp)) }
            Text(stringResource(R.string.video_fout_rada), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 10.dp).width(560.dp))
        }
        // pozycja / długość (ms); przy przewijaniu od razu pozycja docelowa, bez czekania na odtwarzacz
        val czas by produceState(0L to 0L, speler) {
            while (true) { value = speler.currentPosition to speler.duration.coerceAtLeast(0L); delay(200) }
        }
        val teraz by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(250) } }
        val (pozOdtw, dl) = czas
        val poz = if (teraz - staat.przewinieto < 1200 || staat.strzalkaPrzewija) staat.przewinDoel else pozOdtw
        val deel = if (dl > 0) (poz.toFloat() / dl).coerceIn(0f, 1f) else 0f
        // pełny pasek z podpowiedziami: po każdym klawiszu (i przy starcie filmu) przez kilka sekund oraz na pauzie
        val pauza = !staat.speeltVideo && speler.playbackState == Player.STATE_READY && !speler.playWhenReady
        val pelny = !live && !staat.videoFout && !staat.pasek && !staat.info && (teraz - staat.actief < 4000 || pauza || staat.strzalkaPrzewija)
        if (!live) androidx.compose.animation.AnimatedContent(pelny, Modifier.align(Alignment.BottomStart).fillMaxWidth(), label = "pasekWideo") { duzy ->
            if (!duzy) Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(deel).background(Kleur.primair))
            } else Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD0000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 26.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (pauza) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, Modifier.size(28.dp), tint = Color.White)
                    Text(czasFilmu(poz), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(start = 14.dp, end = 16.dp))
                    Box(Modifier.weight(1f).height(6.dp).background(Color(0x40FFFFFF), RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(deel).background(Kleur.primair, RoundedCornerShape(3.dp)))
                    }
                    Text(czasFilmu(dl), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.padding(start = 16.dp))
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    WideoSkrot(stringResource(R.string.wideo_ok)) { Text("OK", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                    WideoSkrot(stringResource(R.string.wideo_poprz_nast)) { Text("◀  ▶", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                    WideoSkrot(stringResource(R.string.wideo_trzymaj)) { Text("◀  ▶", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                    WideoSkrot(stringResource(R.string.wideo_menu)) { Text("▼", style = MaterialTheme.typography.labelMedium, color = Color.White) }
                    WideoSkrot(stringResource(R.string.wideo_zamknij)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = Color.White) }
                }
            }
        }
    }
}

/** Podpowiedź klawisza pod paskiem filmu: [klawisz] opis. */
@Composable
private fun WideoSkrot(opis: String, klawisz: @Composable () -> Unit) {
    Row(Modifier.padding(end = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(Color(0x40FFFFFF), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) { klawisz() }
        Text(opis, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * Nagłówek w stylu Immichin (lewy górny róg): mała etykieta „Album · 5 / 8”, wielka data szeryfem („30 September 2025”),
 * pod nią „Tuesday · 22:56 · 📍 Hornafjörður, Iceland” i ewentualnie albumy.
 */
@Composable
private fun OnderBalk(a: Asset, staat: KijkerStaat, @Suppress("UNUSED_PARAMETER") metKaart: Boolean) {
    Box(Modifier.fillMaxWidth().height(230.dp).background(Brush.verticalGradient(listOf(Color(0xB0000000), Color(0x40000000), Color.Transparent)))) {
        Column(Modifier.padding(start = 40.dp, top = 26.dp, end = 40.dp)) {
            val etykieta = listOfNotNull(
                staat.titel,
                if (Instellingen.teller) "${Taal.getal(staat.index + 1)} / ${Taal.getal(staat.bron.aantal)}" else null,
            ).joinToString("  ·  ")
            if (etykieta.isNotEmpty()) Text(etykieta.uppercase(Taal.locale), style = EtykietaStyl, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            val wzor = if (Formaat.datum == Formaat.Datum.MAAND_DAG) "MMMM d, yyyy" else "d MMMM yyyy"
            Text(
                a.datum.format(java.time.format.DateTimeFormatter.ofPattern(wzor, Taal.locale)),
                style = TytulStyl.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Color.White, maxLines = 1,
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val dzien = a.datum.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", Taal.locale)).replaceFirstChar { it.titlecase(Taal.locale) }
                Text("$dzien  ·  ${a.datum.format(Formaat.tijd)}", style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Color(0xDDFFFFFF))
                val plaats = listOfNotNull(a.stad, a.land).joinToString(", ")
                if (plaats.isNotEmpty()) {
                    Text("  ·  ", style = MaterialTheme.typography.titleMedium, color = Color(0xDDFFFFFF))
                    Icon(Icons.Filled.Place, null, Modifier.size(18.dp), tint = Kleur.pinezka)
                    Text(" $plaats", style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Color(0xDDFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (a.live != null) {
                    Spacer(Modifier.width(16.dp))
                    Row(Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.material.icons.Icons.Filled.MotionPhotosOn, null, Modifier.size(15.dp), tint = Color.White)
                        Text(" LIVE", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
                if (Ulubione.jest(a)) {
                    Spacer(Modifier.width(16.dp))
                    Icon(androidx.compose.material.icons.Icons.Filled.Favorite, null, Modifier.size(18.dp), tint = Color(0xFFFF5C8A))
                }
            }
            // album(s) van de foto; het album dat je nu bekijkt niet nog eens
            if (Instellingen.albumNaam) {
                val namen = albumNamen(a.id).filter { it != staat.titel }
                if (namen.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = Color(0xBBFFFFFF))
                    Text("  " + albumRegel(namen), style = MaterialTheme.typography.bodyMedium, color = Color(0xBBFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Karta „SZCZEGÓŁY” jak w Immichin: pływająca, zaokrąglona, wyśrodkowana pionowo po prawej. U góry etykieta i licznik
 * („5 z 8”), wielka data szeryfem, „wtorek · 22:56 · UTC+2”; potem wiersze z okrągłymi ikonami: plik (MP · wymiary ·
 * rozmiar), aparat (przysłona · czas · ISO · ogniskowa, obiektyw), miejsce (+ współrzędne i mała mapa), ulubione,
 * ludzie, albumy, opis.
 */
@Composable
private fun InfoPaneel(a: Asset, staat: KijkerStaat, metKaartSprong: Boolean) {
    val details by produceState<Details?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Box(Modifier.fillMaxHeight().padding(vertical = 24.dp, horizontal = 32.dp), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(380.dp)
                .background(Color(0xE6121220), RoundedCornerShape(30.dp))
                .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(30.dp))
                .padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 18.dp),
        ) {
            // Inhoud mag langer zijn dan het scherm (veel personen, lange omschrijving) en scrolt dan; de hint blijft onderaan.
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.szczegoly).uppercase(Taal.locale), style = EtykietaStyl, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.z_ilu, Taal.getal(staat.index + 1), Taal.getal(staat.bron.aantal)), style = MaterialTheme.typography.labelLarge, color = Kleur.zacht)
                    }
                    Text(
                        a.datum.format(java.time.format.DateTimeFormatter.ofPattern(if (Formaat.datum == Formaat.Datum.MAAND_DAG) "MMMM d, yyyy" else "d MMMM yyyy", Taal.locale)),
                        style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 8.dp), maxLines = 1,
                    )
                    val dzien = a.datum.format(java.time.format.DateTimeFormatter.ofPattern("EEEE", Taal.locale)).replaceFirstChar { it.titlecase(Taal.locale) }
                    Text(listOfNotNull(dzien, a.datum.format(Formaat.tijd), details?.strefa).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 2.dp))
                }
                val d = details
                if (d != null) {
                    // najpierw ludzie (twarze), potem plik, aparat, miejsce…; mapka na końcu, bo zajmuje najwięcej miejsca
                    if (d.mensen.isNotEmpty()) OsobyRegel(d.mensen)
                    InfoRegel(
                        if (d.wideo) Icons.Filled.Videocam else Icons.Filled.Image, d.bestand.ifEmpty { "—" },
                        listOfNotNull(d.dlugosc, d.plikSub ?: d.afmeting).joinToString("  ·  ").ifEmpty { null },
                    )
                    d.camera?.let { InfoRegel(Icons.Filled.CameraAlt, it, listOfNotNull(d.instellingen, d.lens).joinToString("\n").ifEmpty { null }) }
                    (d.plaats ?: d.wspolrzedne)?.let { InfoRegel(Icons.Filled.Place, it, d.wspolrzedne.takeIf { d.plaats != null }) }
                    if (Ulubione.jest(a)) InfoRegel(Icons.Filled.Favorite, stringResource(R.string.w_ulubionych), null, kolorIkony = Color(0xFFFF5C8A))
                    val albums = albumNamen(a.id)
                    if (albums.isNotEmpty()) InfoRegel(Icons.Filled.PhotoLibrary, albums.joinToString(", "), null, maxRegels = 3)
                    d.omschrijving?.let { InfoRegel(Icons.Filled.Description, it, null, maxRegels = 5) }
                    if (a.lat != null && a.lon != null && Instellingen.mapaSzczegoly) {
                        KaartKlein(a.lat, a.lon, Modifier.padding(start = 56.dp).fillMaxWidth().height(100.dp).background(Kleur.vlak, RoundedCornerShape(14.dp)))
                    }
                } else {
                    Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                }
            }
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = Kleur.zacht)
                Text("  " + stringResource(if (metKaartSprong) R.string.hint_info_kaart else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoRegel(icoon: ImageVector, titel: String, sub: String?, maxRegels: Int = Int.MAX_VALUE, kolorIkony: Color = Color(0xDDFFFFFF)) {
    Row(verticalAlignment = if (sub == null) Alignment.CenterVertically else Alignment.Top) {
        // ikona w okrągłym, półprzezroczystym tle (jak w Immichin)
        Box(Modifier.size(40.dp).background(Color(0x1FFFFFFF), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(icoon, null, Modifier.size(20.dp), tint = kolorIkony)
        }
        Column(Modifier.padding(start = 16.dp, top = if (sub == null) 0.dp else 1.dp)) {
            Text(titel, style = MaterialTheme.typography.titleMedium, maxLines = maxRegels, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

/**
 * Rozpoznane osoby jak w Immichin: okrągłe miniatury twarzy (z Immich, /people/{id}/thumbnail) z imieniem pod spodem,
 * w rzędzie przewijanym, gdy osób jest dużo.
 */
@Composable
private fun OsobyRegel(osoby: List<Persoon>) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).background(Color(0x1FFFFFFF), androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.People, null, Modifier.size(20.dp), tint = Color(0xDDFFFFFF))
        }
        Row(
            Modifier.padding(start = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (p in osoby) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
                    Box(Modifier.border(2.dp, Color(0x33FFFFFF), androidx.compose.foundation.shape.CircleShape).padding(2.dp)) { Gezicht(p, 52.dp) }
                    Text(p.naam, style = MaterialTheme.typography.labelMedium, color = Kleur.tekst, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

/** Akcje dolnego paska przeglądarki (▼), jak okrągłe przyciski w Immichin. */
enum class Akcja { POPRZEDNI, NASTEPNY, POKAZ, ULUBIONE, SZCZEGOLY, POWIEKSZ, LIVE, UDOSTEPNIJ, KOD_QR, WYCZYSC, ALBUM, Z_ALBUMU, OKLADKA, TWARZE, MAPA }

fun akcjeDla(a: Asset?, zMapa: Boolean, zAlbumu: Boolean = false): List<Akcja> = if (a != null && Synology.jestSyno(a.id)) listOf(Akcja.POKAZ) else buildList {
    add(Akcja.POKAZ)
    if (a != null) add(Akcja.ULUBIONE)
    add(Akcja.SZCZEGOLY)
    if (a != null && !a.video) add(Akcja.POWIEKSZ)
    if (a?.live != null) add(Akcja.LIVE)
    if (a != null) add(Akcja.UDOSTEPNIJ)
    if (Udostepnianie.koszyk.isNotEmpty()) add(Akcja.KOD_QR)
    if (Udostepnianie.koszyk.isNotEmpty()) add(Akcja.WYCZYSC)
    if (a != null && !Instellingen.trybDzieciecy) add(Akcja.ALBUM)
    if (a != null && zAlbumu && !Instellingen.trybDzieciecy) add(Akcja.Z_ALBUMU)
    if (a != null && zAlbumu && !Instellingen.trybDzieciecy) add(Akcja.OKLADKA)
    if (a != null && !Instellingen.trybDzieciecy) add(Akcja.TWARZE)
    if (zMapa && a?.lat != null) add(Akcja.MAPA)
}

/**
 * Muzyka w tle pokazu slajdów: strumień lub plik z adresu z ustawień (np. radio internetowe albo mp3 na serwerze
 * domowym). Gra tylko podczas pokazu, pauzuje na czas filmów, w kółko.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun MuzykaPokazu(grac: Boolean) {
    val url = Instellingen.muzykaUrl.trim()
    if (!Instellingen.muzyka || url.isEmpty()) return
    val ctx = LocalContext.current
    val gracz = remember(url) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(url)); repeatMode = Player.REPEAT_MODE_ALL; volume = 0.7f; prepare()
        }
    }
    DisposableEffect(gracz) { onDispose { gracz.release() } }
    LaunchedEffect(grac, gracz) { gracz.playWhenReady = grac }
}

/** Opis niezgodnego filmu: kodek, rozdzielczość, przyczyna z odtwarzacza. */
fun opisBleduWideo(f: androidx.media3.common.Format?, e: PlaybackException): String {
    val kodek = when (f?.sampleMimeType) {
        "video/hevc" -> "HEVC (H.265)"; "video/avc" -> "H.264"; "video/av01" -> "AV1"; "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"; "video/mp4v-es" -> "MPEG-4"; "video/dolby-vision" -> "Dolby Vision"
        null -> null; else -> f.sampleMimeType
    }
    val roz = f?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" }
    val bity = f?.colorInfo?.colorTransfer?.takeIf { it == C.COLOR_TRANSFER_ST2084 || it == C.COLOR_TRANSFER_HLG }?.let { "HDR" }
    return listOfNotNull(kodek, roz, bity, e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')).joinToString("  ·  ")
}

@Composable
private fun PasekAkcji(staat: KijkerStaat, akcje: List<Akcja>) {
    Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))) {
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            akcje.forEachIndexed { i, ak ->
                val wybrany = i == staat.pasekWybor.coerceIn(0, akcje.lastIndex)
                val (ikona, napis) = when (ak) {
                    Akcja.POPRZEDNI -> Icons.Filled.SkipPrevious to stringResource(R.string.poprzedni)
                    Akcja.NASTEPNY -> Icons.Filled.SkipNext to stringResource(R.string.nastepny)
                    Akcja.POKAZ -> (if (staat.diashow) Icons.Filled.Pause else Icons.Filled.Slideshow) to stringResource(if (staat.diashow) R.string.diashow_pauze_kort else R.string.diashow)
                    Akcja.SZCZEGOLY -> Icons.Filled.Info to stringResource(R.string.szczegoly)
                    Akcja.POWIEKSZ -> Icons.Filled.ZoomIn to stringResource(R.string.powieksz)
                    Akcja.MAPA -> Icons.Filled.Map to stringResource(R.string.sectie_kaart)
                    Akcja.ULUBIONE -> {
                        val u = staat.bron.op(staat.index)?.let { Ulubione.jest(it) } == true
                        (if (u) Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Filled.FavoriteBorder) to stringResource(if (u) R.string.w_ulubionych_kort else R.string.ulubione_dodaj)
                    }
                    Akcja.LIVE -> androidx.compose.material.icons.Icons.Filled.MotionPhotosOn to "Live"
                    Akcja.UDOSTEPNIJ -> {
                        val w = staat.bron.op(staat.index)?.let { Udostepnianie.zawiera(it) } == true
                        (if (w) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Filled.AddCircleOutline) to
                            stringResource(if (w) R.string.udost_wybrane else R.string.udost_dodaj)
                    }
                    Akcja.KOD_QR -> androidx.compose.material.icons.Icons.Filled.QrCode2 to stringResource(R.string.udost_kod, Udostepnianie.koszyk.size)
                    Akcja.ALBUM -> Icons.Filled.LibraryAdd to
                        (if (Udostepnianie.koszyk.isNotEmpty()) stringResource(R.string.album_akcja_n, (Udostepnianie.koszyk.map { it.id } + listOfNotNull(staat.bron.op(staat.index)?.id)).distinct().size) else stringResource(R.string.album_akcja))
                    Akcja.OKLADKA -> Icons.Filled.Wallpaper to stringResource(R.string.album_okladka)
                    Akcja.TWARZE -> Icons.Filled.Face to stringResource(R.string.twarze_akcja)
                    Akcja.WYCZYSC -> Icons.Filled.DeleteSweep to stringResource(R.string.zazn_wyczysc_krotko)
                    Akcja.Z_ALBUMU -> {
                        val usuniete = staat.bron.op(staat.index)?.let { staat.usunieteZAlbumu[it.id] } == true
                        (if (usuniete) Icons.AutoMirrored.Filled.Undo else Icons.Filled.RemoveCircleOutline) to
                            stringResource(if (usuniete) R.string.album_przywroc else R.string.album_usun)
                    }
                }
                val skala by animateFloatAsState(if (wybrany) 1.08f else 1f, tween(140), label = "akcja")
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
                    Box(
                        Modifier.size(56.dp).graphicsLayer { scaleX = skala; scaleY = skala }
                            .background(if (wybrany) Color.White else Color(0x33FFFFFF), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(ikona, null, Modifier.size(24.dp), tint = if (wybrany) Color(0xFF101018) else Color.White) }
                    Text(napis, style = MaterialTheme.typography.titleSmall, color = if (wybrany) Color.White else Color(0xB3FFFFFF),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/** Podpowiedź w trybie powiększenia: „2× · ◀▶▲▼ przesuń · OK bliżej · Wstecz wyjdź”. */
@Composable
private fun ZoomPodpowiedz(staat: KijkerStaat) {
    Row(
        Modifier.padding(bottom = 32.dp).background(Color(0xCC101018), RoundedCornerShape(24.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.ZoomIn, null, Modifier.size(20.dp), tint = Kleur.pinezka)
        Text("  ${"%.0f".format(staat.zoom)}×   ·   " + stringResource(R.string.zoom_hint), style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** 83_000 ms -> "1:23", 3_723_000 -> "1:02:03" */
fun czasFilmu(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Albums waar deze foto in zit (leeg zolang ze laden of als er geen zijn). */
@Composable
fun albumNamen(id: String): List<String> =
    produceState(emptyList(), id) { delay(150); value = Immich.albumsVan(id) }.value

/** "Vakantie 2023", of "Vakantie 2023 + 2" bij meer albums. */
fun albumRegel(namen: List<String>) = if (namen.size <= 1) namen.firstOrNull().orEmpty() else namen[0] + "  + " + (namen.size - 1)
