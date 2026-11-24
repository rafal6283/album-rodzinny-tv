package com.immichclient.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tryb imprezy: goście skanują kod QR i wysyłają zdjęcia z telefonów prosto na telewizor; nowe zdjęcia od razu
 * wskakują do pokazu. Opcjonalnie trafiają też do albumu „Impreza <data>” w Immich (uprawnienie asset.upload).
 * Tylko sieć lokalna, jednorazowy kod, zdjęcia do 30 MB; pliki w pamięci podręcznej aplikacji (znikają po wyjściu).
 */
object Impreza {
    private const val TAG = "Impreza"
    private const val MAX = 30L * 1024 * 1024

    val zdjecia = mutableStateListOf<File>()
    var adres by mutableStateOf<String?>(null)
        private set
    var doImmich by mutableStateOf(false)
    var wyslane by mutableIntStateOf(0)
        private set
    @Volatile private var serwer: ServerSocket? = null
    @Volatile private var kod = ""
    private var katalog: File? = null
    private var albumId: String? = null
    private val tlo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(c: Context) {
        if (serwer != null) return
        val ip = Koppel.eigenIp() ?: return
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        katalog = File(c.cacheDir, "impreza").apply { deleteRecursively(); mkdirs() }
        zdjecia.clear(); wyslane = 0; albumId = null
        val znaki = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        kod = String(CharArray(8) { znaki[r.nextInt(znaki.length)] })
        serwer = ss
        adres = "http://$ip:${ss.localPort}/$kod"
        thread(name = "Impreza", isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { obsluz(s) }.onFailure { Log.w(TAG, "żądanie", it) }; runCatching { s.close() } }
            }
        }
    }

    fun stop() {
        serwer?.let { runCatching { it.close() } }
        serwer = null; adres = null
    }

    private fun obsluz(s: Socket) {
        s.soTimeout = 60_000
        val a = s.inetAddress
        val uit = s.getOutputStream()
        if (!(a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)) { odpowiedz(uit, 403, "text/plain", "403"); return }
        val wej = s.getInputStream().buffered()
        val pierwsza = linia(wej) ?: return
        var dlugosc = -1L
        while (true) {
            val l = linia(wej) ?: break
            if (l.isEmpty()) break
            if (l.startsWith("Content-Length:", true)) dlugosc = l.substringAfter(':').trim().toLongOrNull() ?: -1
        }
        val czesci = pierwsza.split(' ')
        val sciezka = czesci.getOrNull(1)?.substringBefore('?')?.trimEnd('/').orEmpty()
        if (kod.isEmpty() || !(sciezka == "/$kod" || sciezka.startsWith("/$kod/"))) { odpowiedz(uit, 403, "text/plain", "403"); return }
        when {
            czesci[0] == "GET" -> odpowiedz(uit, 200, "text/html; charset=utf-8", strona())
            czesci[0] == "POST" && sciezka == "/$kod/u" -> {
                if (dlugosc <= 0 || dlugosc > MAX) { odpowiedz(uit, 413, "text/plain", "413"); return }
                val dir = katalog ?: return
                val plik = File(dir, "${System.currentTimeMillis()}-${zdjecia.size}.jpg")
                plik.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var zostalo = dlugosc
                    while (zostalo > 0) { val n = wej.read(buf, 0, minOf(buf.size.toLong(), zostalo).toInt()); if (n < 0) break; o.write(buf, 0, n); zostalo -= n }
                }
                Handler(Looper.getMainLooper()).post { zdjecia.add(plik) }
                if (doImmich) tlo.launch {
                    runCatching {
                        val id = Immich.wgraj(plik, plik.name)
                        albumId = Immich.doAlbumuONazwie(Taal.s(R.string.impreza_album, java.time.LocalDate.now().toString()), listOf(id), albumId)
                    }.onSuccess { Handler(Looper.getMainLooper()).post { wyslane++ } }.onFailure { Log.w(TAG, "do Immich", it); Edycja.komunikat = Edycja.blad(it) }
                }
                odpowiedz(uit, 200, "text/plain", "ok")
            }
            else -> odpowiedz(uit, 405, "text/plain", "405")
        }
    }

    private fun odpowiedz(uit: OutputStream, k: Int, typ: String, tresc: String) {
        val b = tresc.toByteArray()
        uit.write("HTTP/1.1 $k X\r\nContent-Type: $typ\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        uit.write(b); uit.flush()
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

    /** Strona dla gościa: wybór zdjęć z galerii telefonu, wysyłka po kolei z paskiem postępu (zdjęcia zmniejszane do 2560 px). */
    private fun strona(): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(Taal.s(R.string.impreza_tytul))}</title><style>
body{margin:0;background:#09090f;color:#f1f1f6;font-family:system-ui,sans-serif;padding:24px;text-align:center}
h1{font-size:24px;margin:8px 0}p{color:#a3a3b5}
label{display:block;margin:28px auto;padding:22px;border-radius:18px;background:#4d6bff;color:#fff;font-size:20px;font-weight:600;max-width:360px}
input{display:none}#s{font-size:16px;color:#86efac;min-height:24px}
</style></head><body>
<h1>🎉 ${esc(Taal.s(R.string.impreza_tytul))}</h1><p>${esc(Taal.s(R.string.impreza_strona))}</p>
<label>📷 ${esc(Taal.s(R.string.impreza_wybierz))}<input id="f" type="file" accept="image/*" multiple></label>
<div id="s"></div>
<script>
var s=document.getElementById('s');
function zmniejsz(f){return new Promise(function(ok){var i=new Image();i.onload=function(){var k=Math.min(1,2560/Math.max(i.width,i.height));
var c=document.createElement('canvas');c.width=Math.round(i.width*k);c.height=Math.round(i.height*k);c.getContext('2d').drawImage(i,0,0,c.width,c.height);
c.toBlob(function(b){ok(b||f)},'image/jpeg',0.9)};i.onerror=function(){ok(f)};i.src=URL.createObjectURL(f)})}
document.getElementById('f').onchange=async function(e){var p=e.target.files;for(var i=0;i<p.length;i++){s.textContent=(i+1)+' / '+p.length+' …';
var b=await zmniejsz(p[i]);await fetch('/$kod/u',{method:'POST',body:b});}s.textContent='✓ '+p.length;e.target.value=''};
</script></body></html>"""
}

/** Ekran trybu imprezy: pokaz nadesłanych zdjęć (najnowsze pierwsze w kolejce) z kodem QR w rogu. */
@Composable
fun ImprezaScherm() {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    DisposableEffect(Unit) { Impreza.start(ctx); onDispose { Impreza.stop() } }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    var i by remember { mutableIntStateOf(0) }
    val n = Impreza.zdjecia.size
    // nowe zdjęcie: od razu na ekran; potem co 6 s następne w kółko
    LaunchedEffect(n) { if (n > 0) i = n - 1 }
    LaunchedEffect(i, n) { if (n > 1) { delay(6000); i = (i + 1) % n } }
    MuzykaPokazu(grac = n > 0)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val plik = Impreza.zdjecia.getOrNull(i)
        AnimatedContent(plik, transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) }, label = "impreza") { p ->
            if (p != null) AsyncImage(p, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.impreza_czekam), style = MaterialTheme.typography.headlineMedium, color = Kleur.zacht, textAlign = TextAlign.Center)
            }
        }
        Impreza.adres?.let { a ->
            val qr = remember(a) { Koppel.qr(a).asImageBitmap() }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(24.dp).background(Color(0xCC09090F), RoundedCornerShape(18.dp)).padding(14.dp).width(200.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(qr, null, Modifier.size(170.dp).background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp))
                Text("🎉 " + stringResource(R.string.impreza_qr), style = MaterialTheme.typography.labelLarge, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.impreza_licznik, n) + if (Impreza.doImmich) "  ·  Immich: ${Impreza.wyslane}" else "",
                    style = MaterialTheme.typography.labelMedium, color = Kleur.zacht)
            }
        } ?: Text(stringResource(R.string.fout_geen_netwerk), color = Kleur.zacht, modifier = Modifier.align(Alignment.Center))
        // przełącznik „zapisuj w Immich” (OK)
        Knop(
            stringResource(if (Impreza.doImmich) R.string.impreza_immich_tak else R.string.impreza_immich_nie), null,
            Modifier.align(Alignment.TopEnd).padding(24.dp).width(280.dp).focusRequester(fr),
        ) { Impreza.doImmich = !Impreza.doImmich }
    }
}
