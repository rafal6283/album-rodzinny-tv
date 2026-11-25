package com.immichclient.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Serwer zdjęć niedostępny (wyłączony, np. działa tylko wieczorem; albo Tailscale się nie połączył). Co minutę nowa
 * próba; gdy serwer wstanie, aplikacja startuje sama. Przyciski: spróbuj teraz, otwórz Tailscale, kontynuuj mimo to.
 */
@Composable
fun SerwerSpiEkran() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    var proba by remember { mutableStateOf(false) }
    val tailscale = listOf(Immich.serverDom, Immich.serverZdalny).any { it.isNotBlank() && Zdalnie.jestTailscale(it) }
    val zainstalowany = remember { Zdalnie.tailscaleZainstalowany(ctx) }
    LaunchedEffect(Unit) { delay(60); runCatching { fr.requestFocus() } }
    // co minutę sprawdzić, czy serwer już działa
    LaunchedEffect(Unit) { while (true) { delay(60_000); if (!proba && Zdalnie.wybierz(ctx)) break } }
    Box(Modifier.fillMaxSize().background(Kleur.achtergrond), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(720.dp)) {
            Text("🌙", style = MaterialTheme.typography.displayLarge)
            Text(stringResource(R.string.serwer_spi_tytul), style = TytulStyl, color = Color.White, textAlign = TextAlign.Center)
            Text(stringResource(if (tailscale) R.string.serwer_spi_tailscale else R.string.serwer_spi_opis),
                style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, textAlign = TextAlign.Center)
            if (tailscale && !zainstalowany) Text(stringResource(R.string.tailscale_brak), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFCA5A5), textAlign = TextAlign.Center)
            Zdalnie.opis?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Kleur.zacht, textAlign = TextAlign.Center, maxLines = 3) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp).width(420.dp)) {
                Knop(stringResource(if (proba) R.string.laden else R.string.serwer_sprobuj), null, Modifier.focusRequester(fr)) {
                    if (proba) return@Knop
                    proba = true
                    scope.launch { Zdalnie.wybierz(ctx); proba = false }
                }
                if (tailscale && zainstalowany) Knop(stringResource(R.string.tailscale_otworz), null) { Zdalnie.otworz(ctx) }
                Knop(stringResource(R.string.serwer_kontynuuj), null) { Zdalnie.pomin() }
            }
            Text(stringResource(R.string.serwer_spi_auto), style = MaterialTheme.typography.labelMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
