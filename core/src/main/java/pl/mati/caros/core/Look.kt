package pl.mati.caros.core

import org.json.JSONObject

/**
 * Wygląd wspólny dla auta i telefonu (komenda "look", pole "look" w statusie):
 * [glass] – Liquid Glass, [tone] – "auto" (auto: światła / pora dnia, telefon: jak system), "light", "dark",
 * [accent] – kolor akcentu ARGB (null = bez zmiany), [anim] – animacja kropli, [t] – kiedy zmieniony (ms).
 * Strona z nowszym [t] wygrywa – zmiana w telefonie trafia do auta i odwrotnie, bez odbijania w kółko.
 */
data class Look(
    val glass: Boolean,
    val tone: String,
    val accent: Int? = null,
    val anim: Boolean = true,
    val t: Long = 0L,
    /** Styl szkła: true = matowe (rozmyte), false = gładkie (przejrzyste z załamaniem), null = nie podano (starsza wersja). */
    val frost: Boolean? = null,
) {
    /** To samo bez znacznika czasu – do porównań „czy coś się zmieniło”. */
    val content: String get() = "$glass|$tone|${accent ?: ""}|$anim" + (frost?.let { "|$it" } ?: "")

    fun toJson(): JSONObject = JSONObject()
        .put("glass", glass).put("tone", tone)
        .put("accent", accent?.toLong()?.and(0xFFFFFFFFL) ?: JSONObject.NULL)
        .put("anim", anim).put("t", t)
        .apply { frost?.let { put("frost", it) } }

    /** Krótki opis do komunikatów: „Liquid Glass · ciemny”. */
    fun describe(): String = (if (glass) "Liquid Glass" + (frost?.let { if (it) " matowe" else " gładkie" } ?: "") else "Klasyczny") + " · " + when (tone) {
        "light" -> "jasny"
        "dark" -> "ciemny"
        else -> "automatycznie jasny / ciemny"
    }

    companion object {
        val TONES = setOf("auto", "light", "dark")

        fun fromJson(o: JSONObject?): Look? {
            if (o == null) return null
            val tone = o.optString("tone").takeIf { it in TONES } ?: return null
            val acc = if (o.has("accent") && !o.isNull("accent")) o.optLong("accent").toInt() else null
            val frost = if (o.has("frost") && !o.isNull("frost")) o.optBoolean("frost") else null
            return Look(o.optBoolean("glass"), tone, acc, o.optBoolean("anim", true), o.optLong("t"), frost)
        }

        /** Kolory akcentu do wyboru – te same w obu aplikacjach. */
        val ACCENTS: List<Int> = listOf(
            0xFF2FD3F0, 0xFF3B82F6, 0xFF8B5CF6, 0xFFEC4899, 0xFFEF4444,
            0xFFF97316, 0xFFF59E0B, 0xFF22C55E, 0xFF14B8A6, 0xFFE5E7EB,
        ).map { it.toInt() }
    }
}
