package pl.mati.carlauncher.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import pl.mati.carlauncher.BuildConfig

/**
 * Kopia zapasowa wszystkich ustawień (profil, wygląd, serwis, dziennik tras).
 * - ręczna: do pliku wybranego przez użytkownika (np. pendrive),
 * - automatyczna: Pobrane/CarOS/CarOS-autokopia.json – przetrwa odinstalowanie aplikacji.
 */
object BackupManager {

    private const val AUTO_NAME = "CarOS-autokopia.json"
    private const val AUTO_DIR = "Download/CarOS/"

    data class Found(val uri: Uri, val created: Long, val name: String, val car: String)

    // ---------- JSON ----------

    fun buildJson(): String? {
        val sp = Prefs.store ?: return null
        ServiceRepository.save()
        val data = JSONObject()
        for ((k, v) in sp.all) {
            if (k == "trip_current" || k == "auto_backup_at") continue
            val e = JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                else -> continue
            }
            data.put(k, e)
        }
        return JSONObject()
            .put("app", "CAR OS")
            .put("version", BuildConfig.VERSION_NAME)
            .put("created", System.currentTimeMillis())
            .put("prefs", data)
            .toString(2)
    }

    private fun restoreJson(text: String): Boolean {
        val sp = Prefs.store ?: return false
        val root = JSONObject(text)
        if (root.optString("app") != "CAR OS") return false
        val data = root.getJSONObject("prefs")
        val ed = sp.edit().clear()
        val keys = data.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val e = data.getJSONObject(k)
            when (e.getString("t")) {
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "i" -> ed.putInt(k, e.getInt("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "s" -> ed.putString(k, e.getString("v"))
            }
        }
        return ed.commit()
    }

    // ---------- ręczna kopia ----------

    fun export(ctx: Context, uri: Uri): Boolean {
        return try {
            val json = buildJson() ?: return false
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
        } catch (_: Exception) {
            false
        }
    }

    /** Wczytuje kopię. Po sukcesie aplikacja powinna się zrestartować. */
    fun import(ctx: Context, uri: Uri): Boolean = try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        text != null && restoreJson(text)
    } catch (_: Exception) {
        false
    }

    // ---------- automatyczna kopia (Pobrane/CarOS) ----------

    /** Zapis autokopii. force=false → najwyżej raz na 12 h. */
    fun autoBackup(ctx: Context, force: Boolean = false) {
        val sp = Prefs.store ?: return
        if (!Prefs.profile.value.setupDone) return
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("auto_backup_at", 0L) < 12 * 3_600_000L) return
        try {
            val json = buildJson() ?: return
            val resolver = ctx.contentResolver
            val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var uri: Uri? = null
            resolver.query(
                coll, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(AUTO_NAME, AUTO_DIR), null,
            )?.use { c -> if (c.moveToFirst()) uri = Uri.withAppendedPath(coll, c.getLong(0).toString()) }
            if (uri == null) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, AUTO_DIR)
                }
                uri = resolver.insert(coll, v)
            }
            val target = uri ?: return
            resolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) }
            sp.edit().putLong("auto_backup_at", now).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Szuka autokopii po ponownej instalacji. Wymaga zgody na odczyt pamięci
     * (pliki z poprzedniej instalacji nie należą już do tej aplikacji).
     */
    fun findAutoBackup(ctx: Context): Found? = try {
        var found: Found? = null
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        ctx.contentResolver.query(
            coll, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf("CarOS-autokopia%.json"), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) {
                val uri = Uri.withAppendedPath(coll, c.getLong(0).toString())
                val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (text != null) {
                    val root = JSONObject(text)
                    if (root.optString("app") == "CAR OS") {
                        val p = root.getJSONObject("prefs")
                        fun s(k: String) = p.optJSONObject(k)?.optString("v").orEmpty()
                        found = Found(
                            uri, root.optLong("created"), s("p_name"),
                            listOf(s("p_brand"), s("p_model"), s("p_engine")).filter { it.isNotBlank() }.joinToString(" "),
                        )
                    }
                }
            }
        }
        found
    } catch (_: Exception) {
        null
    }
}
