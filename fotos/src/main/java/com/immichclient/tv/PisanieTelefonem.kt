package com.immichclient.tv

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Pisanie z telefonu: przy polu tekstowym (szukanie, imię osoby, nazwa albumu) telewizor pokazuje kod QR; telefon w
 * tej samej sieci otwiera prostą stronę z polem tekstowym i wszystko, co wpiszesz, od razu pojawia się na telewizorze.
 * Tylko sieć lokalna, jednorazowy losowy kod w adresie, serwer działa tylko, gdy pole tekstowe jest otwarte.
 */
object PisanieTelefonem {
    private const val TAG = "Pisanie"
    @Volatile private var serwer: ServerSocket? = null
    @Volatile private var kod = ""
    /** odbiorca tekstu (otwarte pole); wywoływany w wątku głównym */
    @Volatile private var odbiorca: ((String, Boolean) -> Unit)? = null
    @Volatile private var tytul = ""
    @Volatile private var poczatek = ""

    /** Start dla otwartego pola; zwraca adres do kodu QR albo null (brak sieci). [odbior] dostaje (tekst, gotowe). */
    fun start(tytulPola: String, tekst: String, odbior: (String, Boolean) -> Unit): String? {
        stop()
        val ip = Koppel.eigenIp() ?: return null
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        val znaki = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        kod = String(CharArray(8) { znaki[r.nextInt(znaki.length)] })
        tytul = tytulPola; poczatek = tekst; odbiorca = odbior; serwer = ss
        thread(name = "Pisanie", isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { obsluz(s) }.onFailure { Log.w(TAG, "żądanie", it) }; runCatching { s.close() } }
            }
        }
        return "http://$ip:${ss.localPort}/$kod"
    }

    fun stop() {
        serwer?.let { runCatching { it.close() } }
        serwer = null; odbiorca = null
    }

    /** Bieżący tekst na telewizorze (np. po wpisaniu pilotem), żeby strona w telefonie startowała od niego. */
    fun ustawTekst(t: String) { poczatek = t }

    private fun obsluz(s: Socket) {
        s.soTimeout = 15_000
        val a = s.inetAddress
        val uit = s.getOutputStream()
        if (!(a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)) { odpowiedz(uit, 403, "text/plain", "403"); return }
        val wej = s.getInputStream().buffered()
        val pierwsza = linia(wej) ?: return
        while (true) { val l = linia(wej) ?: break; if (l.isEmpty()) break }
        val czesci = pierwsza.split(' ')
        if (czesci.size < 2 || czesci[0] != "GET") { odpowiedz(uit, 405, "text/plain", "405"); return }
        val pelna = czesci[1]
        val sciezka = pelna.substringBefore('?').trimEnd('/')
        if (kod.isEmpty() || !(sciezka == "/$kod" || sciezka.startsWith("/$kod/"))) { odpowiedz(uit, 403, "text/plain", "403"); return }
        val reszta = sciezka.removePrefix("/$kod")
        val q = pelna.substringAfter('?', "").split('&').associate { p -> p.substringBefore('=') to runCatching { URLDecoder.decode(p.substringAfter('=', ""), "UTF-8") }.getOrDefault("") }
        when (reszta) {
            "" -> odpowiedz(uit, 200, "text/html; charset=utf-8", strona())
            "/t" -> {
                val t = q["q"].orEmpty().take(200)
                val gotowe = q["ok"] == "1"
                poczatek = t
                Handler(Looper.getMainLooper()).post { odbiorca?.invoke(t, gotowe) }
                odpowiedz(uit, 200, "text/plain", "ok")
            }
            else -> odpowiedz(uit, 404, "text/plain", "404")
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

    private fun strona(): String = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(tytul)}</title><style>
body{margin:0;background:#09090f;color:#f1f1f6;font-family:system-ui,sans-serif;padding:24px}
h1{font-size:20px;font-weight:600;margin:0 0 4px}p{color:#a3a3b5;margin:0 0 18px;font-size:14px}
input{width:100%;box-sizing:border-box;font-size:20px;padding:14px 16px;border-radius:14px;border:2px solid #4d6bff;background:#181822;color:#fff;outline:none}
button{margin-top:14px;width:100%;font-size:18px;padding:14px;border:0;border-radius:14px;background:#4d6bff;color:#fff;font-weight:600}
</style></head><body>
<h1>${esc(tytul)}</h1><p>${esc(Taal.s(R.string.pisz_telefon_strona))}</p>
<input id="t" value="${esc(poczatek)}" autofocus autocomplete="off" autocapitalize="sentences">
<button id="ok">${esc(Taal.s(R.string.zapisz))} ✓</button>
<script>
var t=document.getElementById('t');
function wyslij(ok){fetch('/$kod/t?q='+encodeURIComponent(t.value)+(ok?'&ok=1':''))}
t.addEventListener('input',function(){wyslij(false)});
t.addEventListener('keydown',function(e){if(e.key==='Enter'){wyslij(true)}});
document.getElementById('ok').onclick=function(){wyslij(true)};
t.focus();t.setSelectionRange(t.value.length,t.value.length);
</script></body></html>"""
}
