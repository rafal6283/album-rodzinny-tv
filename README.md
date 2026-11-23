# Album Rodzinny

Klient [Immich](https://immich.app) na Android TV: rodzinne zdjęcia i filmy na dużym ekranie, obsługiwane pilotem.
Fork aplikacji [TV Client for Immich](https://github.com/neforce/android-tv-immich-client) autorstwa **neforce**
(licencja MIT), z nowym wyglądem, polską wersją językową i dodatkowymi funkcjami.

> **Nieoficjalna aplikacja.** Nie jest tworzona, wspierana ani popierana przez projekt Immich ani przez autora
> oryginalnego klienta. „Immich” jest znakiem towarowym swoich właścicieli i pojawia się tu tylko w opisie zgodności.

## Co nowego względem oryginału

- **Nowy wygląd**: wąska szyna ikon z logo, ciemne tło z kolorową poświatą (inny odcień w każdej sekcji),
  szeryfowe nagłówki (Instrument Serif), zaokrąglone karty i kafelki.
- **Oś czasu jako siatka kwadratów** z nagłówkiem miesiąca („Listopad 2026”), miejscami (📍 Kraków · Zakopane +2)
  i liczbą elementów. Klasyczny układ Immich z podziałem na dni nadal jest w ustawieniach.
- **Zdjęcia i filmy razem** wszędzie; **Wspomnienia** liczą zdjęcia i filmy osobno, pokaz slajdów odtwarza też
  filmy, sekcja obejmuje dwa ostatnie tygodnie i ma przycisk „Odtwórz wszystkie”.
- **Szczegóły zdjęcia** (▲ w przeglądarce): twarze rozpoznanych osób, plik (MP · wymiary · rozmiar), aparat
  (przysłona · czas · ISO · ogniskowa, obiektyw), miejsce ze współrzędnymi, ulubione, albumy, opis i — opcjonalnie —
  mała mapa kraju.
- **Pasek akcji** (▼ w przeglądarce): pokaz slajdów, szczegóły, powiększenie, mapa.
- **Sterowanie filmem jak w Immichin**: krótkie ◀ ▶ = poprzedni / następny, przytrzymane ◀ ▶ = przewijanie (cały film
  w kilka sekund, po chwili szybciej), OK = pauza, ⏪︎ ⏩︎ = 30 s. Pod filmem pasek czasu z podpowiedziami klawiszy
  (po każdym klawiszu i na pauzie).
- **Ludzie** jako osobna sekcja w menu: wszystkie twarze rozpoznane przez Immich, także bez imienia, z filtrem
  Wszyscy / Z imieniem / Bez imienia, wyszukiwaniem i sortowaniem.
- **Edycja osób z telewizora**: na osi czasu osoby przycisk „Edytuj osobę” — imię (klawiatura ekranowa lub głos),
  data urodzenia (◀ ▶ pole, ▲ ▼ wartość), ukrywanie obcych twarzy i łączenie osoby rozpoznanej dwa razy.
- **Albumy z telewizora**: w przeglądarce ▼ → „Do albumu” dodaje zdjęcie (albo wszystkie zaznaczone przez ▼ → „Zaznacz”)
  do istniejącego lub nowego albumu; w albumie ▼ → „Usuń z albumu” (z możliwością przywrócenia) i „Zmień nazwę”.
  Usuwania zdjęć z biblioteki celowo nie ma. Wymaga nowych uprawnień klucza — aplikacja podpowie ponowne połączenie.
- **Mapa cieplna i podświetlone kraje** jak w Immichin (Ustawienia → Mapa, oba do wyłączenia).
- **Wyszukiwanie z filtrami**: osoba + miejsce + rok, z tekstem albo bez (np. wszystkie zdjęcia Oli z Antalyi w 2025).
- **Kiedyś i dziś**: na osi czasu osoby zdjęcie sprzed roku (◀ ▶ zmienia liczbę lat) obok najnowszego.
- **Pokaz osoby / albumu** jednym przyciskiem; **opisy z Immich** jako napisy w pokazie; **wznawianie filmów**.
- **Rocznice albumów** („3 lata temu: Bułgaria”) na osi czasu i opcjonalny pokaz **„Tego dnia” na start**.
- **„Kto to jest?”** w sekcji Ludzie: twarze bez imienia po kolei — przypisz do osoby, nadaj imię, ukryj albo pomiń.
- **Klawiatura ekranowa** z polskimi literami, wielkimi literami w imionach i mikrofonem.
- **PIN profilu** (4 cyfry) przy przełączaniu na profil domownika i przy starcie.
- **Rocznice**: osobna sekcja z nadchodzącymi i niedawnymi urodzinami oraz rocznicami albumów; OK na osobie pokazuje jej zdjęcia z okolic urodzin z każdego roku.
- **Kolejność menu**: Ustawienia → Wygląd → Kolejność menu; OK chwyta sekcję, ▲▼ ją przesuwają (Ustawienia zostają na dole).
- **Mapa w stylu Immichin**: nagłówek „Miejsca, w których byliście” z podsumowaniem (elementy · miejsca · kraje) i karta
  wybranej grupy po prawej: zdjęcie, rodzaj (kontynent / region / kraj / miejsce), kraj i liczba miejsc, liczba
  elementów, okres, cztery miniatury z „+N” i podpowiedzi pilota.
- **Powiększanie** do 4× z przesuwaniem strzałkami; przy powiększeniu wczytuje się pełna rozdzielczość
  (Immich ≥ 1.133).
- **Kanały na ekranie głównym Android TV**: „Najnowsze zdjęcia”, „Losowe zdjęcia” i „Tego dnia”, odświeżane co ~3 h.
  Kafelek otwiera zdjęcie w aplikacji. Miniatury idą przez dostawcę treści aplikacji (launcher nie dostaje klucza
  API), który udostępnia tylko zdjęcia z bieżących kanałów.
- **Filmy** jako osobna sekcja i przycisk **Losowo** (losowa mieszanka zdjęć i filmów jako pokaz, także w Ulubionych).
- **Udostępnianie gościom kodem QR**: w przeglądarce ▼ → Udostępnij wybierasz zdjęcia i filmy, telewizor pokazuje kod QR,
  goście pobierają pliki w przeglądarce telefonu (tylko sieć domowa, jednorazowy kod, koniec po 10 min bez ruchu).
  Zdjęcia idą w pełnej rozdzielczości jako JPEG, filmy w wersji do odtwarzania (oryginały wymagałyby uprawnienia
  `asset.download`).
- **Urodziny**: osoby z datą urodzenia w Immich pojawiają się na górze osi czasu (dziś i w najbliższym tygodniu);
  OK otwiera ich zdjęcia.
- **Ulubione pilotem** (▼ → Ulubione). Wymaga uprawnienia `asset.update` — nowe parowanie tworzy klucz z nim; przy
  starym kluczu aplikacja podpowie ponowne połączenie.
- **Live Photos**: ruchomy fragment odtwarza się raz po otwarciu zdjęcia, ponownie przyciskiem Live.
- **Niezgodne filmy**: komunikat podaje kodek, rozdzielczość i podpowiedź, jak ustawić transkodowanie w Immich.
- **Ramka cyfrowa**: wygaszacz z godzinami pracy (poza nimi czarny ekran z przygaszonym zegarem) i przyciemnianiem
  wieczorem.
- **Muzyka w tle pokazu**: adres strumienia lub pliku mp3 w ustawieniach; pauza na czas filmów.
- **Kanał wybranego albumu** na ekranie głównym (przycisk „Na ekran główny” w albumie).
- **Profile domowników**: każdy ze swoim kontem Immich i ustawieniami; dodawanie przez kod QR, przełączanie w
  Ustawieniach.
- **Wyszukiwanie systemowe**: intencja ACTION_SEARCH (np. „szukaj … w Album Rodzinny”) otwiera sekcję Szukaj.
- **Polski** jako pełne tłumaczenie interfejsu (oprócz angielskiego, niderlandzkiego, niemieckiego, francuskiego i
  hiszpańskiego z oryginału).
- Automatyczne sprawdzanie aktualizacji z GitHuba oryginału jest domyślnie **wyłączone** (zastąpiłoby tę wersję).

## Wymagania i instalacja

- Android TV 8.0+ (ARM 32/64-bit), serwer Immich 2.x lub 3.x w sieci lokalnej (HTTP lub ważny certyfikat HTTPS).
- Zainstaluj APK (np. aplikacją Downloader lub z pendrive’a). Jeśli masz zainstalowany oryginalny klient z GitHuba
  autora, najpierw go odinstaluj — pakiet ma tę samą nazwę, ale inny podpis.
- Parowanie kodem QR z telefonu, uprawnienia klucza API, wygaszacz ekranu i pozostałe ustawienia działają jak w
  oryginale — zobacz [README.upstream.md](README.upstream.md).

## Wydania i aktualizacje

- **GitHub Actions** (`.github/workflows/budowanie.yml`) kompiluje każdą zmianę na `main` i każdy pull request
  (APK do pobrania jako artefakt przebiegu). Tag `v*` (np. `v2.9-ar3`) buduje APK podpisany **stałym kluczem wydań**
  (sekrety `RELEASE_*`) i publikuje go jako wydanie; opis z `wydania/<tag>.md`, jeśli istnieje.
- Aplikacja przy starcie sprawdza najnowsze wydanie tego repozytorium i sama proponuje instalację (z kontrolą SHA-256).
- Nowa wersja: podnieś `versionCode` i `versionName` w `fotos/build.gradle.kts` (np. `2.9-ar3`), dopisz
  `wydania/v2.9-ar3.md`, wypchnij i dodaj tag `v2.9-ar3`. Można to robić z komputera albo z sesji Claude Code w
  chmurze na telefonie — budowanie i publikacja dzieją się na GitHubie.
- Ten sam klucz wydań jest na komputerze dewelopera (`~/.android/immichclient-release.properties`), więc APK z
  komputera i z GitHuba instalują się jedno na drugim. **Klucza nie wolno zgubić** — bez niego nie da się
  zaktualizować zainstalowanej aplikacji (trzeba by ją odinstalować).

## Budowanie

```sh
./gradlew :fotos:assembleRelease          # APK na telewizor (ARM)
./gradlew :fotos:assembleRelease -Pemu    # dodatkowo x86/x86_64 dla emulatora
```

Wymaga JDK 17+ i Android SDK 35. Bez własnego klucza wydania APK jest podpisany kluczem debug
(zob. `fotos/build.gradle.kts`). Baner i ikonę generuje `fotos/banner_album_rodzinny.py`.

## Licencje i podziękowania

- Kod: MIT — © neforce (oryginalny klient) oraz autorzy tego forka; zob. [LICENSE](LICENSE).
- Instrument Serif: SIL Open Font License — zob. [fotos/OFL-InstrumentSerif.txt](fotos/OFL-InstrumentSerif.txt).
- Overpass: SIL Open Font License — zob. [fotos/OFL-Overpass.txt](fotos/OFL-Overpass.txt).
