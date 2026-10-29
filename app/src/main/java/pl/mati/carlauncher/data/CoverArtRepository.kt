package pl.mati.carlauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Okładki z internetu dla źródeł, które ich nie wysyłają (np. Bluetooth AVRCP na Androidzie 10).
 * Deezer (dobry dla polskiej muzyki), w zapasie iTunes. Wyniki w pamięci i na dysku.
 */
object CoverArtRepository {

    private val memory = object : LinkedHashMap<String, Bitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > 40
    }

    /** Pierwszy wykonawca bez dopisków: „A, B oraz C - Album” → „A”. */
    fun cleanArtist(a: String?): String? = a
        ?.substringBefore(" - ")
        ?.split(",", " oraz ", " & ", " feat", " ft.", " x ", " i ", " and ")
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    fun cleanTitle(t: String?): String? = t
        ?.replace(Regex("""\s*[\(\[](feat|ft|prod|official|lyric|audio|video)[^)\]]*[\)\]]""", RegexOption.IGNORE_CASE), "")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /** Blokujące – wołać z wątku w tle. */
    fun find(ctx: Context, artistRaw: String?, titleRaw: String?): Bitmap? {
        val title = cleanTitle(titleRaw) ?: return null
        val artist = cleanArtist(artistRaw)
        val key = "${artist.orEmpty().lowercase()}|${title.lowercase()}"
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }

        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        val file = File(dir, md5(key) + ".jpg")
        var bmp: Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            val url = try { deezer(artist, title) } catch (_: Exception) { null }
                ?: try { itunes(artist, title) } catch (_: Exception) { null }
            if (url != null) {
                try {
                    val bytes = download(url)
                    file.writeBytes(bytes)
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: Exception) {
                }
            }
        }
        synchronized(memory) { memory[key] = bmp }
        return bmp
    }

    private fun deezer(artist: String?, title: String): String? {
        val q = if (artist != null) "artist:\"$artist\" track:\"$title\"" else title
        val j = JSONObject(get("https://api.deezer.com/search?limit=1&q=" + URLEncoder.encode(q, "UTF-8")))
        val d = j.optJSONArray("data")
        if (d == null || d.length() == 0) {
            // drugie podejście: zwykłe wyszukiwanie
            if (artist == null) return null
            val j2 = JSONObject(get("https://api.deezer.com/search?limit=1&q=" + URLEncoder.encode("$artist $title", "UTF-8")))
            val d2 = j2.optJSONArray("data") ?: return null
            if (d2.length() == 0) return null
            return d2.getJSONObject(0).optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotBlank() }
        }
        return d.getJSONObject(0).optJSONObject("album")?.optString("cover_xl")?.takeIf { it.isNotBlank() }
    }

    private fun itunes(artist: String?, title: String): String? {
        val term = listOfNotNull(artist, title).joinToString(" ")
        val j = JSONObject(get("https://itunes.apple.com/search?entity=song&limit=1&term=" + URLEncoder.encode(term, "UTF-8")))
        val r = j.optJSONArray("results") ?: return null
        if (r.length() == 0) return null
        return r.getJSONObject(0).optString("artworkUrl100").takeIf { it.isNotBlank() }?.replace("100x100", "600x600")
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "CarOS/1.1")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
