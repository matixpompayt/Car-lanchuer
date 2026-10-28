# Car Launcher – Touran (Android 10, 1280×720)

Launcher w stylu nowych aut: ekran główny (mapa + muzyka + dane z auta) i ekran **Pojazd** z zegarami OBD2 (ELM327).

## Build
1. Android Studio (Koala lub nowsze) → **Open** → folder `CarLauncher`.
2. Poczekaj na Gradle sync (pobierze AGP 8.5.2, Gradle 8.7, Compose).
3. **Build → Build APK(s)** → `app/build/outputs/apk/debug/app-debug.apk`.
   Z terminala: `./gradlew assembleDebug` (JDK 17).

## Instalacja na radiu
- APK na pendrive → menedżer plików na radiu → zainstaluj (zezwól na nieznane źródła), albo
- `adb install -r app-debug.apk` (WiFi/USB debug w opcjach programisty radia).

## Pierwsze uruchomienie
1. Zezwól na lokalizację.
2. Aplikacje → **Ustawienia**:
   - **Domyślny ekran główny** → wybierz Car Launcher (na Junsun czasem trzeba wyłączyć ich launcher w ustawieniach fabrycznych / „Factory”, hasło zależne od firmware).
   - **Dostęp do powiadomień** → włącz dla Car Launcher (muzyka + manewry z Map Google/Waze).
   - **ELM327**: sparuj adapter w Bluetooth radia (PIN 1234/0000), potem wybierz go z listy. Łączy się sam przy każdym starcie.

## Dane OBD (PIDy mode 01)
| Dana | PID | Wzór |
|---|---|---|
| Prędkość | 0D | A km/h |
| Obroty | 0C | (256A+B)/4 |
| MAP | 0B | A kPa |
| Baro | 33 | A kPa (brak → 101 kPa) |
| Płyn | 05 | A−40 °C |
| Obciążenie | 04 | A·100/255 % |
| Napięcie | `AT RV` | z ELM |

Doładowanie = MAP − baro. Szybkie PIDy (0D/0C/0B) co cykl, wolne co 5. cykl. PID, który 3× zwróci NO DATA, jest pomijany.
Protokół: `ATSP0` (auto). Bez zapłonu ECU nie odpowie – launcher ponawia co 5 s.
Bez OBD prędkość bierze się z GPS.

## Struktura
- `data/ObdManager.kt` – ELM327 SPP, init, polling, parsowanie
- `data/MediaRepository.kt` – sesje multimediów (MediaSessionManager)
- `data/NavRepository.kt` + `service/CarNotificationListener.kt` – manewry z powiadomień nawigacji
- `data/LocationRepository.kt` – GPS, prędkość, dystans trasy
- `data/WeatherRepository.kt` – temperatura z Open-Meteo
- `ui/*` – Compose; projekt rysowany w 1280 dp i skalowany do ekranu (`Theme.kt → CarRoot`)

Zakres obrotomierza i czerwone pole: `RPM_MAX` / `RPM_RED` w `ui/VehicleScreen.kt`.
