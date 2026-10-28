package pl.mati.carlauncher.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import pl.mati.carlauncher.BuildConfig

/** Kopia zapasowa wszystkich ustawień (profil, wygląd, serwis, dziennik tras) do pliku JSON. */
object BackupManager {

    fun export(ctx: Context, uri: Uri): Boolean {
        val sp = Prefs.store ?: return false
        TripRepository.trips.value // upewnij się, że dane są wczytane
        ServiceRepository.save()
        return try {
            val data = JSONObject()
            for ((k, v) in sp.all) {
                if (k == "trip_current") continue
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
            val root = JSONObject()
                .put("app", "CAR OS")
                .put("version", BuildConfig.VERSION_NAME)
                .put("created", System.currentTimeMillis())
                .put("prefs", data)
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(2).toByteArray()) } ?: return false
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Wczytuje kopię. Po sukcesie aplikacja powinna się zrestartować. */
    fun import(ctx: Context, uri: Uri): Boolean {
        val sp = Prefs.store ?: return false
        return try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
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
            ed.commit()
        } catch (_: Exception) {
            false
        }
    }
}
