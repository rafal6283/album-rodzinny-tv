package com.immichclient.tv

import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import okhttp3.Request
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Udostępnianie gościom (jak w Immichin): w przeglądarce wybierasz zdjęcia i filmy (▼ → Udostępnij), telewizor
 * pokazuje kod QR, a goście w tej samej sieci otwierają w telefonie stronę z galerią i pobierają pliki — bez aplikacji
 * i konta. Telewizor pośredniczy: pobiera z Immich z własnym kluczem, klucz nigdy nie trafia do telefonu.
 *
 * Bezpieczeństwo: tylko adresy z sieci lokalnej, jednorazowy losowy kod w adresie, wyłącznie wybrane elementy,
 * serwer zatrzymuje się po 10 minutach bez ruchu, przy zatrzymaniu ręcznym i gdy aplikacja schodzi z ekranu.
 * Pliki: zdjęcia w pełnej rozdzielczości jako JPEG (Immich „fullsize”), filmy w wersji do odtwarzania; oryginałów
 * nie, bo klucz aplikacji ich nie pobiera (brak uprawnienia asset.download).
 */
object Udostepnianie {
    private const val TAG = "Udostepnianie"
    private const val BEZCZYNNOSC_MS = 10 * 60 * 1000L

    val koszyk = mutableStateListOf<Asset>()
    var url by mutableStateOf<String?>(null)
        private set
    var pobrania by mutableIntStateOf(0)
        private set
    var otwarte by mutableStateOf(false)
    var blad by mutableStateOf<Int?>(null)
        private set

    @Volatile private var serwer: ServerSocket? = null
    @Volatile private var kod = ""
    @Volatile private var ostatnio = 0L

    /** szybkie sprawdzanie na kafelkach (dziesiątki naraz): id zaznaczonych */
    private val idy = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** tryb zaznaczania: krótkie OK na kafelku zaznacza / odznacza zamiast otwierać (Wstecz kończy) */
    var tryb by mutableStateOf(false)

    fun zawiera(a: Asset) = idy[a.id] == true
    fun przelacz(a: Asset) {
        if (zawiera(a)) { koszyk.removeAll { it.id == a.id }; idy.remove(a.id) } else { koszyk.add(a); idy[a.id] = true }
        if (koszyk.isEmpty()) tryb = false
    }
    /** wyczyść całe zaznaczenie (i zakończ tryb zaznaczania) */
    fun wyczysc() { koszyk.clear(); idy.clear(); tryb = false }

    /** Ekran z kodem QR; serwer startuje przy pierwszym otwarciu. */
    fun otworz(c: Context) { if (serwer == null) start(c); otwarte = true }

