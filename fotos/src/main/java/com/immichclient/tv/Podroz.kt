package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sqrt

/** Przystanek podróży: miejsce (środek zdjęć) i zdjęcia z niego, chronologicznie. */
class Przystanek(val lat: Double, val lon: Double, val zdjecia: List<Asset>) {
    val nazwa get() = zdjecia.firstNotNullOfOrNull { it.stad } ?: zdjecia.firstNotNullOfOrNull { it.land }.orEmpty()
}

/**
 * Film z podróży: zdjęcia albumu z GPS, od najstarszych, połączone w przystanki (nowy przystanek co ponad 5 km).
 * Mapa przelatuje od przystanku do przystanku po narysowanej trasie, a w każdym miejscu przewijają się jego zdjęcia.
 */
class PodrozStaat(val albumId: String, val nazwa: String, scope: CoroutineScope) {
    var przystanki by mutableStateOf<List<Przystanek>?>(null)
        private set
    var nr by mutableIntStateOf(-1)
    var pauza by mutableStateOf(false)

    init {
        scope.launch {
            val zdjecia = runCatching { Immich.zAlbumu(albumId, 3000) }.onFailure { Log.w("Fotos", "podróż", it) }.getOrDefault(emptyList())
                .filter { it.lat != null && it.lon != null && !it.video }.sortedBy { it.datum }
            val lista = ArrayList<MutableList<Asset>>()
            for (a in zdjecia) {
                val ost = lista.lastOrNull()
                val s = ost?.let { g -> g.map { it.lat!! }.average() to g.map { it.lon!! }.average() }
                if (s == null || ost == null || km(s.first, s.second, a.lat!!, a.lon!!) > 5.0) lista.add(mutableListOf(a)) else ost.add(a)
            }
            // za dużo przystanków: łączymy sąsiednie (film ma trwać kilka minut, nie godzinę)
            var grupy: List<List<Asset>> = lista
            while (grupy.size > 30) grupy = grupy.chunked(2).map { it.flatten() }
            przystanki = grupy.map { g -> Przystanek(g.map { it.lat!! }.average(), g.map { it.lon!! }.average(), g) }
        }
    }

    private fun km(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dx = (lo2 - lo1) * 111.32 * cos(Math.toRadians((la1 + la2) / 2)); val dy = (la2 - la1) * 110.57
        return sqrt(dx * dx + dy * dy)
    }
}

