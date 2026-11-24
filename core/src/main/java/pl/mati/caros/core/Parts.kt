package pl.mati.caros.core

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Duże komendy w częściach: przekaźnik (ntfy) przyjmuje do 4 KB na wiadomość, a trasa z kilkudziesięcioma
 * przystankami bywa większa. Cała komenda (JSON) → gzip → kawałki po [CHUNK] bajtów, każdy jako
 * {"type":"pt","id","i","n","d"} (d – base64), każdy szyfrowany osobno. Druga strona składa je
 * [Joiner]em i wykonuje jak zwykłą komendę (z jej "mid" i potwierdzeniem).
 */
object Parts {
    const val TYPE = "pt"
    const val CHUNK = 2400

    fun split(plain: String, id: String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (1000..9999).random(), chunk: Int = CHUNK): List<JSONObject> {
        val gz = RelayCrypto.gzip(plain.toByteArray())
        val n = (gz.size + chunk - 1) / chunk
        return (0 until n).map { i ->
            val part = gz.copyOfRange(i * chunk, minOf(gz.size, (i + 1) * chunk))
            JSONObject().put("type", TYPE).put("id", id).put("i", i).put("n", n).put("d", Base64.getEncoder().encodeToString(part))
        }
    }

    /** Składa części; [add] zwraca całą komendę (tekst JSON), gdy doszła ostatnia brakująca część. */
    class Joiner(private val maxAgeMs: Long = 15 * 60_000L) {
        private class Entry(val at: Long, val parts: Array<String?>)

        private val map = HashMap<String, Entry>()

        @Synchronized
        fun add(o: JSONObject): String? {
            val now = System.currentTimeMillis()
            val it = map.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.at > maxAgeMs) it.remove()
            val id = o.optString("id")
            val n = o.optInt("n", 0)
            val i = o.optInt("i", -1)
            if (id.isBlank() || n <= 0 || n > 200 || i !in 0 until n) return null
            val e = map.getOrPut(id) { Entry(now, arrayOfNulls(n)) }
            if (e.parts.size != n) return null
            e.parts[i] = o.optString("d")
            if (e.parts.any { it == null }) return null
            map.remove(id)
            return try {
                val bos = ByteArrayOutputStream()
                e.parts.forEach { bos.write(Base64.getDecoder().decode(it)) }
                String(RelayCrypto.gunzip(bos.toByteArray()))
            } catch (_: Exception) {
                null
            }
        }
    }
}