    @Suppress("UNUSED_PARAMETER")
    private fun start(c: Context) {
        blad = null
        val ip = Koppel.eigenIp()
        val ss = runCatching { ServerSocket(0) }.getOrNull()
        if (ip == null || ss == null) { blad = if (ip == null) R.string.fout_geen_netwerk else R.string.fout_geen_poort; return }
        val tekens = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        kod = String(CharArray(8) { tekens[r.nextInt(tekens.length)] })
        serwer = ss; pobrania = 0; ostatnio = System.currentTimeMillis()
        url = "http://$ip:${ss.localPort}/$kod"
        Log.i(TAG, "start na porcie ${ss.localPort}")
        thread(name = "Udostepnianie", isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { obsluz(s) }.onFailure { Log.w(TAG, "żądanie", it) }; runCatching { s.close() } }
            }
            Log.i(TAG, "zatrzymany")
        }
        // strażnik bezczynności
        thread(name = "UdostepnianieStraz", isDaemon = true) {
            while (serwer === ss) {
                Thread.sleep(15_000)
                if (serwer === ss && System.currentTimeMillis() - ostatnio > BEZCZYNNOSC_MS) { Log.i(TAG, "10 min bez ruchu"); zatrzymaj() }
            }
        }
    }

    fun zatrzymaj() {
        serwer?.let { runCatching { it.close() } }
        serwer = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { url = null; otwarte = false }
    }

    // ---- HTTP ----

    private fun obsluz(s: Socket) {
        s.soTimeout = 20_000
        val a = s.inetAddress
        val uit = s.getOutputStream()
        if (!(a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)) { odpowiedz(uit, 403, "text/plain", "403".toByteArray()); return }
        val wej = s.getInputStream().buffered()
        val pierwsza = linia(wej) ?: return
        while (true) { val l = linia(wej) ?: break; if (l.isEmpty()) break }
        val czesci = pierwsza.split(' ')
        if (czesci.size < 2 || czesci[0] != "GET") { odpowiedz(uit, 405, "text/plain", "405".toByteArray()); return }
        val sciezka = czesci[1].substringBefore('?').trimEnd('/')
        if (kod.isEmpty() || !(sciezka == "/$kod" || sciezka.startsWith("/$kod/"))) { odpowiedz(uit, 403, "text/html; charset=utf-8", strona403().toByteArray()); return }
        ostatnio = System.currentTimeMillis()
        val reszta = sciezka.removePrefix("/$kod")
        val lista = koszyk.toList()
        when {
            reszta.isEmpty() -> odpowiedz(uit, 200, "text/html; charset=utf-8", strona(lista).toByteArray())
            reszta.startsWith("/t/") -> lista.getOrNull(reszta.removePrefix("/t/").toIntOrNull() ?: -1)
                ?.let { posrednicz(uit, Immich.preview(it.id), "image/jpeg", null) } ?: odpowiedz(uit, 404, "text/plain", "404".toByteArray())
            reszta.startsWith("/f/") -> {
                val i = reszta.removePrefix("/f/").toIntOrNull() ?: -1
                val z = lista.getOrNull(i)
                if (z == null) odpowiedz(uit, 404, "text/plain", "404".toByteArray())
                else {
                    val nazwa = "album-rodzinny-${z.datum.toLocalDate()}-${i + 1}." + if (z.video) "mp4" else "jpg"
                    posrednicz(uit, if (z.video) Immich.video(z.id) else Immich.pelny(z.id), if (z.video) "video/mp4" else "image/jpeg", nazwa)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { pobrania++ }
                }
            }
            else -> odpowiedz(uit, 404, "text/plain", "404".toByteArray())
        }
    }

    /** Pobierz z Immich (z kluczem) i przekaż dalej strumieniowo. */
    private fun posrednicz(uit: OutputStream, adres: String, typ: String, plik: String?) {
        Immich.http.newCall(Request.Builder().url(adres).build()).execute().use { r ->
            if (!r.isSuccessful) { odpowiedz(uit, 502, "text/plain", "502".toByteArray()); return }
            val body = r.body ?: return
            val dl = body.contentLength()
            val naglowki = StringBuilder("HTTP/1.1 200 OK\r\nContent-Type: ${r.header("Content-Type") ?: typ}\r\nCache-Control: no-store\r\nConnection: close\r\n")
            if (dl >= 0) naglowki.append("Content-Length: $dl\r\n")
            if (plik != null) naglowki.append("Content-Disposition: attachment; filename=\"$plik\"\r\n")
            uit.write(naglowki.append("\r\n").toString().toByteArray())
            body.byteStream().copyTo(uit, 64 * 1024)
            uit.flush()
        }
    }

    private fun odpowiedz(uit: OutputStream, kodHttp: Int, typ: String, tresc: ByteArray) {
        uit.write("HTTP/1.1 $kodHttp X\r\nContent-Type: $typ\r\nContent-Length: ${tresc.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        uit.write(tresc); uit.flush()
    }

    private fun linia(w: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = w.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar()); if (sb.length > 8192) return null
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun strona403() = "<!doctype html><meta charset=utf-8><body style=\"background:#09090f;color:#a3a3b5;font-family:sans-serif;padding:40px;text-align:center\">${esc(Taal.s(R.string.udost_wygaslo))}</body>"

    /** Galeria dla telefonu: kafelki z przyciskiem pobierania, w kolorach aplikacji. Adresy względne do /<kod>/. */
    private fun strona(lista: List<Asset>): String {
        val kafle = lista.mapIndexed { i, a ->
            val opis = esc(listOfNotNull(a.datum.toLocalDate().toString(), a.stad).joinToString(" · "))
            """<a class="k" href="$kod/f/$i" download><img src="$kod/t/$i" loading="lazy" alt=""><span>${if (a.video) "▶ " else ""}$opis</span><b>⬇</b></a>"""
        }.joinToString("")
        return """<!doctype html><html lang="${Taal.code}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(Taal.s(R.string.app_name))}</title><style>
body{margin:0;background:#09090f;color:#f1f1f6;font-family:system-ui,sans-serif}
header{padding:28px 18px 10px}h1{font-family:Georgia,serif;font-weight:400;font-size:34px;margin:0}
p{color:#a3a3b5;margin:6px 0 0}.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px;padding:14px}
.k{position:relative;display:block;border-radius:14px;overflow:hidden;background:#181822;color:#fff;text-decoration:none;aspect-ratio:1}
.k img{width:100%;height:100%;object-fit:cover}.k span{position:absolute;left:0;right:0;bottom:0;padding:22px 10px 8px;font-size:12px;background:linear-gradient(transparent,#000c)}
.k b{position:absolute;top:8px;right:8px;background:#fff;color:#101018;border-radius:50%;width:30px;height:30px;display:grid;place-items:center}
</style></head><body><header><h1>${esc(Taal.s(R.string.app_name))}</h1><p>${esc(Taal.s(R.string.udost_strona_sub))}</p></header><div class="g">$kafle</div></body></html>"""
    }
}

