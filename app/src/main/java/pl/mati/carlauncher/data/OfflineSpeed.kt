package pl.mati.carlauncher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import pl.mati.carlauncher.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater
import kotlin.math.floor

/**
 * Ograniczenia prędkości bez internetu: plik z drogami i limitami budowany co tydzień
 * przez GitHub Actions (tools/speedlimits/build.py) z OpenStreetMap i pobierany z wydania „speed-data”.
 */
object OfflineSpeed {

    data class Info(val regions: String, val builtAt: Long, val bytes: Long, val tiles: Int)

    /** Opis pobranego pliku (null = brak). */
    val info = MutableStateFlow<Info?>(null)
    /** Postęp pobierania 0..1, null = nie pobiera. */
    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow<String?>(null)

    class Area(val ways: List<SpeedLimitRepository.Way>, val signs: List<SpeedLimitRepository.Sign>)

    private const val TILE = 0.05
    private const val SCALE = 100_000.0
    private const val ASSET = "speedlimits.bin"
    private const val CHECK_EVERY = 24L * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appCtx: Context? = null
    private var raf: RandomAccessFile? = null
    private val index = HashMap<Long, LongArray>()      // klucz kafla -> [offset, długość]
    private val tileCache = object : LinkedHashMap<Long, Pair<List<SpeedLimitRepository.Way>, List<SpeedLimitRepository.Sign>>>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Pair<List<SpeedLimitRepository.Way>, List<SpeedLimitRepository.Sign>>>?) = size > 30
    }
    private var lastArea: Area? = null
    private var lastLat = 0.0
    private var lastLon = 0.0
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, ASSET)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speed_offline", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        scope.launch {
            open()
            // sprawdzaj aktualizację przy starcie i raz na dobę
            while (isActive) {
                val p = prefs(appCtx!!)
                if (System.currentTimeMillis() - p.getLong("checked", 0) > CHECK_EVERY || info.value == null) {
                    try { update(false) } catch (_: Exception) {}
                }
                delay(30 * 60 * 1000L)
            }
        }
    }

    /** Ręczne „Pobierz / sprawdź teraz”. */
    fun updateNow() {
        if (progress.value != null) return
        scope.launch { try { update(true) } catch (e: Exception) { error.value = e.message ?: "Błąd pobierania" } }
    }

    private fun update(force: Boolean) {
        val ctx = appCtx ?: return
        val p = prefs(ctx)
        error.value = null
        val c = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/speed-data").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CarOS")
        val json = try {
            if (c.responseCode == 404) { if (force) error.value = "Dane jeszcze nie zbudowane na GitHubie"; return }
            if (c.responseCode !in 200..299) throw java.io.IOException("GitHub: HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
        val assets = json.optJSONArray("assets") ?: return
        var url: String? = null; var stamp = ""; var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET) { url = a.optString("browser_download_url"); stamp = a.optString("updated_at"); size = a.optLong("size") }
        }
        if (url == null) { if (force) error.value = "Brak pliku w wydaniu speed-data"; return }
        p.edit().putLong("checked", System.currentTimeMillis()).apply()
        if (!force && stamp == p.getString("stamp", null) && file(ctx).exists()) return
        if (force && stamp == p.getString("stamp", null) && file(ctx).exists()) return
        download(url, size, file(ctx))
        p.edit().putString("stamp", stamp).apply()
        open()
    }

    private fun download(url: String, size: Long, dest: File) {
        val tmp = File(dest.parentFile, "$ASSET.part")
        progress.value = 0f
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "CarOS")
            try {
                if (c.responseCode !in 200..299) throw java.io.IOException("Pobieranie: HTTP ${c.responseCode}")
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else size
                c.inputStream.use { inp ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }
            } finally { c.disconnect() }
            synchronized(lock) {
                try { raf?.close() } catch (_: Exception) {}
                raf = null
                if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            }
        } finally {
            tmp.delete()
            progress.value = null
        }
    }

    private fun open() {
        val ctx = appCtx ?: return
        val f = file(ctx)
        synchronized(lock) {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            index.clear(); tileCache.clear(); lastArea = null
            if (!f.exists()) { info.value = null; return }
            try {
                val r = RandomAccessFile(f, "r")
                val magic = ByteArray(4); r.readFully(magic)
                if (String(magic) != "CSL1") { r.close(); info.value = null; return }
                val built = r.readLong()
                val rl = readVarint(r)
                val rb = ByteArray(rl); r.readFully(rb)
                val n = r.readInt()
                val idx = ByteArray(n * 16); r.readFully(idx)
                val bb = java.nio.ByteBuffer.wrap(idx)
                repeat(n) {
                    val tx = bb.int; val ty = bb.int; val off = bb.int.toLong() and 0xFFFFFFFFL; val len = bb.int.toLong()
                    index[key(tx, ty)] = longArrayOf(off, len)
                }
                raf = r
                info.value = Info(String(rb, Charsets.UTF_8), built, f.length(), n)
            } catch (_: Exception) {
                info.value = null
            }
        }
    }

    /**
     * Drogi i znaki w promieniu ~1 km od auta. null = brak pliku albo auto poza obszarem danych
     * (wtedy SpeedLimitRepository pobiera online). Przelicza dopiero po przejechaniu 250 m.
     */
    fun around(lat: Double, lon: Double): Area? {
        synchronized(lock) {
            if (raf == null) return null
            lastArea?.let { if (RouteRepository.dist(lat, lon, lastLat, lastLon) < 250) return it }
            val cx = floor(lon / TILE).toInt(); val cy = floor(lat / TILE).toInt()
            var covered = false
            val ways = LinkedHashMap<Long, SpeedLimitRepository.Way>()
            val signs = ArrayList<SpeedLimitRepository.Sign>()
            val kx = 111_320.0 * Math.cos(Math.toRadians(lat))
            for (dx in -1..1) for (dy in -1..1) {
                val k = key(cx + dx, cy + dy)
                if (!index.containsKey(k)) continue
                covered = true
                val t = tile(cx + dx, cy + dy) ?: continue
                for (w in t.first) {
                    if (ways.containsKey(w.id)) continue
                    // tylko drogi, które mają punkt/odcinek w promieniu ~1,2 km
                    var near = false
                    for (i in w.lat.indices) {
                        val ex = (w.lon[i] - lon) * kx; val ey = (w.lat[i] - lat) * 110_540.0
                        if (ex * ex + ey * ey < 1_440_000.0) { near = true; break }
                    }
                    if (near) ways[w.id] = w
                }
                for (s in t.second) {
                    val ex = (s.lon - lon) * kx; val ey = (s.lat - lat) * 110_540.0
                    if (ex * ex + ey * ey < 2_250_000.0) signs += s
                }
            }
            if (!covered) { lastArea = null; return null }
            val a = Area(ways.values.toList(), signs)
            lastArea = a; lastLat = lat; lastLon = lon
            return a
        }
    }

    private fun tile(tx: Int, ty: Int): Pair<List<SpeedLimitRepository.Way>, List<SpeedLimitRepository.Sign>>? {
        val k = key(tx, ty)
        tileCache[k]?.let { return it }
        val e = index[k] ?: return null
        val r = raf ?: return null
        val z = ByteArray(e[1].toInt())
        r.seek(e[0]); r.readFully(z)
        val inf = Inflater()
        val data = try {
            inf.setInput(z)
            val out = java.io.ByteArrayOutputStream(z.size * 4)
            val buf = ByteArray(32 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } finally { inf.end() }
        val rd = Reader(data)
        val oy = Math.round(ty * TILE * SCALE)
        val ox = Math.round(tx * TILE * SCALE)
        val wn = rd.varint().toInt()
        val ways = ArrayList<SpeedLimitRepository.Way>(wn)
        repeat(wn) {
            val id = rd.varint()
            val lim = rd.u8()
            val est = rd.u8() and 1 == 1
            val name = rd.utf().ifBlank { null }
            val n = rd.varint().toInt()
            val la = DoubleArray(n); val lo = DoubleArray(n)
            var py = oy; var px = ox
            for (i in 0 until n) {
                py += rd.zz(); px += rd.zz()
                la[i] = py / SCALE; lo[i] = px / SCALE
            }
            ways += SpeedLimitRepository.Way(id, if (lim == 0) null else lim, name, la, lo, est)
        }
        val sn = rd.varint().toInt()
        val signs = ArrayList<SpeedLimitRepository.Sign>(sn)
        repeat(sn) { i ->
            val kind = when (rd.u8()) { 1 -> "signals"; 2 -> "stop"; 3 -> "give_way"; 4 -> "crossing"; 5 -> "rail"; else -> "camera" }
            val la = (oy + rd.zz()) / SCALE; val lo = (ox + rd.zz()) / SCALE
            // id znaku: syntetyczny, stały dla kafla i pozycji
            signs += SpeedLimitRepository.Sign(k * 100_000 + i, kind, la, lo)
        }
        val res = ways to signs
        tileCache[k] = res
        return res
    }

    fun delete() {
        val ctx = appCtx ?: return
        synchronized(lock) {
            try { raf?.close() } catch (_: Exception) {}
            raf = null
            file(ctx).delete()
            prefs(ctx).edit().remove("stamp").apply()
        }
        open()
    }

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    private fun readVarint(r: RandomAccessFile): Int {
        var res = 0; var shift = 0
        while (true) {
            val b = r.readUnsignedByte()
            res = res or ((b and 0x7F) shl shift)
            if (b < 0x80) return res
            shift += 7
        }
    }

    private class Reader(val b: ByteArray) {
        var i = 0
        fun u8(): Int = b[i++].toInt() and 0xFF
        fun varint(): Long {
            var res = 0L; var shift = 0
            while (true) {
                val x = u8()
                res = res or ((x and 0x7F).toLong() shl shift)
                if (x < 0x80) return res
                shift += 7
            }
        }
        fun zz(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }
        fun utf(): String { val n = varint().toInt(); val s = String(b, i, n, Charsets.UTF_8); i += n; return s }
    }
}
