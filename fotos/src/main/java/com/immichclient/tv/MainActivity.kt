package com.immichclient.tv

import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        Taal.start(this)
        Immich.kiesAccount(this, null)
        Diagnose.start(this)
    }

    /**
     * Eén ImageLoader voor alles: OkHttp mét sleutel, 400 MB op schijf (thumbnails ~15 kB, previews ~300 kB; een
     * tv-box heeft vaak maar een paar GB vrij, dus niet groter). Geheugencache bewust klein en vast: een box met 2 GB
     * ruimt bij geheugennood ook de app vooraan op (gezien tijdens een video). Bitmaps zijn hardware-bitmaps
     * (GPU-geheugen, telt mee in "Graphics" van meminfo); met 35 % van de grote heap stond er 220 MB aan previews in de cache.
     */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Synology.polaczenia })) }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(CACHE_MB * 1024L * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("beelden").toOkioPath()).maxSizeBytes(400L * 1024 * 1024).build() }
        .build()

    // Geheugennood of naar de achtergrond: cache leeg (thumbnails komen in ms terug van schijf).
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            android.util.Log.i("Fotos", "onTrimMemory $level: geheugencache leeg")
            SingletonImageLoader.get(this).memoryCache?.clear()
            openKaart?.onLowMemory() // MapLibre: tegelcache in het geheugen leeg
            if (level >= TRIM_MEMORY_RUNNING_CRITICAL) ThumbHash.leeg()
        }
    }

    /** Alles vrijgeven wat zonder schade weg kan (beeldcache, thumbhashes, MapLibre-tegels), bv. als de screensaver start. */
    fun ontlast(waarom: String) {
        android.util.Log.i("Fotos", "ontlasten ($waarom)")
        SingletonImageLoader.get(this).memoryCache?.clear()
        ThumbHash.leeg()
        openKaart?.onLowMemory()
    }

    companion object { const val CACHE_MB = 56 }
}

/** Een scherm met zijn eigen staat; blijft bewaard zolang het op de stapel staat (terug = zelfde plek, zelfde focus). */
sealed class Scherm {
    class Tijdlijn(
        titel: String, val sub: String?, filter: Filter, nieuwsteEerst: Boolean, val metHerinneringen: Boolean, scope: CoroutineScope,
    ) : Scherm() {
        val staat = TijdlijnStaat(filter, nieuwsteEerst, scope)
        /** zmienny: nowe imię osoby / nowa nazwa albumu nadane z telewizora */
        var titel by mutableStateOf(titel)
    }
    class Albums(scope: CoroutineScope) : Scherm() { val staat = AlbumsStaat(scope) }
    class Verkennen(scope: CoroutineScope) : Scherm() { val staat = VerkenStaat(scope) }
    class Zoeken(scope: CoroutineScope) : Scherm() { val staat = ZoekStaat(scope) }
    class Herinneringen(scope: CoroutineScope) : Scherm() { val staat = HerinneringStaat(scope) }
    class Kaart(scope: CoroutineScope) : Scherm() { val staat = KaartStaat(scope) }
    /** alle foto's van een kaartcluster (lang OK op de kaart) */
    class KaartFotos(val titel: String, ids: List<String>, scope: CoroutineScope) : Scherm() { val staat = KaartFotosStaat(ids, scope) }
    /** alle personen (vanuit Zoeken), met zoekveld en sortering */
    class Personen(val staat: PersonenStaat) : Scherm()
    /** een map uit de mappenboom (null = het startpunt van de boom, zodra die geladen is) */
    class Mappen(boom: MappenBoom, knoop: MapKnoop?, scope: CoroutineScope) : Scherm() { val staat = MapStaat(boom, knoop, scope) }
    data object Instellingen : Scherm()
    data object Rocznice : Scherm()
    data object Zabawa : Scherm()
    class Quiz(scope: CoroutineScope) : Scherm() { val staat = QuizStaat(scope) }
    data object Impreza : Scherm()
    class Podroz(albumId: String, nazwa: String, scope: CoroutineScope) : Scherm() { val staat = PodrozStaat(albumId, nazwa, scope) }
    class Rosniecie(osobaId: String, imie: String, scope: CoroutineScope) : Scherm() { val staat = RosniecieStaat(osobaId, imie, scope) }
    class Podobni(osobaId: String, imie: String, scope: CoroutineScope) : Scherm() { val staat = PodobniStaat(osobaId, imie, scope) }
    class Kadr(scope: CoroutineScope) : Scherm() { val staat = KadrStaat(scope) }
    class Powroty(scope: CoroutineScope) : Scherm() { val staat = PowrotyStaat(scope) }
    @Suppress("UNUSED_PARAMETER") class Kolory(scope: CoroutineScope) : Scherm()
    class Synology(scope: CoroutineScope) : Scherm() { val staat = SynologyStaat(scope) }
    /** Rok w skrócie (ze Wspomnień) */
    class Rok(rok: Int, scope: CoroutineScope, osobaId: String? = null, imie: String? = null) : Scherm() { val staat = RokStaat(scope, rok, osobaId, imie) }
    /** sekcja Filmy: kolejność, przejście do daty, losowo */
    class Filmy(scope: CoroutineScope) : Scherm() { val staat = FilmyStaat(scope) }
    class Lijst(val titel: String, val sub: String?, val perDag: Boolean, scope: CoroutineScope, val losowyPokaz: Boolean = false, laad: suspend (Int) -> Pagina) : Scherm() {
        val res = Resultaten(scope).also { it.start(laad) }
    }
}

