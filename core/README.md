# core – wspólny kod CAR OS i CAR OS Connect

- `Proto` – protokół auto ↔ telefon: wersja (`pv`), lista obsługiwanych komend (`caps`), `nack` na nieznaną komendę
- `RelayCrypto` – szyfrowanie wiadomości przez przekaźnik (AES-GCM + gzip, kanały sha256)
- `AlongRoute` – stacje paliw i miejsca na przerwę przy trasie (OSM Overpass + TomTom)
- `Geo` – odległości

CAR OS buduje ten moduł z repo. CAR OS Connect pobiera katalog `core/` z repo Car-lanchuer
w wersji z `coreRef` (gradle.properties Connect) – po zmianie tutaj trzeba podbić `coreRef` w Connect.

Testy: `./gradlew :core:testReleaseUnitTest` (uruchamiane w CI przed każdym wydaniem beta).
