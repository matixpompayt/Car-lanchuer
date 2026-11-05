# CAR OS – system dla radia samochodowego z Androidem

**CAR OS** to darmowy launcher (własny system) dla radia samochodowego z Androidem (head unit), w stylu ekranów z nowych aut. Zastępuje ekran główny radia: mapa z nawigacją, muzyka, telefon, dane z auta i ustawienia w jednym miejscu, dopasowane do jazdy.

Projekt powstaje dla radia **TopWay TS10** (Android 10, 1280×720) w **VW Touranie**, ale działa na większości radii z Androidem 10+ (TopWay, FYT/UIS, Junsun i podobne).

👉 **Strona projektu:** https://matixpompayt.github.io/Car-lanchuer/
👉 **Pobierz:** [najnowsza wersja (Releases)](https://github.com/matixpompayt/Car-lanchuer/releases/latest) – plik `CarOS-*.apk`
👉 **Aplikacja na telefon:** [CAR OS Connect](https://github.com/matixpompayt/Caros-connect)

## Funkcje

**Mapa i nawigacja**
- Mapa wektorowa 3D (MapLibre + OpenFreeMap), tryb dzień / noc, auto 3D na mapie przyklejone do drogi
- Własna nawigacja z głosem po polsku, przeliczanie trasy, mapy offline
- Ograniczenia prędkości **bez internetu** (dane OpenStreetMap aktualizowane co tydzień), znaki: światła, STOP, przejścia, przejazdy kolejowe, fotoradary
- Ostrzeżenia o fotoradarach z CamSam
- Mapa na pełny ekran pod wirtualny licznik (rozciąganie, własny układ elementów)

**Wygląd**
- 6 motywów, które zmieniają cały system: **Klasyczny**, **Minimal** (jak w Tesli), **VW Nocny** (zegary jak w Touranie), **Sport** (shift-light, telemetria), **Pełna mapa**, **Retro 80s**
- Własny ekran główny: kafle (prędkość, płyn, doładowanie, akumulator, zasięg, km dziś / w tygodniu…), rozmiar mapy, ukrywanie kart

**Auto**
- Dane z magistrali CAN radia i z OBD2 (ELM327): prędkość, obroty, temperatura płynu, doładowanie, napięcie
- Kontrola akumulatora przy każdym uruchomieniu, timer schłodzenia turbo
- Dziennik tras z mapą, tankowania i spalanie, przypomnienia o serwisie

**Muzyka i telefon**
- Odtwarzacz dla każdej aplikacji (Spotify, YouTube Music, radio, muzyka z Bluetooth)
- Komendy głosowe po polsku

**CAR OS Connect (telefon)**
- Gdzie zaparkowałem, auto na żywo w czasie jazdy, powiadomienie „Auto ruszyło”
- Tankowania, serwis, grafik pracy z przypomnieniem o wyjeździe, synchronizacja domu i pracy, widżet

Aktualizacje: radio samo sprawdza GitHub Releases, pobiera nową wersję i proponuje instalację.

## Instalacja

1. Pobierz `CarOS-*.apk` z [Releases](https://github.com/matixpompayt/Car-lanchuer/releases/latest).
2. Zainstaluj na radiu (pendrive + menedżer plików, albo `adb install -r CarOS-*.apk`). Zezwól na instalację z nieznanych źródeł.
3. Ustaw **CAR OS** jako domyślny ekran główny. Na niektórych radiach trzeba wyłączyć fabryczny launcher w ustawieniach fabrycznych.
4. Kreator pierwszego uruchomienia poprowadzi przez uprawnienia: lokalizacja, dostęp do powiadomień (muzyka i nawigacja), mikrofon.

## Budowanie

- Android Studio (Koala lub nowsze) albo `./gradlew assembleDebug` (JDK 17).
- Każdy push na `main` buduje APK w GitHub Actions i publikuje wydanie (wersja `1.<numer buildu>`). Lista zmian: `app/src/main/assets/changelog.txt`.

Technologie: Kotlin, Jetpack Compose, MapLibre Native, OpenFreeMap, OpenStreetMap (Overpass), Open-Meteo.

## English

**CAR OS** is a free custom launcher / car UI for Android head units (TopWay, FYT, Junsun and similar Chinese car radios, Android 10+). It replaces the stock home screen with a modern car-style interface: offline vector maps with turn-by-turn voice navigation, offline speed limits from OpenStreetMap, media player, CAN bus and OBD2 (ELM327) vehicle data, battery check, trip log, six system themes (Minimal/Tesla-like, VW night, Sport, Full map, Retro 80s) and a companion phone app (**CAR OS Connect**: find my parked car, live car location, fuel log, service reminders). UI is in Polish. Download the APK from [Releases](https://github.com/matixpompayt/Car-lanchuer/releases/latest).

---
Dane map © współtwórcy [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL), kafle [OpenFreeMap](https://openfreemap.org).