class Nav(
    val scope: CoroutineScope,
    val open: (Scherm) -> Unit,
    private val toon: (bron: BeeldBron, index: Int, titel: String?, diashow: Boolean, raster: RasterStaat?, naTerug: (() -> Unit)?) -> Unit,
    /** bieżący ekran podrzędny zamknąć (np. oś czasu osoby po połączeniu jej z inną) */
    val terug: () -> Unit = {},
) {
    /** Viewer openen; [raster] krijgt de focus terug, of [naTerug] wordt aangeroepen bij het sluiten (kaart). */
    fun bekijk(bron: BeeldBron, index: Int, titel: String?, diashow: Boolean, raster: RasterStaat?, naTerug: (() -> Unit)? = null) =
        toon(bron, index, titel, diashow, raster, naTerug)
}

/** Kliknięty kafelek kanału z ekranu głównego: (kanał, id zdjęcia lub null = sam kanał). */
object WejscieKanal {
    var zadanie by mutableStateOf<Pair<String, String?>?>(null)
    fun z(intent: android.content.Intent?) {
        val k = intent?.getStringExtra(Kanaly.EXTRA_KANAL) ?: return
        zadanie = k to intent.getStringExtra(Kanaly.EXTRA_ZASOB)
    }
}

/** Wyszukiwanie z systemu/Asystenta (ACTION_SEARCH, „szukaj … w Album Rodzinny”): tekst do sekcji Szukaj. */
object WejscieSzukaj {
    var zapytanie by mutableStateOf<String?>(null)
    fun z(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEARCH) return
        zapytanie = intent.getStringExtra(android.app.SearchManager.QUERY)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** Signalen van toetsen die de activity afvangt (Terug lang ingedrukt). */
object Toetsen {
    var langeTerug by androidx.compose.runtime.mutableIntStateOf(0)
    /** geopend via het tandwieltje van de screensaver (alias ScreensaverInstellingen): naar die groep scrollen */
    @Volatile var naarScreensaver = false
    /** de wereldkaart gebruikt OK ingedrukt houden (lang OK); overal elders is een OK-herhaling nooit een nieuwe actie */
    @Volatile var okHerhaling = false
    /** een scherm dat door OK (lang) geopend is, krijgt de rest van diezelfde druk (herhalingen + key-up) niet meer */
    @Volatile var okGeslikt = false
}

enum class Sectie(@StringRes val label: Int, val icoon: ImageVector) {
    FOTOS(R.string.sectie_fotos, Icons.Filled.Photo),
    HERINNERINGEN(R.string.sectie_herinneringen, Icons.Filled.AutoAwesome),
    /** albumy z Synology Photos (widoczne po połączeniu w Ustawieniach) */
    SYNOLOGY(R.string.sectie_synology, Icons.Filled.Storage),
    /** quiz, tryb imprezy, film z podróży, jak rosła…, powroty, szukaj po kolorze */
    ZABAWA(R.string.sectie_zabawa, Icons.Filled.Celebration),
    /** urodziny i rocznice albumów: nadchodzące i niedawne */
    ROCZNICE(R.string.sectie_rocznice, Icons.Filled.Cake),
    ZOEKEN(R.string.sectie_zoeken, Icons.Filled.Search),
    VERKENNEN(R.string.sectie_verkennen, Icons.Filled.Explore),
    KAART(R.string.sectie_kaart, Icons.Filled.Map),
    ALBUMS(R.string.sectie_albums, Icons.Filled.PhotoLibrary),
    MAPPEN(R.string.sectie_mappen, Icons.Filled.Folder),
    FAVORIETEN(R.string.sectie_favorieten, Icons.Filled.Favorite),
    /** wszystkie rozpoznane twarze, także bez imienia */
    LUDZIE(R.string.personen, Icons.Filled.People),
    /** same filmy z osi czasu, od najnowszych */
    FILMY(R.string.sectie_filmy, Icons.Filled.VideoLibrary),
    INSTELLINGEN(R.string.sectie_instellingen, Icons.Filled.Settings);

    companion object {
        /** Kolejność w bocznej szynie, jak w Immichin: szukaj, zdjęcia+wideo, wspomnienia, ulubione, albumy, ludzie, mapa… */
        val KOLEJNOSC = listOf(ZOEKEN, FOTOS, FILMY, HERINNERINGEN, ROCZNICE, ZABAWA, FAVORIETEN, ALBUMS, SYNOLOGY, LUDZIE, VERKENNEN, KAART, MAPPEN, INSTELLINGEN)
    }
}

class MainActivity : ComponentActivity() {
    /** OK/Enter pas doorlaten na een eigen KeyDown: een losse key-up/repeat van een launcher-tegel mag hier niets "klikken" */
    private var okGezien = false
    private var start = 0L

