package com.immichclient.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Podświetlone kraje, w których powstały zdjęcia (kontury z landen.bin), jako warstwa MapLibre pod napisami.
 * Mapę cieplną rysuje Compose ([MapaCieplna]): HeatmapLayer MapLibre z tysiącami punktów zawieszał mapę na telewizorach.
 */
class KaartWarstwyDane(val kraje: String)

private const val SRC_KRAJE = "ar_kraje"
private const val L_KRAJE = "ar_kraje"
/** warstwa z wersji 2.9-ar4 (HeatmapLayer MapLibre): zbyt ciężka dla GPU telewizorów, usuwana, jeśli jeszcze jest */
private const val L_HEAT_STARY = "ar_heat"

/** Kontury krajów ze zdjęciami jako gotowy tekst GeoJSON (budowany w tle; MapLibre dostaje tylko tekst). */
suspend fun zbudujWarstwy(ctx: Context, m: Markers): KaartWarstwyDane = withContext(Dispatchers.Default) {
    val odwiedzone = m.iso.filterNotNull().toHashSet()
    val kraje = Landen.lijst(ctx).filter { Landnaam.iso(it.naam) in odwiedzone }.flatMap { l ->
        l.ringen.filter { it.size >= 6 }.map { r ->
            val pkt = ArrayList<Point>(r.size / 2 + 1)
            var i = 0
            while (i < r.size) { pkt.add(Point.fromLngLat(r[i].toDouble(), r[i + 1].toDouble())); i += 2 }
            if (pkt.first() != pkt.last()) pkt.add(pkt.first())
            Feature.fromGeometry(Polygon.fromLngLats(listOf(pkt)))
        }
    }
    Log.i("Fotos", "kaart: ${kraje.size} wielokątów (${odwiedzone.size} krajów)")
    KaartWarstwyDane(FeatureCollection.fromFeatures(kraje).toJson())
}

/** Dodaj / usuń warstwę podświetlonych krajów w bieżącym stylu (pod napisami). */
fun ustawWarstwy(stijl: Style, d: KaartWarstwyDane, kraje: Boolean) {
    runCatching {
        stijl.getLayer(L_HEAT_STARY)?.let { stijl.removeLayer(it) }
        if (kraje) {
            if (stijl.getSource(SRC_KRAJE) == null) stijl.addSource(GeoJsonSource(SRC_KRAJE, d.kraje))
            if (stijl.getLayer(L_KRAJE) == null) {
                val l = FillLayer(L_KRAJE, SRC_KRAJE).withProperties(PropertyFactory.fillColor("#7C6BFF"), PropertyFactory.fillOpacity(0.16f))
                val pod = stijl.layers.firstOrNull { it is SymbolLayer }?.id
                if (pod != null) stijl.addLayerBelow(l, pod) else stijl.addLayer(l)
            }
        } else stijl.getLayer(L_KRAJE)?.let { stijl.removeLayer(it) }
    }.onFailure { Log.w("Fotos", "kaart: warstwy", it) }
}

/**
 * Mapa cieplna jak w Immichin: rozmyta różowa poświata wokół każdej widocznej grupy, większa przy większej liczbie
 * zdjęć. Rysowana w Compose z gotowych grup (najwyżej kilkadziesiąt kół), tylko w fazie rysowania: tania na każdym GPU.
 */
@androidx.compose.runtime.Composable
fun MapaCieplna(grupy: List<Cluster>, pozycja: (Cluster) -> androidx.compose.ui.geometry.Offset) {
    val kolor = androidx.compose.ui.graphics.Color(0xFFE07BDF)
    androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
        val baza = 46.dp.toPx()
        for (c in grupy) {
            val p = pozycja(c)
            val r = baza * (1f + 0.55f * kotlin.math.log10(c.aantal.toFloat().coerceAtLeast(1f)))
            if (p.x < -r || p.y < -r || p.x > size.width + r || p.y > size.height + r) continue
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to kolor.copy(alpha = 0.55f), 0.55f to kolor.copy(alpha = 0.32f), 1f to kolor.copy(alpha = 0f),
                    center = p, radius = r,
                ),
                radius = r, center = p,
            )
        }
    }
}
