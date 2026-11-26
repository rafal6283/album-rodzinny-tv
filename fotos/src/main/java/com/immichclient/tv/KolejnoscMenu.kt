package com.immichclient.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Kolejność sekcji w bocznym menu (Ustawienia → Wygląd → Kolejność menu). Zapisana jako lista nazw; sekcje, których
 * zapis nie zna (np. nowe w aktualizacji), dochodzą na końcu w domyślnej kolejności. Ustawienia zawsze na dole.
 */
/** Ukryte sekcje (Ustawienia nigdy). */
fun Sectie.Companion.ukryte(): Set<Sectie> = Instellingen.ukryteSekcje.split(',').mapNotNull { n -> Sectie.entries.firstOrNull { it.name == n.trim() } }
    .filter { it != Sectie.INSTELLINGEN }.toSet()

fun Sectie.Companion.ukryj(s: Sectie, tak: Boolean) {
    if (s == Sectie.INSTELLINGEN) return
    val u = ukryte().toMutableSet().apply { if (tak) add(s) else remove(s) }
    Instellingen.ukryteSekcje = u.joinToString(",") { it.name }
}

/** Przesuń sekcję w kolejności menu o [d] (−1 wyżej, +1 niżej), licząc także ukryte. */
fun Sectie.Companion.przesun(s: Sectie, d: Int) {
    val l = wszystkieWKolejnosci().toMutableList()
    val i = l.indexOf(s); val j = i + d
    if (i < 0 || j !in l.indices) return
    l.removeAt(i); l.add(j, s)
    Instellingen.sekcje = l.joinToString(",") { it.name }
}

/** Kolejność wszystkich sekcji (z ukrytymi), bez Ustawień. */
fun Sectie.Companion.wszystkieWKolejnosci(): List<Sectie> {
    val zapis = Instellingen.sekcje.split(',').mapNotNull { n -> Sectie.entries.firstOrNull { it.name == n.trim() } }
        .filter { it != Sectie.INSTELLINGEN }.distinct()
    return (zapis + Sectie.KOLEJNOSC.filter { it != Sectie.INSTELLINGEN && it !in zapis })
        .filter { it != Sectie.SYNOLOGY || Synology.skonfigurowane }
        .filter { it != Sectie.PORZADKI || !Instellingen.trybDzieciecy }
}

fun Sectie.Companion.menu(): List<Sectie> {
    val zapis = Instellingen.sekcje.split(',').mapNotNull { n -> Sectie.entries.firstOrNull { it.name == n.trim() } }
        .filter { it != Sectie.INSTELLINGEN }.distinct()
    // Synology Photos w menu dopiero po połączeniu (Ustawienia → Synology Photos)
    @Suppress("UNUSED_VARIABLE") val zmiana = Synology.stan // odczyt stanu: menu odświeża się po połączeniu
    return (zapis + Sectie.KOLEJNOSC.filter { it != Sectie.INSTELLINGEN && it !in zapis })
        .filter { it != Sectie.SYNOLOGY || Synology.skonfigurowane }
        .filter { it != Sectie.PORZADKI || !Instellingen.trybDzieciecy }
        .filter { it !in ukryte() } + Sectie.INSTELLINGEN
}

@Composable
fun nazwaSekcji(s: Sectie): String = stringResource(if (s == Sectie.FOTOS) Instellingen.uiterlijk.label else s.label)

/** Ekran układania menu: OK chwyta / upuszcza sekcję, ▲▼ przesuwają chwyconą, Wstecz zamyka (zmiany zapisują się od razu). */
@Composable
fun KolejnoscSekcji(sluit: () -> Unit) {
    Lagen.Open()
    val lista = remember { Sectie.wszystkieWKolejnosci().toMutableStateList() }
    var ukryte by remember { mutableStateOf(Sectie.ukryte()) }
    var trzymany by remember { mutableStateOf<Sectie?>(null) }
    val fr = remember { Sectie.entries.associateWith { FocusRequester() } }
    val scope = rememberCoroutineScope()
    BackHandler { if (trzymany != null) trzymany = null else sluit() }
    LaunchedEffect(Unit) { delay(40); runCatching { fr.getValue(lista.first()).requestFocus() } }

    fun zapisz() { Instellingen.sekcje = lista.joinToString(",") { it.name } }
    fun przesun(s: Sectie, d: Int) {
        val i = lista.indexOf(s); val j = i + d
        if (i < 0 || j !in lista.indices) return
        lista.removeAt(i); lista.add(j, s); zapisz()
        scope.launch { delay(16); runCatching { fr.getValue(s).requestFocus() } }
    }

    Box(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).focusVangen().padding(horizontal = 64.dp, vertical = 30.dp)) {
        Column {
            Text(stringResource(R.string.menu_kolejnosc), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.menu_kolejnosc_hint), style = MaterialTheme.typography.bodyMedium, color = Kleur.zacht, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).width(560.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lista.forEachIndexed { i, s ->
                    key(s) {
                        val chwycony = trzymany == s
                        var focus by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { trzymany = if (chwycony) null else s },
                            modifier = Modifier.fillMaxWidth().height(48.dp).focusRequester(fr.getValue(s))
                                .onFocusChanged { focus = it.isFocused }
                                .onPreviewKeyEvent { e ->
                                    // ▶ na niechwyconej sekcji: ukryj / pokaż w menu
                                    if (!chwycony && e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                        Sectie.ukryj(s, s !in ukryte); ukryte = Sectie.ukryte(); return@onPreviewKeyEvent true
                                    }
                                    if (!chwycony || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key.nativeKeyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> { przesun(s, -1); true }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> { przesun(s, 1); true }
                                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                                        else -> false
                                    }
                                }
                                .then(if (chwycony) Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp)) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = if (chwycony) 1.03f else 1f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
                        ) {
                            val kolor = if (focus) Kleur.opPrimair else Kleur.tekst
                            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, color = kolor.copy(alpha = 0.7f), modifier = Modifier.width(36.dp))
                                Icon(s.icoon, null, Modifier.size(22.dp), tint = kolor)
                                Text(nazwaSekcji(s), style = MaterialTheme.typography.titleMedium, color = if (s in ukryte) kolor.copy(alpha = 0.45f) else kolor, modifier = Modifier.padding(start = 14.dp).weight(1f))
                                if (s in ukryte) Icon(Icons.Filled.VisibilityOff, null, Modifier.padding(end = 8.dp).size(20.dp), tint = kolor.copy(alpha = 0.7f))
                                if (chwycony) Icon(Icons.Filled.SwapVert, null, Modifier.size(22.dp), tint = kolor)
                            }
                        }
                    }
                }
                // przywróć domyślną kolejność
                var focus by remember { mutableStateOf(false) }
                Surface(
                    onClick = {
                        trzymany = null
                        lista.clear(); lista.addAll(Sectie.KOLEJNOSC.filter { it != Sectie.INSTELLINGEN })
                        Instellingen.sekcje = ""; Instellingen.ukryteSekcje = ""; ukryte = emptySet()
                    },
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(48.dp).onFocusChanged { focus = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Kleur.vlak, focusedContainerColor = Kleur.primair, focusedContentColor = Kleur.opPrimair),
                ) {
                    val kolor = if (focus) Kleur.opPrimair else Kleur.tekst
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Restore, null, Modifier.padding(start = 36.dp).size(22.dp), tint = kolor)
                        Text(stringResource(R.string.menu_domyslna), style = MaterialTheme.typography.titleMedium, color = kolor, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }
    }
}