    /** De gekozen taal, los van de systeemtaal (zie Taal). */
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Taal.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        start = android.os.SystemClock.uptimeMillis()
        // een eigen launcher kan het profiel meegeven ("kijker"); per profiel kan dan een eigen Immich-account gelden
        Immich.kiesAccount(this, intent.getStringExtra("kijker"))
        Synology.wczytaj(this)
        if (savedInstanceState == null) { WejscieKanal.z(intent); WejscieSzukaj.z(intent) }
        // Test-extra's alleen bij een verse start vanaf de shell (`am start`, referrer com.android.shell). Bij een herstart
        // van de taak (proces opgeruimd door geheugennood, daarna de tegel in de launcher) geeft Android het óórspronkelijke
        // intent van de taak opnieuw mee (savedInstanceState != null) en zou `--ei test_index` blijven plakken: de app
        // opende dan "uit het niets" in de viewer.
        val vanShell = referrer?.host == "com.android.shell"
        val test = savedInstanceState == null && vanShell
        Log.i("Fotos", "start: referrer=$referrer hersteld=${savedInstanceState != null} test=$test extras=${intent.extras?.keySet()?.joinToString()}")
        // test: `am start … --ei test_index 91` opent de viewer meteen op die foto in de tijdlijn
        val testIndex = if (test) intent.getIntExtra("test_index", -1) else -1
        // test: `--es sectie KAART` opent meteen die sectie; anders het ingestelde startscherm
        // via het tandwieltje bij de screensaver in de systeeminstellingen (alias .ScreensaverInstellingen, zie dream.xml)
        val vanScreensaver = intent.component?.className?.endsWith(".ScreensaverInstellingen") == true
        if (vanScreensaver) Toetsen.naarScreensaver = true
        val startSectie = intent.getStringExtra("sectie")?.takeIf { test }?.let { s -> Sectie.entries.firstOrNull { it.name == s } }
            ?: Taal.herstartSectie?.also { Taal.herstartSectie = null } // na een taalwissel terug in Instellingen
            ?: Sectie.INSTELLINGEN.takeIf { vanScreensaver }
            ?: Instellingen.start
        // test: `--ez instel_test true` toont het instelscherm zonder de bestaande koppeling te wissen
        val instelTest = test && intent.getBooleanExtra("instel_test", false)
        // test: `--ez taal_test true` toont het taalkeuzescherm van de eerste start (de koppeling blijft staan)
        val taalTest = test && intent.getBooleanExtra("taal_test", false)
        if (test) {
            kaartTexture = intent.getBooleanExtra("kaart_texture", false); kaartLod = intent.getIntExtra("kaart_lod", kaartLod)
            kaartFps = intent.getIntExtra("kaart_fps", 0); kaartAlles = intent.getBooleanExtra("kaart_alles", false)
            kaartRes = intent.getIntExtra("kaart_res", kaartRes); kaartVlucht = intent.getIntExtra("kaart_vlucht", kaartVlucht)
            scrolBalkUit = intent.getBooleanExtra("geen_balk", false)
            videoZonderPlaceholder = intent.getBooleanExtra("video_zonder_placeholder", false)
            kaartCache = intent.getBooleanExtra("kaart_cache", kaartCache); kaartPrefetch = intent.getIntExtra("kaart_prefetch", -1)
            // `--es update_test 2.0`: doen alsof die versie geïnstalleerd is (updatecheck/-venster testen)
            Update.testVersie = intent.getStringExtra("update_test")
        }
        setContent { FotosThema { FotosApp(finish = ::finish, herstart = ::recreate, testIndex = testIndex, startSectie = startSectie, instelTest = instelTest, taalTest = taalTest) } }
    }

    /** Terug lang ingedrukt = menu (lade); de key-up erna mag dan geen gewone Terug meer zijn */
    private var terugLang = false

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Terug lang ingedrukt: overal het menu. KEYCODE_MENU onderscheppen sommige boxen (NVIDIA Shield) op systeemniveau,
        // daarom deze toets; MENU blijft ernaast voor afstandsbedieningen die hem wél doorgeven (zie FotosApp).
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0) {
                if (!terugLang) { terugLang = true; Toetsen.langeTerug++ }
                return true
            }
            if (e.action == KeyEvent.ACTION_UP && terugLang) { terugLang = false; return true }
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER) {
            // eerste halve seconde na het starten helemaal geen OK (ook geen verse: dubbele druk op de tegel)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && android.os.SystemClock.uptimeMillis() - start > 500) okGezien = true
            if (Toetsen.okGeslikt) {
                Log.i("Fotos", "OK ingeslikt (actie ${e.action}, herhaling ${e.repeatCount})")
                if (e.action == KeyEvent.ACTION_UP) Toetsen.okGeslikt = false
                return true
            }
            if (!okGezien) { Log.i("Fotos", "OK genegeerd (actie ${e.action}, herhaling ${e.repeatCount})"); return true }
            // ingedrukt houden = key-repeat: geen tweede, derde, ... actie (viewer wisselde zo razendsnel de diashow)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && !Toetsen.okHerhaling) return true
        }
        return super.dispatchKeyEvent(e)
    }

    /** aplikacja schodzi z ekranu: udostępnianie gościom się kończy */
    override fun onStop() {
        super.onStop()
        Udostepnianie.zatrzymaj()
    }

    // singleTask: draait hij al en komt er een ander profiel binnen met een eigen account, dan opnieuw opbouwen
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        okGezien = false
        WejscieKanal.z(intent); WejscieSzukaj.z(intent)
        val oud = Immich.sleutel
        Immich.kiesAccount(this, intent.getStringExtra("kijker"))
        if (Immich.sleutel != oud) recreate()
    }
}

