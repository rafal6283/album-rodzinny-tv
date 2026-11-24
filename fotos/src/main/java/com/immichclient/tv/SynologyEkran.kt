package com.immichclient.tv

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Sekcja Synology Photos: albumy z Synology; OK otwiera album (siatka po dniach, przeglądarka, pokaz). */
class SynologyStaat(private val scope: CoroutineScope) {
    var albumy by mutableStateOf<List<Synology.AlbumSyno>?>(null)
        private set
    var fout by mutableStateOf<String?>(null)
        private set

    init { laduj() }

    fun laduj() {
        if (!Synology.skonfigurowane) return
        fout = null
        scope.launch {
            runCatching { Synology.albumy() }.onSuccess { albumy = it }.onFailure { fout = it.message; Log.w("Fotos", "synology albumy", it) }
        }
    }
}

@Composable
fun SynologyScherm(staat: SynologyStaat, nav: Nav) {
    val pierwszy = remember { FocusRequester() }
    var logowanie by remember { mutableStateOf(false) }
    val wersja = Synology.stan
    LaunchedEffect(wersja) { if (Synology.skonfigurowane && staat.albumy == null) staat.laduj() }
    val lista = staat.albumy.orEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SchermKop(
                stringResource(R.string.sectie_synology),
                when {
                    !Synology.skonfigurowane -> stringResource(R.string.synology_brak)
                    staat.fout != null -> stringResource(R.string.kaart_niet_geladen, staat.fout.orEmpty())
                    staat.albumy == null -> stringResource(R.string.laden)
                    else -> pluralStringResource(R.plurals.albums_aantal, lista.size, lista.size) + "  ·  " + Synology.nazwaKonta
                },
                etykieta = stringResource(R.string.etyk_kolekcje),
            ) {
                Keuze(selected = false, onClick = { if (Synology.skonfigurowane) staat.laduj() else logowanie = true }, modifier = Modifier.focusRequester(pierwszy),
                    tekst = stringResource(if (Synology.skonfigurowane) R.string.synology_odswiez else R.string.synology_polacz))
            }
        }
        items(lista, key = { it.id }) { a ->
            Column {
                Card(
                    onClick = {
                        nav.open(Scherm.Lijst(a.nazwa, "Synology Photos", true, nav.scope) { p -> Synology.elementy(a.id, p) })
                    },
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
                Text(pluralStringResource(R.plurals.items, a.ile, a.ile), style = MaterialTheme.typography.labelMedium, color = Kleur.zacht)
            }
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { pierwszy.requestFocus() } }
    if (logowanie) SynologyLogowanie { logowanie = false; staat.laduj() }
}

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
                            else -> ctx.getString(R.string.synology_blad, e.message.orEmpty())
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
