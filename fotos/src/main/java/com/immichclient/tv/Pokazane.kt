package com.immichclient.tv

import android.content.Context

/**
 * Pamięć ostatnio pokazanych zdjęć (Jak rosła, urodziny, Kiedyś i dziś, Tego dnia, quiz…): przy losowaniu zdjęcia,
 * których jeszcze nie było, mają pierwszeństwo, więc kolejne uruchomienia pokazują coś nowego. Ostatnie ~3000 id,
 * zapisane w prefs (przetrwa restart aplikacji).
 */
object Pokazane {
    private const val PREFS = "pokazane"
    private const val MAX = 3000
    private val lista = LinkedHashSet<String>()
    private var wczytane = false

    private fun prefs() = Taal.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized private fun wczytaj() {
        if (wczytane) return
        wczytane = true
        runCatching { prefs().getString("idy", "").orEmpty().split(',').filter { it.isNotBlank() }.forEach { lista.add(it) } }
    }

    @Synchronized fun bylo(id: String): Boolean { wczytaj(); return id in lista }

    @Synchronized fun zapamietaj(ids: Collection<String>) {
        wczytaj()
        for (id in ids) { lista.remove(id); lista.add(id) }
        while (lista.size > MAX) lista.remove(lista.first())
        runCatching { prefs().edit().putString("idy", lista.joinToString(",")).apply() }
    }

    /** [n] losowych elementów, najpierw te jeszcze niepokazane (w losowej kolejności), potem reszta. */
    fun <T> wybierz(z: List<T>, n: Int, id: (T) -> String): List<T> {
        val (nowe, stare) = z.shuffled().partition { !bylo(id(it)) }
        return (nowe + stare).take(n)
    }

    /** Lista zdjęć z nowymi na początku (kolejność losowa w obu grupach). */
    fun nowePierwsze(z: List<Asset>): List<Asset> = wybierz(z, z.size) { it.id }
}
