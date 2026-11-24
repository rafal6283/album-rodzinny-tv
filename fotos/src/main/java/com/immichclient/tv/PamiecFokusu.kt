package com.immichclient.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Pamięć fokusu ekranów bez własnej siatki (Wspomnienia, Rocznice, Zabawa, Synology, Powroty…): zapamiętuje ostatnio
 * wybrany kafelek i po powrocie (z przeglądarki albo z podstrony) od razu oddaje mu fokus. Bez tego fokus przez chwilę
 * nie miał gdzie trafić i Android oddawał go bocznemu menu (menu się rozwijało, lista „skakała”).
 */
object PamiecFokusu {
    private val ostatni = HashMap<String, String>()
    /** ekran → do kiedy (ms) przywrócić fokus; potem wjazd kafelka w kadr (przewijanie) już go nie „kradnie” */
    private val doPrzywrocenia = HashMap<String, Long>()

    fun zapamietaj(ekran: String, klucz: String) { ostatni[ekran] = klucz }
    fun ostatni(ekran: String): String? = ostatni[ekran]

    /** Ekran właśnie się pojawił: przez chwilę kafelek [ostatni] (albo pierwszy) może przejąć fokus. */
    fun wejscie(ekran: String) { doPrzywrocenia[ekran] = System.currentTimeMillis() + 1500 }

    fun wezPrzywrocenie(ekran: String, klucz: String, pierwszy: Boolean): Boolean {
        val do_ = doPrzywrocenia[ekran] ?: return false
        if (System.currentTimeMillis() > do_) { doPrzywrocenia.remove(ekran); return false }
        val zap = ostatni[ekran]
        val moj = if (zap != null) zap == klucz else pierwszy
        if (moj) doPrzywrocenia.remove(ekran)
        return moj
    }
}

/** Ekran z pamięcią fokusu: wywołać raz na początku ekranu. */
@Composable
fun PamiecEkranu(ekran: String) {
    LaunchedEffect(Unit) { PamiecFokusu.wejscie(ekran) }
}

/**
 * Kafelek z pamięcią fokusu: zapamiętuje się przy wyborze, a po powrocie na ekran dostaje fokus z powrotem.
 * [pierwszy] = kafelek startowy, gdy nic jeszcze nie wybrano.
 */
@Composable
fun Modifier.pamiecFokusu(ekran: String, klucz: String, pierwszy: Boolean = false): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(ekran, klucz) {
        delay(40)
        if (PamiecFokusu.wezPrzywrocenie(ekran, klucz, pierwszy)) {
            repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
        }
    }
    return this.focusRequester(fr).onFocusChanged { if (it.isFocused) PamiecFokusu.zapamietaj(ekran, klucz) }
}