@Composable
private fun FotosApp(finish: () -> Unit, herstart: () -> Unit, testIndex: Int = -1, startSectie: Sectie = Sectie.FOTOS, instelTest: Boolean = false, taalTest: Boolean = false) {
    // Eerste start: eerst de taal kiezen (voorgeselecteerd op de systeemtaal); daarna opnieuw opbouwen in die taal
    var taalKiezen by remember { mutableStateOf(taalTest || !Taal.gekozen) }
    if (taalKiezen) { TaalScherm(finish = finish) { taalKiezen = false; herstart() }; return }
    // Geen account (eerste start, of na Ontkoppelen) of "Opnieuw koppelen": instelscherm met QR-code. Na een geslaagde
    // koppeling de activity opnieuw opbouwen: alle schermen hangen aan het oude adres/account.
    val stand = Immich.stand
    var instellen by remember { mutableStateOf(instelTest) }
    val gekoppeld = Immich.sleutel.isNotBlank() && stand >= 0
    if (!gekoppeld || instellen) {
        // via Instellingen → Opnieuw koppelen is het nooit een test (sleutelnaam zonder "(test)")
        InstelScherm(test = instelTest && !instellen, terug = if (gekoppeld) ({ instellen = false }) else null, klaar = { instellen = false; herstart() }, finish = finish)
        return
    }
    // profil domownika z PIN-em: przy starcie najpierw PIN (albo przejście na wspólny profil główny)
    val ctxPin = androidx.compose.ui.platform.LocalContext.current
    var zablokowane by remember { mutableStateOf(Pin.ma(ctxPin, Immich.kijker)) }
    if (zablokowane) {
        val profil = Immich.kijker.orEmpty()
        PinEkran(
            stringResource(R.string.pin_tytul, profil), stringResource(R.string.pin_wpisz),
            inne = stringResource(R.string.pin_na_glowny) to { Profile.przelacz(ctxPin, null); zablokowane = false; herstart() },
            anuluj = null,
        ) { pin -> Pin.sprawdz(ctxPin, profil, pin).also { if (it) zablokowane = false } }
        return
    }
    // Eén scope voor alle schermstaten, met een CoroutineExceptionHandler: een onverwachte fout in een coroutine (de
    // meeste zijn al met runCatching afgedekt) sluit dan niet de hele app, maar komt in de diagnose terecht.
    val scope = remember {
        CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            Log.e("Fotos", "coroutine", e); Diagnose.noteer("coroutine", e)
        })
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { scope.cancel() } }
    androidx.compose.runtime.LaunchedEffect(Unit) { Immich.warmZoeken() }
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // rechten van de sleutel controleren; een nieuw ontbrekend recht één keer melden (overzicht met Opnieuw koppelen)
    androidx.compose.runtime.LaunchedEffect(Immich.stand) { kotlinx.coroutines.delay(1500); Rechten.controleer(); Rechten.meldNieuw(appCtx) }
    // na een update één keer "Wat is er nieuw"; daarna (als dat aanstaat) kijken of er een nieuwere release is
    androidx.compose.runtime.LaunchedEffect(Unit) { Update.bijStart(appCtx); kotlinx.coroutines.delay(4000); if (Instellingen.updateCheck) Update.controleer(appCtx, melden = true) }
    val wortels = remember { HashMap<Sectie, Scherm>() }
    var sectie by remember { mutableStateOf(startSectie) }
    val stapel = remember { mutableStateListOf<Scherm>() }
    var kijker by remember { mutableStateOf<Triple<KijkerStaat, RasterStaat?, (() -> Unit)?>?>(null) }
    /** overzicht van de sneltoetsen (INFO-toets of via Instellingen) */
    var toetsen by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val naamApp = stringResource(Instellingen.uiterlijk.label)
    // instellingen die een bestaande tijdlijn raken (volgorde, partner-foto's): toepassen zonder herstart
    val volgorde = Instellingen.nieuwsteEerst; val partners = Instellingen.partners
    androidx.compose.runtime.LaunchedEffect(volgorde, partners) {
        for (s in listOf(Sectie.FOTOS, Sectie.FAVORIETEN)) (wortels[s] as? Scherm.Tijdlijn)?.staat?.pasInstellingenToe()
    }

    val nav = remember {
        Nav(
            scope = scope,
            open = { stapel.add(it) },
            toon = { bron, i, titel, dia, raster, naTerug -> if (i >= 0) kijker = Triple(KijkerStaat(bron, i, titel, dia), raster, naTerug) },
            terug = { if (stapel.isNotEmpty()) stapel.removeAt(stapel.lastIndex) },
        )
    }
    fun wortel(s: Sectie): Scherm = wortels.getOrPut(s) {
        when (s) {
            Sectie.FOTOS -> Scherm.Tijdlijn(naamApp, null, Filter(), Instellingen.nieuwsteEerst, true, scope)
            Sectie.HERINNERINGEN -> Scherm.Herinneringen(scope)
            Sectie.ROCZNICE -> Scherm.Rocznice
            Sectie.ZABAWA -> Scherm.Zabawa
            Sectie.SYNOLOGY -> Scherm.Synology(scope)
            Sectie.ZOEKEN -> Scherm.Zoeken(scope)
            Sectie.VERKENNEN -> Scherm.Verkennen(scope)
            Sectie.KAART -> Scherm.Kaart(scope)
            Sectie.LUDZIE -> Scherm.Personen(PersonenStaat(emptyList(), scope) { Immich.wszystkieOsoby() })
            Sectie.ALBUMS -> Scherm.Albums(scope)
            Sectie.MAPPEN -> Scherm.Mappen(MappenBoom(scope), null, scope)
            Sectie.FILMY -> Scherm.Filmy(scope)
            Sectie.FAVORIETEN -> Scherm.Tijdlijn(ctx.getString(R.string.sectie_favorieten), null, Filter(favorieten = true), Instellingen.nieuwsteEerst, false, scope)
            Sectie.INSTELLINGEN -> Scherm.Instellingen
        }
    }
    val scherm = stapel.lastOrNull() ?: wortel(sectie)
    // kanały na ekranie głównym: odśwież przy starcie (i zaplanuj okresowo); po zmianie ustawienia od razu
    val kanalyWl = Instellingen.kanaly
    androidx.compose.runtime.LaunchedEffect(kanalyWl) { kotlinx.coroutines.delay(2500); Kanaly.odswiezWTle(appCtx) }
    val zSzukania = WejscieSzukaj.zapytanie
    androidx.compose.runtime.LaunchedEffect(zSzukania) {
        val q = zSzukania ?: return@LaunchedEffect
        kijker = null; stapel.clear(); ZabawaPamiec.wybor = null; sectie = Sectie.ZOEKEN
        (wortel(Sectie.ZOEKEN) as Scherm.Zoeken).staat.let { z -> z.tekst = q; z.zoek(appCtx) }
        WejscieSzukaj.zapytanie = null
    }
    // kafelek kanału kliknięty: przeglądarka na tym zdjęciu, ◀ ▶ przegląda resztę kanału
    val zKanalu = WejscieKanal.zadanie
    androidx.compose.runtime.LaunchedEffect(zKanalu) {
        val (kanal, id) = zKanalu ?: return@LaunchedEffect
        // żądanie zerujemy dopiero na końcu: zmiana klucza w trakcie anulowałaby ten efekt przed wczytaniem zdjęć
        val wynik = runCatching { Kanaly.zasoby(appCtx, kanal) }.onFailure { Log.w("Fotos", "kanał $kanal", it) }.getOrNull()
        if (wynik != null && wynik.second.isNotEmpty()) {
            val (tytul, lista) = wynik
            val i = lista.indexOfFirst { it.id == id }.coerceAtLeast(0)
            kijker = null; stapel.clear(); ZabawaPamiec.wybor = null
            nav.bekijk(LijstBron(lista), i, tytul, id == null, null)
        }
        WejscieKanal.zadanie = null
    }
    // „Tego dnia” jako pokaz na start (Ustawienia → Oś czasu): raz na uruchomienie, tylko gdy są zdjęcia z tego dnia
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // urodziny dziś: pokaz solenizanta (raz dziennie) ma pierwszeństwo przed „Tego dnia”
        if (!UrodzinyStart.pokazano && testIndex < 0 && WejscieKanal.zadanie == null && Instellingen.urodzinyStart) {
            UrodzinyStart.pokazano = true
            val u = UrodzinyStart.dzis(ctx)
            if (u != null && kijker == null) { TegoDniaStart.pokazano = true; nav.bekijk(LijstBron(u.second), 0, u.first, true, null); return@LaunchedEffect }
        }
        if (!Instellingen.tegoDniaStart || TegoDniaStart.pokazano || testIndex >= 0 || WejscieKanal.zadanie != null) return@LaunchedEffect
        TegoDniaStart.pokazano = true
        val lista = TegoDniaStart.zasoby()
        if (lista.isNotEmpty() && kijker == null) nav.bekijk(LijstBron(lista), 0, ctx.getString(R.string.tego_dnia), true, null)
    }
    if (testIndex >= 0) androidx.compose.runtime.LaunchedEffect(Unit) {
        val s = (wortel(Sectie.FOTOS) as Scherm.Tijdlijn).staat
        while (s.aantal == 0) kotlinx.coroutines.delay(100)
        kotlinx.coroutines.delay(600)
        nav.bekijk(s, testIndex, naamApp, false, s.raster)
    }

    // Terug: toetsenoverzicht dicht → sub-scherm sluiten → naar het startscherm → in Foto's naar boven → app uit (terug naar de launcher)

    val ladeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    /** inhoud (rechts van de lade): na het sluiten van een popup de focus hierheen, anders pakt Android de lade */
    val inhoudFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val inhoudHeeftFocus = remember { androidx.compose.runtime.mutableStateOf(false) }
    // menu openen = focus op het lade-item van de huidige sectie (de lade van tv-material opent op focus)
    fun openLade() { toetsen = false; runCatching { ladeFocus.requestFocus() }.onFailure { Log.w("Fotos", "lade", it) } }
    // Wstecz w sekcji (na jej głównym ekranie) otwiera boczne menu na tej sekcji — nie przeskakuje do „Album Rodzinny”;
    // dopiero Wstecz w menu: do sekcji startowej, a z niej wyjście z aplikacji
    val ladeMaFocus = remember { androidx.compose.runtime.mutableStateOf(false) }
    BackHandler(enabled = kijker == null) {
        val t = (scherm as? Scherm.Tijdlijn)?.staat?.raster?.lijst
        when {
            toetsen -> toetsen = false
            stapel.isNotEmpty() -> stapel.removeAt(stapel.lastIndex)
            ladeMaFocus.value -> if (sectie != startSectie) sectie = startSectie else finish()
            sectie == startSectie && t != null && t.firstVisibleItemIndex > 0 -> scope.launch {
                t.scrollToItem(0)
                (scherm as Scherm.Tijdlijn).staat.let { s -> s.maanden.firstOrNull()?.let { m -> s.geladen[m.sleutel]?.firstOrNull()?.let { s.raster.focusDoel = it.id } } }
            }
            else -> openLade()
        }
    }
    val langeTerug = Toetsen.langeTerug
    androidx.compose.runtime.LaunchedEffect(langeTerug) { if (langeTerug > 0 && kijker == null) openLade() }
    Box(
        Modifier.fillMaxSize().background(Kleur.achtergrond).onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || kijker != null) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // MENU (≡ op sommige afstandsbedieningen): overal de lade openen, met de focus op de huidige sectie
                KeyEvent.KEYCODE_MENU -> { openLade(); true }
                // INFO: overzicht van de sneltoetsen (in de viewer is INFO het infopaneel)
                KeyEvent.KEYCODE_INFO -> { toetsen = !toetsen; true }
                else -> false
            }
        },
    ) {
        // tło z poświatą w odcieniu bieżącej sekcji (jak w Immichin)
        Tlo(odcienSekcji(sectie))
        val lade = rememberDrawerState(DrawerValue.Closed)
        val ladeInstap = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        // een frame later: tv-material zet zelf nog een focus bij het binnengaan
        androidx.compose.runtime.LaunchedEffect(ladeInstap.intValue) {
            if (ladeInstap.intValue == 0) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
            val ok = runCatching { ladeFocus.requestFocus() }.getOrDefault(false)
            Log.i("Fotos", "lade: focus naar huidige sectie ($ok)")
        }
        val ladeBinnen = remember { androidx.compose.runtime.mutableStateOf(false) }
        val ladeFocusNu = ladeMaFocus
        // Omleiden alleen bij het BINNENGAAN. Bij een focusverplaatsing binnen de lade kan hasFocus kort false->true
        // knipperen; daarom telt "buiten" pas na 300 ms zonder focus (anders sprong ▼ steeds terug naar de huidige sectie).
        androidx.compose.runtime.LaunchedEffect(ladeFocusNu.value) {
            if (ladeFocusNu.value) {
                if (!ladeBinnen.value) { ladeBinnen.value = true; ladeInstap.intValue++ }
            } else {
                kotlinx.coroutines.delay(300)
                ladeBinnen.value = false
            }
        }
        // Schermwissel (ook viewer open/dicht): even heeft niets focus en zou Android die aan het eerste focusbare
        // element geven = de lade (klapt kort open). Daarom is de lade tijdens de wissel niet focusbaar; het nieuwe
        // scherm pakt zijn focus zelf. De eerste compositie met een nieuw scherm telt al mee (`nieuw`), vóór de effect.
        // popups (nieuw in deze versie, update, rechten) tellen ook als wissel: sluiten ze, dan gaat de focus terug naar de inhoud
        val popup = Update.nieuwsOpen || Update.open || Rechten.open || Lagen.open > 0
        val wisselSleutel = Triple(scherm, kijker != null, popup)
        val laatsteSleutel = remember { androidx.compose.runtime.mutableStateOf(wisselSleutel) }
        val wisselt = remember { androidx.compose.runtime.mutableStateOf(false) }
        val ladeDicht = wisselSleutel != laatsteSleutel.value || wisselt.value
        androidx.compose.runtime.LaunchedEffect(wisselSleutel) {
            if (wisselSleutel == laatsteSleutel.value) return@LaunchedEffect
            wisselt.value = true
            val popupDicht = laatsteSleutel.value.third && !popup && laatsteSleutel.value.first == scherm
            val kijkerByl = laatsteSleutel.value.second && kijker == null && laatsteSleutel.value.first == scherm
            laatsteSleutel.value = wisselSleutel
            // de laag zelf kan de focus al teruggezet hebben (bijv. op de regel die hem opende); alleen als niets in de inhoud focus heeft
            if (popupDicht) { kotlinx.coroutines.delay(48); if (!inhoudHeeftFocus.value) runCatching { inhoudFocus.requestFocus() } }
            // przeglądarka zamknięta (Wstecz z filmu albo zdjęcia): fokus wraca na siatkę (zdjęcie, na którym skończyłeś),
            // a gdyby jeszcze go tam nie było — na treść ekranu, nigdy do bocznego menu
            if (kijkerByl) { kotlinx.coroutines.delay(150); if (!inhoudHeeftFocus.value) runCatching { inhoudFocus.requestFocus() } }
            // nowy ekran (sekcja, podstrona, powrót z podstrony): gdyby sam nie przejął fokusu, trafia on na treść, nie do menu
            if (!popupDicht && !kijkerByl && !wisselSleutel.second && !popup) {
                kotlinx.coroutines.delay(600); if (!inhoudHeeftFocus.value && !ladeFocusNu.value) runCatching { inhoudFocus.requestFocus() }
            }
            kotlinx.coroutines.delay(800)
            wisselt.value = false
        }
        ModalNavigationDrawer(
            drawerState = lade,
            modifier = Modifier.graphicsLayer { alpha = if (kijker != null) 0f else 1f },
            drawerContent = { waarde ->
                Column(
                    Modifier.fillMaxHeight()
                        .background(
                            if (waarde == DrawerValue.Open) androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xF5101018), Color(0xE6101018)))
                            else androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xCC07070C), Color(0x0007070C))),
                        )
                        .focusProperties { canFocus = !ladeDicht }
                        // binnenkomen (◀ aan de rand, lang Terug) = altijd focus op het item van de huidige sectie, niet op wat het
                        // dichtstbij ligt of het laatst gefocust was; daarna loopt de focus gewoon item voor item
                        .onFocusChanged {
                            ladeFocusNu.value = it.hasFocus
                            if (it.hasFocus) Log.i("Fotos", "lade focus (wisselt=$ladeDicht)")
                        }
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(12.dp).selectableGroup(),
                ) {
                    // logo u góry szyny, jak w Immichin
                    Logo(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
                    for (s in Sectie.menu()) {
                        if (s == Sectie.INSTELLINGEN) Spacer(Modifier.height(16.dp))
                        NavigationDrawerItem(
                            selected = sectie == s && stapel.isEmpty(),
                            modifier = (if (sectie == s) Modifier.focusRequester(ladeFocus) else Modifier)
                                .onFocusChanged { if (it.isFocused && !ladeBinnen.value) Log.i("Fotos", "lade: binnen op ${s.name} (huidig ${sectie.name})") }
                                // w trakcie przejścia (zamknięcie przeglądarki, okna, zmiana ekranu) żadna pozycja menu nie może
                                // przechwycić fokusu — inaczej menu na chwilę się rozwija i lista „skacze”
                                .focusProperties { canFocus = !ladeDicht },
                            onClick = {
                                stapel.clear(); ZabawaPamiec.wybor = null; sectie = s
                                lade.setValue(DrawerValue.Closed)
                            },
                            leadingContent = { Icon(s.icoon, null) },
                            colors = NavigationDrawerItemDefaults.colors(
                                containerColor = Color.Transparent, contentColor = Kleur.zacht, inactiveContentColor = Kleur.zacht,
                                selectedContainerColor = Color.Transparent, selectedContentColor = Kleur.pinezka,
                                focusedContainerColor = Color.White, focusedContentColor = Color(0xFF101018),
                                focusedSelectedContainerColor = Color.White, focusedSelectedContentColor = Color(0xFF101018),
                            ),
                        ) { Text(if (s == Sectie.FOTOS) naamApp else stringResource(s.label), color = androidx.tv.material3.LocalContentColor.current) }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            },
        ) {
            // Binnenkomen zonder flits: verlaat de focus het scherm naar links (◀ aan de rand), dan gaat hij meteen naar het
            // lade-item van de huidige sectie. Android koos anders eerst het geometrisch dichtstbijzijnde item (bv.
            // Verkennen) en pas een frame later corrigeerde ladeInstap dat: een zichtbare flits. (canFocus per item of
            // focusProperties.enter op de lade werken niet: tv-material zet die zelf.)
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            Box(
                Modifier.fillMaxSize().padding(start = 80.dp)
                    .focusRequester(inhoudFocus)
                    .onFocusChanged { inhoudHeeftFocus.value = it.hasFocus }
                    .focusProperties {
                        exit = { richting ->
                            if (richting == androidx.compose.ui.focus.FocusDirection.Left && !ladeDicht) ladeFocus
                            else androidx.compose.ui.focus.FocusRequester.Default
                        }
                    }
                    .focusGroup(),
            ) {
                key(scherm) {
                    when (scherm) {
                        is Scherm.Tijdlijn -> TijdlijnScherm(scherm, nav)
                        is Scherm.Albums -> AlbumsScherm(scherm.staat, nav)
                        is Scherm.Verkennen -> VerkenScherm(scherm.staat, nav)
                        is Scherm.Zoeken -> ZoekScherm(scherm.staat, nav)
                        is Scherm.Herinneringen -> HerinneringenScherm(scherm.staat, nav)
                        is Scherm.Kaart -> KaartScherm(scherm.staat, nav)
                        is Scherm.KaartFotos -> KaartFotosScherm(scherm, nav)
                        is Scherm.Personen -> PersonenScherm(scherm.staat, nav)
                        is Scherm.Mappen -> MappenScherm(scherm, nav)
                        is Scherm.Lijst -> LijstScherm(scherm, nav)
                        is Scherm.Filmy -> FilmyScherm(scherm.staat, nav)
                        is Scherm.Rocznice -> RoczniceScherm(nav)
                        is Scherm.Zabawa -> ZabawaScherm(nav)
                        is Scherm.Quiz -> QuizScherm(scherm.staat, nav)
                        is Scherm.Impreza -> ImprezaScherm()
                        is Scherm.Podroz -> PodrozScherm(scherm.staat)
                        is Scherm.Rosniecie -> RosniecieScherm(scherm.staat)
                        is Scherm.Podobni -> PodobniScherm(scherm.staat)
                        is Scherm.Kadr -> KadrScherm(scherm.staat)
                        is Scherm.Powroty -> PowrotyScherm(scherm.staat, nav)
                        is Scherm.Kolory -> KoloryScherm(nav)
                        is Scherm.Synology -> SynologyScherm(scherm.staat, nav)
                        is Scherm.Rok -> RokWSkrocieScherm(scherm.staat, nav)
                        is Scherm.Instellingen -> {
                            // tryb dziecięcy: Ustawienia tylko po PIN-ie rodzica (raz na wejście do sekcji)
                            var odblokowane by remember { mutableStateOf(!Instellingen.trybDzieciecy || !Pin.ma(ctx, PIN_RODZICA)) }
                            if (!odblokowane) PinEkran(stringResource(R.string.pin_rodzica), stringResource(R.string.pin_rodzica_wpisz), anuluj = { sectie = startSectie }) { pin ->
                                Pin.sprawdz(ctx, PIN_RODZICA, pin).also { if (it) odblokowane = true }
                            }
                            else InstellingenScherm(opnieuwKoppelen = { instellen = true }, ontkoppeld = { sectie = Sectie.FOTOS }, toetsen = { toetsen = true })
                        }
                    }
                }
            }
        }
        if (toetsen && kijker == null) ToetsenOverzicht { toetsen = false }
        if (Rechten.open && kijker == null) RechtenScherm(opnieuwKoppelen = { Rechten.open = false; instellen = true }) { Rechten.open = false }
        if (Update.nieuwsOpen && kijker == null) NieuwsScherm { Update.nieuwsOpen = false }
        else if (Update.open && kijker == null) UpdateScherm { Update.open = false }
        kijker?.let { (k, raster, naTerug) ->
            Kijker(
                k,
                // "Toon op kaart" (OK in het infopaneel): viewer dicht, naar de kaartsectie, inzoomen op die plek
                opKaart = { a ->
                    // wywołane wciśnięciem OK: puszczenie tego samego OK nie może trafić w boczne menu (fokus na chwilę
                    // ląduje na pierwszej pozycji, Szukaj, zanim mapa go przejmie) ani w mapę (OK = przybliż)
                    Toetsen.okGeslikt = true
                    kijker = null; stapel.clear(); ZabawaPamiec.wybor = null; sectie = Sectie.KAART
                    (wortel(Sectie.KAART) as Scherm.Kaart).staat.toon(a)
                },
            ) { laatste ->
                if (raster != null && laatste != null) {
                    // najpierw fokus na element siatki (pod przeglądarką, przewinięty do niego), dopiero potem zamknięcie:
                    // fokus ani przez chwilę nie jest „bezdomny”, więc nie ucieka do bocznego menu
                    raster.focusDoel = laatste.id; raster.terugNaar = laatste.id
                    val zamykany = kijker
                    scope.launch { kotlinx.coroutines.delay(120); if (kijker === zamykany) { kijker = null; naTerug?.invoke() } }
                } else {
                    // ekrany z kafelkami (Wspomnienia, Rocznice…): najpierw fokus na kafelek, z którego otwarto przeglądarkę
                    PamiecFokusu.przywroc()
                    val zamykany = kijker
                    scope.launch { kotlinx.coroutines.delay(120); if (kijker === zamykany) { kijker = null; naTerug?.invoke() } }
                }
            }
        }
        // ekran udostępniania na samym wierzchu (otwierany z przeglądarki); po zamknięciu fokus wraca do przeglądarki
        if (Udostepnianie.otwarte) UdostepnianieScherm()
        // komunikaty edycji (osoby, albumy) na samym wierzchu
        EdycjaKomunikat(Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}
