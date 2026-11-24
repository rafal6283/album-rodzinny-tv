package com.immichclient.tv

import android.content.Context
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * PIN profili domowników (4 cyfry): pytanie przy przełączaniu na profil i przy starcie aplikacji, gdy aktywny jest
 * profil z PIN-em. Profil główny (wspólny) nie ma PIN-u. Zapisany tylko skrót SHA-256 (z nazwą profilu), nie sam PIN.
 */
/** „profil” PIN-u rodzica (tryb dziecięcy): blokuje Ustawienia w profilu dziecka */
const val PIN_RODZICA = "__rodzic__"

object Pin {
    private const val PREFS = "immich"
    private fun klucz(profil: String) = "pin_$profil"
    private fun skrot(profil: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest("$profil:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun ma(c: Context, profil: String?) = profil != null && c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(klucz(profil))
    fun sprawdz(c: Context, profil: String, pin: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(klucz(profil), null) == skrot(profil, pin)
    fun ustaw(c: Context, profil: String, pin: String?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (pin == null) remove(klucz(profil)) else putString(klucz(profil), skrot(profil, pin)) }.apply()
    }
}

/**
 * Wpisywanie PIN-u: cyfry z pilota (0–9) albo z klawiatury na ekranie. [sprawdz] zwraca true = przyjęty;
 * przy nowym PIN-ie ([nowy]) trzeba go wpisać dwa razy. [inne] = dodatkowy przycisk (np. „Profil główny”).
 */
@Composable
fun PinEkran(tytul: String, podtytul: String, nowy: Boolean = false, inne: Pair<String, () -> Unit>? = null, anuluj: (() -> Unit)?, sprawdz: (String) -> Boolean) {
    Lagen.Open()
    var pin by remember { mutableStateOf("") }
    var pierwszy by remember { mutableStateOf<String?>(null) }
    var blad by remember { mutableStateOf<String?>(null) }
    val fr = remember { FocusRequester() }
    BackHandler(enabled = anuluj != null) { anuluj?.invoke() }
    LaunchedEffect(Unit) { delay(40); runCatching { fr.requestFocus() } }
    val zlyPin = stringResource(R.string.pin_zly)
    val rozne = stringResource(R.string.pin_rozne)
    fun cyfra(c: Char) {
        if (pin.length >= 4) return
        pin += c; blad = null
        if (pin.length == 4) {
            val p = pin
            if (nowy && pierwszy == null) { pierwszy = p; pin = "" }
            else if (nowy && pierwszy != p) { blad = rozne; pierwszy = null; pin = "" }
            else if (!sprawdz(p)) { blad = zlyPin; pin = "" }
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xFA09090F)).focusVangen().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when {
                k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { cyfra('0' + (k - KeyEvent.KEYCODE_0)); true }
                k == KeyEvent.KEYCODE_DEL -> { pin = pin.dropLast(1); true }
                else -> false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tytul, style = MaterialTheme.typography.displaySmall)
            Text(if (nowy && pierwszy != null) stringResource(R.string.pin_powtorz) else podtytul, style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    Box(Modifier.size(22.dp).border(2.dp, Color.White, CircleShape).then(if (i < pin.length) Modifier.background(Color.White, CircleShape) else Modifier))
                }
            }
            Text(blad ?: " ", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFCA5A5))
            // klawiatura numeryczna 3×4
            listOf("123", "456", "789").forEachIndexed { ri, r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.forEachIndexed { ci, c -> Knop(c.toString(), null, Modifier.width(84.dp).then(if (ri == 0 && ci == 0) Modifier.focusRequester(fr) else Modifier)) { cyfra(c) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Knop("⌫", null, Modifier.width(84.dp)) { pin = pin.dropLast(1) }
                Knop("0", null, Modifier.width(84.dp)) { cyfra('0') }
                Spacer(Modifier.width(84.dp).height(64.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                inne?.let { (napis, akcja) -> Knop(napis, null, Modifier.width(240.dp)) { akcja() } }
                anuluj?.let { Knop(stringResource(R.string.anuluj), null, Modifier.width(160.dp)) { it() } }
            }
        }
    }
}