@Composable
fun PodrozScherm(staat: PodrozStaat) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    var styl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { styl = Immich.kaartStijl(licht = false); delay(60); runCatching { fr.requestFocus() } }
    val widok = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).compassEnabled(false).logoEnabled(false).attributionEnabled(false)).apply { isFocusable = false }
    }
    DisposableEffect(widok) {
        widok.onCreate(null); widok.onStart(); widok.onResume()
        widok.getMapAsync { m -> m.uiSettings.setAllGesturesEnabled(false); mapa = m }
        onDispose { widok.onPause(); widok.onStop(); widok.onDestroy() }
    }
    // styl + trasa (linia i kropki przystanków), potem start filmu
    val lista = staat.przystanki
    var gotowe by remember { mutableStateOf(false) }
    LaunchedEffect(mapa, styl, lista) {
        val m = mapa ?: return@LaunchedEffect; val u = styl ?: return@LaunchedEffect; val p = lista ?: return@LaunchedEffect
        if (p.isEmpty()) return@LaunchedEffect
        m.setStyle(Style.Builder().fromUri(u)) { st ->
            runCatching {
                val pkt = p.map { Point.fromLngLat(it.lon, it.lat) }
                st.addSource(GeoJsonSource("trasa", FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(pkt))))))
                st.addLayer(LineLayer("trasa", "trasa").withProperties(PropertyFactory.lineColor("#F5B83D"), PropertyFactory.lineWidth(4f), PropertyFactory.lineOpacity(0.9f), PropertyFactory.lineDasharray(arrayOf(2f, 1.5f))))
                st.addSource(GeoJsonSource("przystanki", FeatureCollection.fromFeatures(pkt.map { Feature.fromGeometry(it) })))
                st.addLayer(CircleLayer("przystanki", "przystanki").withProperties(PropertyFactory.circleColor("#E07BDF"), PropertyFactory.circleRadius(7f), PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
            }.onFailure { Log.w("Fotos", "podróż: trasa", it) }
            // na początek cała trasa w kadrze
            if (p.size > 1) runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(p.map { LatLng(it.lat, it.lon) }).build(), 120)) }
            else m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p[0].lat, p[0].lon), 10.0))
            gotowe = true
        }
    }
    // film: przystanek po przystanku
    var zdjecieNr by remember { mutableIntStateOf(0) }
    LaunchedEffect(gotowe, staat.nr, staat.pauza) {
        val p = lista ?: return@LaunchedEffect; val m = mapa ?: return@LaunchedEffect
        if (!gotowe || staat.pauza) return@LaunchedEffect
        if (staat.nr < 0) { delay(2500); staat.nr = 0; return@LaunchedEffect }
        val s = p.getOrNull(staat.nr)
        if (s == null) {
            // koniec: cała trasa jeszcze raz
            if (p.size > 1) runCatching { m.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(p.map { LatLng(it.lat, it.lon) }).build(), 120), 2500) }
            return@LaunchedEffect
        }
        zdjecieNr = 0
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(s.lat, s.lon), 11.0), 2600)
        delay(2800)
        // do 5 zdjęć z przystanku, każde ok. 2,5 s
        val ile = minOf(5, s.zdjecia.size)
        for (k in 0 until ile) { zdjecieNr = k; delay(2500) }
        staat.nr = staat.nr + 1
    }
    MuzykaPokazu(grac = gotowe && !staat.pauza && lista != null && staat.nr < (lista.size))

    Box(
        Modifier.fillMaxSize().background(Color(0xFF101418)).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val n = lista?.size ?: 0
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { staat.pauza = !staat.pauza; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (staat.nr < n) staat.nr++; true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (staat.nr > 0) staat.nr--; true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                else -> false
            }
        },
    ) {
        AndroidView(factory = { widok }, modifier = Modifier.fillMaxSize())
        // nagłówek
        Column(Modifier.padding(start = 28.dp, top = 22.dp).background(Color(0xB3000000), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.podroz_tytul).uppercase(Taal.locale), style = EtykietaStyl)
            Text(staat.nazwa, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
            val p = lista
            Text(
                when {
                    p == null -> stringResource(R.string.laden)
                    p.isEmpty() -> stringResource(R.string.podroz_brak_gps)
                    else -> stringResource(R.string.podroz_przystanek, (staat.nr + 1).coerceIn(1, p.size), p.size) + (if (staat.pauza) "  ·  ⏸" else "")
                },
                style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht,
            )
        }
        // zdjęcia bieżącego przystanku
        val s = lista?.getOrNull(staat.nr)
        if (s != null) Column(Modifier.align(Alignment.BottomStart).padding(28.dp).width(520.dp)) {
            AnimatedContent(s.zdjecia.getOrNull(zdjecieNr)?.id, transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(600)) }, label = "podrozZdjecie") { id ->
                if (id != null) AsyncImage(preview(id), null, Modifier.width(520.dp).height(320.dp).clip(RoundedCornerShape(18.dp)).border(3.dp, Color.White, RoundedCornerShape(18.dp)).background(Color.Black), contentScale = ContentScale.Crop)
            }
            Text(
                listOf(s.nazwa, s.zdjecia.first().datum.toLocalDate().format(Formaat.dagJaar)).filter { it.isNotBlank() }.joinToString("  ·  "),
                style = MaterialTheme.typography.titleLarge, color = Color.White,
                modifier = Modifier.padding(top = 8.dp).background(Color(0xB3000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Text(stringResource(R.string.podroz_hint), style = MaterialTheme.typography.labelLarge, color = Color(0xDDFFFFFF),
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).background(Color(0xB3000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
    }
}
