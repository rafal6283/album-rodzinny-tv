package com.immichclient.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Warstwy mapy jak w Immichin: mapa cieplna miejsc ze zdjęciami (różowa poświata) i podświetlone kraje, w których
 * powstały zdjęcia (kontury z landen.bin). Obie pod napisami stylu i pod znacznikami (te rysuje Compose).
 */
class KaartWarstwyDane(val punkty: FeatureCollection, val kraje: FeatureCollection)

private const val SRC_PUNKTY = "ar_punkty"
private const val SRC_KRAJE = "ar_kraje"
private const val L_HEAT = "ar_heat"
private const val L_KRAJE = "ar_kraje"

suspend fun zbudujWarstwy(ctx: Context, m: Markers): KaartWarstwyDane = withContext(Dispatchers.Default) {
    // mapa cieplna: co najwyżej jeden punkt na ~100 m (zaokrąglenie do 3 miejsc), z wagą = liczba zdjęć w tym miejscu
    val waga = HashMap<Long, IntArray>()
    val miejsce = HashMap<Long, Pair<Double, Double>>()
    for (i in 0 until m.aantal) {
        val k = (Math.round(m.lat[i] * 1000) shl 32) xor (Math.round(m.lon[i] * 1000) and 0xFFFFFFFFL)
        waga.getOrPut(k) { miejsce[k] = m.lat[i] to m.lon[i]; IntArray(1) }[0]++
    }
    val punkty = waga.map { (k, n) ->
        val (la, lo) = miejsce.getValue(k)
        Feature.fromGeometry(Point.fromLngLat(lo, la)).apply { addNumberProperty("n", n[0]) }
    }
    val odwiedzone = m.iso.filterNotNull().toHashSet()
    val kraje = Landen.lijst(ctx).filter { Landnaam.iso(it.naam) in odwiedzone }.flatMap { l ->
        l.ringen.map { r ->
            val pkt = ArrayList<Point>(r.size / 2 + 1)
            var i = 0
            while (i < r.size) { pkt.add(Point.fromLngLat(r[i].toDouble(), r[i + 1].toDouble())); i += 2 }
            if (pkt.isNotEmpty() && pkt.first() != pkt.last()) pkt.add(pkt.first())
            Feature.fromGeometry(Polygon.fromLngLats(listOf(pkt)))
        }
    }
    Log.i("Fotos", "kaart: warstwy ${punkty.size} punktów, ${kraje.size} wielokątów (${odwiedzone.size} krajów)")
    KaartWarstwyDane(FeatureCollection.fromFeatures(punkty), FeatureCollection.fromFeatures(kraje))
}

/** Dodaj / zaktualizuj / usuń warstwy w bieżącym stylu według ustawień. */
fun ustawWarstwy(stijl: Style, d: KaartWarstwyDane, heat: Boolean, kraje: Boolean) {
    runCatching {
        val pod = stijl.layers.firstOrNull { it is SymbolLayer }?.id
        fun dodaj(l: Layer) { if (pod != null) stijl.addLayerBelow(l, pod) else stijl.addLayer(l) }

        if (kraje) {
            (stijl.getSourceAs<GeoJsonSource>(SRC_KRAJE) ?: GeoJsonSource(SRC_KRAJE).also { stijl.addSource(it) }).setGeoJson(d.kraje)
            if (stijl.getLayer(L_KRAJE) == null) dodaj(
                FillLayer(L_KRAJE, SRC_KRAJE).withProperties(
                    PropertyFactory.fillColor("#7C6BFF"), PropertyFactory.fillOpacity(0.16f), PropertyFactory.fillOutlineColor("#9D90FF"),
                ),
            )
        } else stijl.getLayer(L_KRAJE)?.let { stijl.removeLayer(it) }

        if (heat) {
            (stijl.getSourceAs<GeoJsonSource>(SRC_PUNKTY) ?: GeoJsonSource(SRC_PUNKTY).also { stijl.addSource(it) }).setGeoJson(d.punkty)
            if (stijl.getLayer(L_HEAT) == null) dodaj(
                HeatmapLayer(L_HEAT, SRC_PUNKTY).withProperties(
                    // waga: logarytm liczby zdjęć (dom z 7 tys. zdjęć nie może zgasić reszty)
                    PropertyFactory.heatmapWeight(Expression.interpolate(Expression.linear(), Expression.get("n"), Expression.stop(1, 0.35), Expression.stop(20, 0.7), Expression.stop(500, 1.0))),
                    PropertyFactory.heatmapIntensity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(0, 1.2), Expression.stop(9, 2.0), Expression.stop(15, 3.0))),
                    PropertyFactory.heatmapRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(0, 10), Expression.stop(5, 22), Expression.stop(10, 40), Expression.stop(15, 60))),
                    PropertyFactory.heatmapOpacity(0.75f),
                    PropertyFactory.heatmapColor(
                        Expression.interpolate(
                            Expression.linear(), Expression.heatmapDensity(),
                            Expression.stop(0.0, Expression.rgba(0, 0, 0, 0)),
                            Expression.stop(0.15, Expression.rgba(90, 100, 255, 0.35)),
                            Expression.stop(0.4, Expression.rgba(170, 110, 240, 0.6)),
                            Expression.stop(0.7, Expression.rgba(225, 120, 225, 0.8)),
                            Expression.stop(1.0, Expression.rgba(245, 150, 230, 0.9)),
                        ),
                    ),
                ),
            )
        } else stijl.getLayer(L_HEAT)?.let { stijl.removeLayer(it) }
    }.onFailure { Log.w("Fotos", "kaart: warstwy", it) }
}
