package com.immichclient.tv

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Zakładki sekcji Synology Photos. */
enum class SynoZakladka { MOJE, UDOSTEPNIONE, WSPOLNA }

/**
 * Sekcja Synology Photos: moje albumy, albumy udostępnione mi i foldery przestrzeni współdzielonej; OK otwiera album
 * (siatka po dniach, przeglądarka, pokaz) albo folder (podfoldery + „Zdjęcia w tym folderze”).
 */
class SynologyStaat(private val scope: CoroutineScope) {
    var zakladka by mutableStateOf(SynoZakladka.MOJE)
        private set
    var albumy by mutableStateOf<List<Synology.AlbumSyno>?>(null)
        private set
    /** ścieżka w przestrzeni współdzielonej (pierwszy = folder główny) */
    var sciezka by mutableStateOf<List<Synology.FolderSyno>>(emptyList())
        private set
    var foldery by mutableStateOf<List<Synology.FolderSyno>?>(null)
        private set
    var fout by mutableStateOf<String?>(null)
        private set

    init { laduj() }

    fun wybierz(z: SynoZakladka) { if (z != zakladka) { zakladka = z; laduj() } }

    fun laduj() {
        if (!Synology.skonfigurowane) return
        fout = null
        val z = zakladka
        scope.launch {
            runCatching {
                when (z) {
                    SynoZakladka.MOJE -> { albumy = null; albumy = Synology.albumy(); sprawdz() }
                    SynoZakladka.UDOSTEPNIONE -> { albumy = null; albumy = Synology.albumyUdostepnione(); sprawdz() }
                    SynoZakladka.WSPOLNA -> {
                        if (sciezka.isEmpty()) sciezka = listOf(Synology.folderGlowny())
                        foldery = null; foldery = Synology.podfoldery(sciezka.last().id)
                    }
                }
            }.onFailure { fout = it.message; Log.w("Fotos", "synology $z", it) }
        }
    }

    /** wynik testu jednej miniatury (null = w porządku); pokazywany pod listą, gdy obrazki się nie wczytują */
    var problemMiniatur by mutableStateOf<String?>(null)
        private set

    private suspend fun sprawdz() {
        problemMiniatur = albumy?.firstNotNullOfOrNull { it.okladka }?.let { Synology.dobierzWariant(Taal.ctx, it) }
    }

    fun wejdz(f: Synology.FolderSyno) { sciezka = sciezka + f; laduj() }
    /** Wstecz w przestrzeni współdzielonej: folder wyżej; false = już w folderze głównym. */
    fun wyzej(): Boolean {
        if (zakladka != SynoZakladka.WSPOLNA || sciezka.size <= 1) return false
        sciezka = sciezka.dropLast(1); laduj(); return true
    }
}

