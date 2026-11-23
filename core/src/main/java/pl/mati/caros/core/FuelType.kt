package pl.mati.caros.core

/** Rodzaj paliwa – ten sam w CAR OS i CAR OS Connect (klucze jak w docs/fuel.json). */
enum class FuelType(val key: String, val short: String) {
    ON("on", "ON"),
    PB95("pb95", "Pb95"),
    PB98("pb98", "Pb98"),
    LPG("lpg", "LPG");

    companion object {
        fun of(key: String?): FuelType = entries.firstOrNull { it.key == key } ?: ON
    }
}
