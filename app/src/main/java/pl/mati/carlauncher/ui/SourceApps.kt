package pl.mati.carlauncher.ui

import android.content.Context
import android.content.Intent
import pl.mati.carlauncher.data.Prefs

/**
 * Fabryczne aplikacje radia: tuner FM/AM i muzyka Bluetooth.
 * Każdy producent (FYT, MTCD, TS…) ma inne pakiety – wykrywamy po pakiecie i nazwie,
 * a gdy się nie uda, użytkownik wybiera ręcznie w Ustawieniach → Dźwięk.
 */
object SourceApps {

    enum class Kind { RADIO, BT }

    private val RADIO_PKGS = listOf(
        "com.syu.radio", "com.fyt.radio", "com.android.fmradio", "com.microntek.radio", "com.ts.radio",
        "com.qf.radio", "com.hct.radio", "com.tw.radio", "com.nwd.radio", "com.android.radio", "com.spd.radio",
        "com.zxw.radio", "com.car.radio", "com.ts.tsradio", "com.hzbhd.radio",
    )
    private val BT_PKGS = listOf(
        "com.syu.bt", "com.syu.btmusic", "com.microntek.bluetooth", "com.ts.bt", "com.tw.bt", "com.nwd.bt.music",
        "com.hct.bluetooth", "com.qf.bt", "com.zxw.bt", "com.hzbhd.bt", "com.car.bluetooth", "com.android.bluetooth.music",
    )
    private val RADIO_LABEL = Regex("(^|\\b)(radio|radyjko|fm|tuner)(\\b|$)", RegexOption.IGNORE_CASE)
    private val BT_LABEL = Regex("(bt ?music|bt ?audio|muzyka ?bt|bluetooth ?(music|audio|muzyka)|a2dp|^bt$|^bluetooth$)", RegexOption.IGNORE_CASE)

    /** "pakiet/aktywność" aplikacji danego rodzaju albo null. */
    fun resolve(ctx: Context, kind: Kind): String? {
        val chosen = if (kind == Kind.RADIO) Prefs.radioApp.value else Prefs.btApp.value
        if (chosen.isNotBlank()) return chosen
        return detect(ctx, kind)
    }

    fun detect(ctx: Context, kind: Kind): String? {
        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val pkgs = if (kind == Kind.RADIO) RADIO_PKGS else BT_PKGS
        // 1) znane pakiety producentów
        for (p in pkgs) {
            val ri = launchables.firstOrNull { it.activityInfo.packageName == p } ?: continue
            return ri.activityInfo.packageName + "/" + ri.activityInfo.name
        }
        // 2) po nazwie widocznej w systemie
        val re = if (kind == Kind.RADIO) RADIO_LABEL else BT_LABEL
        val hit = launchables.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().trim()
            re.containsMatchIn(label) && ri.activityInfo.packageName != ctx.packageName &&
                !label.contains("spotify", true) && !label.contains("youtube", true)
        }
        return hit?.let { it.activityInfo.packageName + "/" + it.activityInfo.name }
    }

    fun label(ctx: Context, comp: String?): String? {
        if (comp == null) return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(comp.substringBefore("/"), 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun open(ctx: Context, kind: Kind): Boolean {
        val comp = resolve(ctx, kind) ?: return false
        // Bluetooth: od razu zakładka z muzyką (osobna aktywność w aplikacji radia), a nie „Połącz”
        if (kind == Kind.BT && openBtMusic(ctx, comp.substringBefore("/"), comp.substringAfter("/"))) return true
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(comp.substringBefore("/"), comp.substringAfter("/"))
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return Launch.start(ctx, i)
    }

    private val MUSIC_ACT = Regex("(music|a2dp|avrcp|btaudio|media|player|song)", RegexOption.IGNORE_CASE)

    /** Szuka w aplikacji BT aktywności z muzyką (np. com.ts.bt…BtMusicActivity) i ją otwiera. */
    private fun openBtMusic(ctx: Context, pkg: String, main: String): Boolean {
        val acts = try {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        // tylko muzyka BLUETOOTH (w TopWay radio, USB i BT to jedna aplikacja – nie otwieraj odtwarzacza USB)
        val btName = Regex("(bt|blue|a2dp|avrcp)", RegexOption.IGNORE_CASE)
        val cands = acts.filter {
            val n = it.name.lowercase()
            it.exported && it.name != main && MUSIC_ACT.containsMatchIn(n.substringAfterLast('.')) &&
                (btName.containsMatchIn(n.substringAfterLast('.')) || btName.containsMatchIn(n.substringBeforeLast('.').substringAfterLast('.')))
        }
            .sortedByDescending { (if (it.name.contains("bt", true)) 2 else 0) + (if (it.name.contains("music", true)) 1 else 0) }
        for (a in cands) {
            val i = Intent(Intent.ACTION_MAIN).setClassName(pkg, a.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (Launch.start(ctx, i)) return true
        }
        return false
    }

    fun pkgOf(ctx: Context, kind: Kind): String? = resolve(ctx, kind)?.substringBefore("/")
}
