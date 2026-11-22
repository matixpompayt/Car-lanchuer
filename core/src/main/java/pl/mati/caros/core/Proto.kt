package pl.mati.caros.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Protokół wymiany CAR OS (radio) ↔ CAR OS Connect (telefon) przez szyfrowany przekaźnik.
 *
 * Każda wiadomość to JSON z polem "type". Od wersji 2:
 *  - nadawca dopisuje "pv" (wersja protokołu) i "app" (wersja aplikacji),
 *  - status z radia niesie "caps" – komendy i funkcje, które to CAR OS obsługuje;
 *    telefon sprawdza je przed wysłaniem (np. pojemność baku, 8 przystanków),
 *  - na nieznaną komendę radio odpowiada "nack" (z nazwą komendy) – telefon pokazuje „zaktualizuj CAR OS”.
 * Starsze CAR OS (1.x) nie wysyłają "caps" – wtedy obsługiwane komendy wynikają z numeru wersji ("ver").
 */
object Proto {
    /** Wersja protokołu tej wersji aplikacji. */
    const val VERSION = 2

    const val TYPE = "type"
    const val PV = "pv"
    const val APP = "app"
    const val CAPS = "caps"

    /** Komendy telefon → auto. */
    object Cmd {
        const val ROUTE = "route"
        const val FAV_ADD = "fav_add"
        const val FAV_DEL = "fav_del"
        const val CFG = "cfg"
        const val ALERT = "alert"
        const val ALERT_END = "alert_end"
        const val CAMSAM = "camsam"
        const val CAMSAM_HUD = "camsam_hud"
        const val PLACE_SET = "place_set"
        const val FUEL_ADD = "fuel_add"
        const val FUEL_DEL = "fuel_del"
        const val FUEL_TANK = "fuel_tank"
        const val SVC_ITEM = "svc_item"
        const val SVC_DONE = "svc_done"
        const val SVC_ODO = "svc_odo"
        const val SYNC = "sync"
        const val RESTORE_PART = "rs"
    }

    /** Wiadomości auto → telefon. */
    object Msg {
        const val STATUS = "status"
        const val PARK = "park"
        const val TRIP = "trip"
        const val LIVE = "live"
        const val REPORT = "rp"
        const val BACKUP_PART = "bk"
        const val NACK = "nack"
    }

    /** Funkcje (nie osobne komendy), które telefon może sprawdzić w "caps". */
    object Feat {
        /** Do 8 przystanków na trasie (wcześniej 3). */
        const val STOPS8 = "stops8"
        /** Status z litrami w baku ("fuel.left"). */
        const val FUEL_LEFT = "fuel_left"
        /** Odpowiedź "nack" na nieznaną komendę. */
        const val NACK = "nack"
    }

    /** Komendy obsługiwane przez CAR OS 1.x (przed "caps"). */
    private val V1_BASE = setOf(
        Cmd.ROUTE, Cmd.FAV_ADD, Cmd.FAV_DEL, Cmd.CFG, Cmd.ALERT, Cmd.ALERT_END, Cmd.CAMSAM, Cmd.CAMSAM_HUD,
        Cmd.PLACE_SET, Cmd.FUEL_ADD, Cmd.FUEL_DEL, Cmd.SVC_ITEM, Cmd.SVC_DONE, Cmd.SVC_ODO, Cmd.SYNC, Cmd.RESTORE_PART,
    )

    /** Wszystko, co obsługuje CAR OS z tym protokołem. */
    val CAR_CAPS: Set<String> = V1_BASE + setOf(Cmd.FUEL_TANK, Feat.STOPS8, Feat.FUEL_LEFT, Feat.NACK)

    /** Druga strona połączenia: wersja protokołu, wersja aplikacji, obsługiwane komendy. */
    data class Peer(val pv: Int, val app: String?, val caps: Set<String>) {
        fun supports(cap: String) = cap in caps
        /** Druga strona ma starszy protokół niż ta aplikacja. */
        val older get() = pv < VERSION
        /** Druga strona ma nowszy protokół – zaktualizuj tę aplikację. */
        val newer get() = pv > VERSION
    }

    /** Dopisuje wersję protokołu i aplikacji do wysyłanej wiadomości. */
    fun stamp(o: JSONObject, app: String): JSONObject = o.put(PV, VERSION).put(APP, app)

    fun capsJson(caps: Set<String> = CAR_CAPS): JSONArray = JSONArray().also { a -> caps.sorted().forEach { a.put(it) } }

    /**
     * Co obsługuje CAR OS, który przysłał ten status. Bez "caps" (CAR OS 1.x) – z numeru wersji:
     * 1.139+ do 8 przystanków, 1.140+ pojemność baku z telefonu i litry w baku.
     */
    fun carFromStatus(o: JSONObject): Peer {
        val app = o.optString("ver").ifBlank { null }
        val a = o.optJSONArray(CAPS)
        if (a != null) {
            val caps = HashSet<String>()
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { caps += it }
            return Peer(o.optInt(PV, 2), app, caps)
        }
        val build = app?.let { buildOf(it) } ?: 0
        val caps = HashSet(V1_BASE)
        if (build >= 139) caps += Feat.STOPS8
        if (build >= 140) { caps += Cmd.FUEL_TANK; caps += Feat.FUEL_LEFT }
        return Peer(1, app, caps)
    }

    /** Wersja telefonu z komendy (bez "pv" = Connect 1.x). */
    fun phoneFrom(o: JSONObject): Peer = Peer(o.optInt(PV, 1), o.optString(APP).ifBlank { null }, emptySet())

    /** Numer builda z nazwy wersji: „1.140” → 140, „2.0-beta.3” → 0 (beta zawsze ma "caps"). */
    fun buildOf(ver: String): Int {
        if (ver.contains("beta")) return 0
        return ver.substringAfterLast('.').takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

    /** Odpowiedź radia na nieznaną komendę. */
    fun nack(type: String, app: String): JSONObject = stamp(JSONObject().put(TYPE, Msg.NACK).put("of", type), app)
}
