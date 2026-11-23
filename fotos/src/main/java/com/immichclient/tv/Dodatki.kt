package com.immichclient.tv

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Ulubione oznaczane pilotem. Zasoby są niezmienne (Asset), więc zmiany trzymamy tu jako nadpisania — kafelki,
 * przeglądarka i szczegóły czytają [jest]. Zapis do Immich wymaga uprawnienia asset.update.
 */
object Ulubione {
    private val zmiany = mutableStateMapOf<String, Boolean>()

    fun jest(a: Asset): Boolean = zmiany[a.id] ?: a.favoriet

    /** Przełącz; zwraca null = udało się, albo komunikat błędu do pokazania. */
    suspend fun przelacz(a: Asset): Int? {
        val nowe = !jest(a)
        zmiany[a.id] = nowe
        return runCatching { Immich.ustawUlubione(a.id, nowe) }.fold(
            onSuccess = { null },
            onFailure = { e ->
                zmiany.remove(a.id)
                Log.w("Fotos", "ulubione ${a.id}: ${e.message}")
                if (e.message.orEmpty().startsWith("Immich 403")) R.string.ulubione_brak_prawa else R.string.ulubione_blad
            },
        )
    }
}

/** Urodziny osób z Immich (data urodzenia ustawiona w Immich przy osobie): dziś i w najbliższym tygodniu. */
object Urodziny {
    class Wpis(val osoba: Persoon, val zaIle: Int, val lat: Int)

    var lista by mutableStateOf<List<Wpis>>(emptyList())
        private set
    private var pobrano: LocalDate? = null

    /** Po zmianie daty urodzenia w aplikacji: przy następnym laduj() pobierz od nowa. */
    fun uniewaznij() { pobrano = null }

    suspend fun laduj() {
        val dzis = LocalDate.now()
        if (pobrano == dzis) return
        runCatching { Immich.osobyZUrodzinami() }.onSuccess { osoby ->
            lista = osoby.mapNotNull { p ->
                val ur = p.urodziny ?: return@mapNotNull null
                // najbliższa rocznica (29 lutego w zwykłym roku → 28 lutego)
                var r = runCatching { ur.withYear(dzis.year) }.getOrElse { LocalDate.of(dzis.year, 2, 28) }
                if (r.isBefore(dzis)) r = runCatching { ur.withYear(dzis.year + 1) }.getOrElse { LocalDate.of(dzis.year + 1, 2, 28) }
                val za = ChronoUnit.DAYS.between(dzis, r).toInt()
                if (za > 7) null else Wpis(p, za, r.year - ur.year)
            }.sortedBy { it.zaIle }
            pobrano = dzis
        }.onFailure { Log.w("Fotos", "urodziny", it) }
    }
}

/**
 * Profile domowników: każdy profil ma własne konto Immich (klucz + serwer w prefs „immich” jako sleutel_<profil>) i
 * własne ustawienia (Instellingen z przyrostkiem _<profil>). Aktywny profil jest zapamiętany i obowiązuje po restarcie.
 */
object Profile {
    private const val PREFS = "immich"
    const val AKTYWNY = "aktywny_profil"

    /** profil, dla którego trwa parowanie (Dodaj profil); null = konto główne */
    @Volatile var docelowy: String? = null

    fun lista(c: Context): List<String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.keys.filter { it.startsWith("sleutel_") }.map { it.removePrefix("sleutel_") }.sorted()

    fun aktywny(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(AKTYWNY, null)

    fun ustawAktywny(c: Context, nazwa: String?) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (nazwa == null) remove(AKTYWNY) else putString(AKTYWNY, nazwa) }.apply()
    }

    /** Przełącz na profil (null = główny); aktywność trzeba potem odtworzyć (recreate). */
    fun przelacz(c: Context, nazwa: String?) {
        ustawAktywny(c, nazwa)
        Immich.kiesAccount(c, nazwa)
    }

    /** Usuń profil (klucz z tego urządzenia) i wróć do konta głównego. */
    fun usun(c: Context, nazwa: String) {
        Immich.ontkoppel(c, nazwa)
        przelacz(c, null)
    }
}