/** Ekran udostępniania na telewizorze: kod QR, adres, wybrane elementy, licznik pobrań, zatrzymanie. */
@Composable
fun UdostepnianieScherm() {
    BackHandler { Udostepnianie.otwarte = false }
    val url = Udostepnianie.url
    val qr = remember(url) { url?.let { Koppel.qr(it).asImageBitmap() } }
    val pierwszy = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { pierwszy.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Kleur.achtergrond).focusVangen()) {
        Tlo(Color(0xFF1E3A2E))
        Row(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(300.dp)) {
                Box(Modifier.size(280.dp).background(Color.White, RoundedCornerShape(28.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
                    if (qr != null) Image(qr, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                    else Text(stringResource(Udostepnianie.blad ?: R.string.laden), color = Color.Black)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp), tint = Kleur.zacht)
                    Text("  " + stringResource(R.string.udost_skanuj), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht)
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy((-10).dp), verticalAlignment = Alignment.CenterVertically) {
                    Udostepnianie.koszyk.take(6).forEach { a ->
                        AsyncImage(beeld(Immich.thumb(a.id)), null, Modifier.size(46.dp).clip(CircleShape).border(2.dp, Kleur.achtergrond, CircleShape), contentScale = ContentScale.Crop)
                    }
                    if (Udostepnianie.koszyk.size > 6) Text("+${Udostepnianie.koszyk.size - 6}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp))
                }
            }
            Spacer(Modifier.width(48.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.background(Color(0x3322C55E), RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Color(0xFF22C55E), CircleShape))
                    Text("  " + stringResource(R.string.udost_na_zywo), style = MaterialTheme.typography.labelMedium, color = Color(0xFF86EFAC))
                }
                Text(stringResource(R.string.udost_tytul, Udostepnianie.koszyk.size), style = TytulStyl, modifier = Modifier.padding(top = 10.dp))
                Text(licznikMix(Udostepnianie.koszyk), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().background(Color(0x14FFFFFF), RoundedCornerShape(20.dp)).padding(18.dp)) {
                    Text(stringResource(R.string.udost_adres), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Filled.Link, null, Modifier.size(20.dp), tint = Kleur.pinezka)
                        Text("  " + (url ?: "—"), style = MaterialTheme.typography.titleLarge)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Filled.Shield, null, Modifier.size(20.dp), tint = Color(0xFF4ADE80))
                        Text("  " + stringResource(R.string.udost_lokalnie), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = { Udostepnianie.wyczysc(); Udostepnianie.zatrzymaj() }, modifier = Modifier.focusRequester(pierwszy),
                        colors = ButtonDefaults.colors(containerColor = Color(0x22FFFFFF), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color(0xFF101018))) {
                        Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.udost_wyczysc), color = androidx.tv.material3.LocalContentColor.current)
                    }
                    Button(onClick = { Udostepnianie.zatrzymaj() }, colors = ButtonDefaults.colors(containerColor = Color(0x22FFFFFF), contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color(0xFF101018))) {
                        Icon(Icons.Filled.StopCircle, null, Modifier.size(18.dp)); Text("  " + stringResource(R.string.udost_zatrzymaj), color = androidx.tv.material3.LocalContentColor.current)
                    }
                }
                Text(stringResource(R.string.udost_pobrania, Udostepnianie.pobrania), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Timer, null, Modifier.size(16.dp), tint = Kleur.zacht)
                    Text("  " + stringResource(R.string.udost_stop_info), style = MaterialTheme.typography.bodySmall, color = Kleur.zacht)
                }
            }
        }
    }
}