@Composable
fun SynologyScherm(staat: SynologyStaat, nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    var logowanie by remember { mutableStateOf(false) }
    val wersja = Synology.stan
    LaunchedEffect(wersja) { if (Synology.skonfigurowane && staat.albumy == null && staat.foldery == null) staat.laduj() }
    BackHandler(enabled = staat.zakladka == SynoZakladka.WSPOLNA && staat.sciezka.size > 1) { staat.wyzej() }
    val wspolna = staat.zakladka == SynoZakladka.WSPOLNA
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SchermKop(
                    if (wspolna && staat.sciezka.size > 1) staat.sciezka.last().nazwa else stringResource(R.string.sectie_synology),
                    when {
                        !Synology.skonfigurowane -> stringResource(R.string.synology_brak)
                        staat.fout != null -> stringResource(R.string.kaart_niet_geladen, staat.fout.orEmpty())
                        wspolna -> staat.foldery?.let { pluralStringResource(R.plurals.mappen_aantal, it.size, it.size) + "  ·  " + staat.sciezka.joinToString(" / ") { f -> f.nazwa } } ?: stringResource(R.string.laden)
                        staat.albumy == null -> stringResource(R.string.laden)
                        else -> pluralStringResource(R.plurals.albums_aantal, staat.albumy.orEmpty().size, staat.albumy.orEmpty().size) + "  ·  " + Synology.nazwaKonta
                    },
                    etykieta = stringResource(R.string.etyk_kolekcje),
                ) {
                    if (!Synology.skonfigurowane) Keuze(selected = false, onClick = { logowanie = true }, modifier = Modifier.focusRequester(pierwszy), tekst = stringResource(R.string.synology_polacz))
                    else if (wspolna && staat.sciezka.isNotEmpty()) {
                        // zdjęcia z bieżącego folderu przestrzeni współdzielonej
                        val f = staat.sciezka.last()
                        Keuze(selected = false, onClick = { nav.open(Scherm.Lijst(f.nazwa, stringResource0(R.string.synology_wspolna), true, nav.scope) { p -> Synology.elementyFolderu(f.id, p) }) },
                            tekst = stringResource(R.string.synology_zdjecia_folderu))
                    }
                }
                staat.problemMiniatur?.let { Text(stringResource(R.string.synology_miniatury_blad, it), style = MaterialTheme.typography.bodySmall, color = Color(0xFFFCA5A5), maxLines = 3) }
                if (Synology.skonfigurowane) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Keuze(selected = staat.zakladka == SynoZakladka.MOJE, onClick = { staat.wybierz(SynoZakladka.MOJE) }, modifier = Modifier.focusRequester(pierwszy), tekst = stringResource(R.string.synology_moje))
                    Keuze(selected = staat.zakladka == SynoZakladka.UDOSTEPNIONE, onClick = { staat.wybierz(SynoZakladka.UDOSTEPNIONE) }, tekst = stringResource(R.string.synology_udostepnione))
                    Keuze(selected = wspolna, onClick = { staat.wybierz(SynoZakladka.WSPOLNA) }, tekst = stringResource(R.string.synology_wspolna))
                }
            }
        }
        if (wspolna) items(staat.foldery.orEmpty(), key = { "f" + it.id }) { f ->
            Card(
                onClick = { staat.wejdz(f) },
                modifier = Modifier.fillMaxWidth().aspectRatio(2.2f),
                shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
                scale = CardDefaults.scale(focusedScale = 1.06f),
            ) {
                Row(Modifier.fillMaxSize().background(Kleur.vlakHoog).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(30.dp), tint = Color(0xFFF5B83D))
                    Text(f.nazwa, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        else items(staat.albumy.orEmpty(), key = { "a" + it.id + it.haslo.orEmpty() + "/" + wersja }) { a ->
            Column {
                Card(
                    onClick = { nav.open(Scherm.Lijst(a.nazwa, a.wlasciciel ?: "Synology Photos", true, nav.scope) { p -> Synology.elementy(a, p) }) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.35f),
                    shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.06f),
                ) {
                    Box(Modifier.fillMaxSize().background(Kleur.vlakHoog)) {
                        a.okladka?.let { AsyncImage(beeld(Immich.thumb(it)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB0000000)))))
                    }
                }
                Text(a.nazwa, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text(listOfNotNull(pluralStringResource(R.plurals.items, a.ile, a.ile), a.wlasciciel).joinToString("  ·  "), style = MaterialTheme.typography.labelMedium, color = Kleur.zacht, maxLines = 1)
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { pierwszy.requestFocus() } }
    if (logowanie) SynologyLogowanie { logowanie = false; staat.laduj() }
}

/** Tekst z zasobów poza kompozycją (w lambdach onClick). */
private fun stringResource0(id: Int) = Taal.s(id)

/** Logowanie do Synology Photos: adres NAS, login, hasło i (opcjonalnie) kod 2FA; pola wpisuje się pilotem albo telefonem. */
@Composable
fun SynologyLogowanie(sluit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var adres by remember { mutableStateOf(Synology.adres.ifBlank { "http://" }) }
    var login by remember { mutableStateOf("") }
    var haslo by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var pole by remember { mutableStateOf<String?>(null) }
    var blad by remember { mutableStateOf<String?>(null) }
    var trwa by remember { mutableStateOf(false) }
    if (pole == null) MenuWarstwa(stringResource(R.string.synology_polacz), blad ?: stringResource(R.string.synology_uitleg), sluit) { pierwszy ->
        Knop(stringResource(R.string.synology_adres), adres, pierwszy.width(560.dp)) { pole = "adres" }
        Knop(stringResource(R.string.synology_login), login.ifBlank { "—" }, Modifier.width(560.dp)) { pole = "login" }
        Knop(stringResource(R.string.synology_haslo), if (haslo.isEmpty()) "—" else "•".repeat(haslo.length), Modifier.width(560.dp)) { pole = "haslo" }
        Knop(stringResource(R.string.synology_otp), otp.ifBlank { stringResource(R.string.synology_otp_brak) }, Modifier.width(560.dp)) { pole = "otp" }
        Knop(stringResource(if (trwa) R.string.laden else R.string.synology_polacz), null, Modifier.width(560.dp)) {
            if (trwa) return@Knop
            trwa = true; blad = null
            scope.launch {
                runCatching { Synology.zaloguj(ctx, adres, login, haslo, otp) }
                    .onSuccess { trwa = false; Edycja.komunikat = ctx.getString(R.string.synology_polaczono); sluit() }
                    .onFailure { e ->
                        trwa = false
                        blad = when ((e as? Synology.Blad)?.kod) {
                            400 -> ctx.getString(R.string.synology_zle_haslo)
                            403, 404 -> ctx.getString(R.string.synology_potrzebny_otp)
                            401, 402 -> ctx.getString(R.string.synology_konto_wylaczone)
                            407 -> ctx.getString(R.string.synology_zablokowany)
                            else -> if (e.message.orEmpty().contains("HTTP 400")) ctx.getString(R.string.synology_port)
                                else ctx.getString(R.string.synology_blad, e.message.orEmpty())
                        }
                    }
            }
        }
    }
    when (pole) {
        "adres" -> TekstInvoer(stringResource(R.string.synology_adres), adres, actie = stringResource(R.string.zapisz), sluit = { pole = null }) { adres = it; pole = null }
        "login" -> TekstInvoer(stringResource(R.string.synology_login), login, actie = stringResource(R.string.zapisz), sluit = { pole = null }) { login = it; pole = null }
        "haslo" -> TekstInvoer(stringResource(R.string.synology_haslo), haslo, actie = stringResource(R.string.zapisz), sluit = { pole = null }) { haslo = it; pole = null }
        "otp" -> TekstInvoer(stringResource(R.string.synology_otp), otp, actie = stringResource(R.string.zapisz), sluit = { pole = null }) { otp = it; pole = null }
    }
}
