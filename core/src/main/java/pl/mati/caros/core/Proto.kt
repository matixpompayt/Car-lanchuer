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
 *  - na nieznaną komendę radio odpowiada "nack" (z nazwą komendy) – telefon pokazuje „zaktualizuj CAR OS”,
 *  - komenda z "mid" (id wiadomości) dostaje potwierdzenie "ack" po wykonaniu (ok / błąd + opis);
 *    radio pomija powtórki tego samego "mid", a komendy „na żywo” (ostrzeżenia) starsze niż [LIVE_MAX_MS] – po czasie są bez sensu.
 * Starsze CAR OS (1.x) nie wysyłają "caps" – wtedy obsługiwane komendy wynikają z numeru wersji ("ver").
 */
object Proto {
    /** Wersja protokołu tej wersji aplikacji. */
    const val VERSION = 2

    const val TYPE = "type"
    const val PV = "pv"
    const val APP = "app"
    const val CAPS = "caps"
    /** Id komendy – radio odsyła je w "ack". */
    const val MID = "mid"
    /** Kiedy telefon wysłał komendę (ms). */
    const val SENT = "st"
    /** Ostrzeżenia z telefonu dłużej w drodze (np. radio było wyłączone) – pomijane. */
    const val LIVE_MAX_MS = 3 * 60_000L

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
        /** Korekta zasięgu: {"km": zasięg z licznika} albo {"k": mnożnik} (1 = bez korekty). */
        const val FUEL_RK = "fuel_rk"
        const val SVC_ITEM = "svc_item"
        const val SVC_DONE = "svc_done"
        const val SVC_ODO = "svc_odo"
        const val SYNC = "sync"
        const val RESTORE_PART = "rs"
        /** Wygląd (motyw, Liquid Glass, akcent) – patrz [Look]. */
        const val LOOK = "look"
        /** Jazda na żywo przez link: "k" – klucz ([LiveShare.keyText]), "min" – na ile minut (0 = do celu). */
        const val SHARE = "share"
        const val SHARE_END = "share_end"
    }

    /** Komendy „na żywo” – bez potwierdzeń, po [LIVE_MAX_MS] nieaktualne. */
    val LIVE_CMDS = setOf(Cmd.ALERT, Cmd.ALERT_END, Cmd.CAMSAM, Cmd.CAMSAM_HUD)

    /** Wiadomości auto → telefon. */
    object Msg {
        const val STATUS = "status"
        const val PARK = "park"
        const val TRIP = "trip"
        const val LIVE = "live"
        const val REPORT = "rp"
        const val BACKUP_PART = "bk"
        const val NACK = "nack"
        const val ACK = "ack"
    }

    /** Funkcje (nie osobne komendy), które telefon może sprawdzić w "caps". */
    object Feat {
        /** Do 8 przystanków na trasie (wcześniej 3). */
        const val STOPS8 = "stops8"
        /** Dowolnie dużo przystanków (do [MAX_STOPS]) – stacje i postoje z planu bez limitu 8. */
        const val STOPS_MANY = "stops_many"
        /** Duże komendy w częściach ([Parts]) – przekaźnik przyjmuje do 4 KB na wiadomość. */
        const val PARTS = "parts"
        /** Status z litrami w baku ("fuel.left"). */
        const val FUEL_LEFT = "fuel_left"
        /** Odpowiedź "nack" na nieznaną komendę. */
        const val NACK = "nack"
        /** Potwierdzenia "ack" komend z "mid". */
        const val ACK = "ack"
        /** cfg z kluczem Tankerkönig ("tk") – ceny na stacjach w Niemczech także w aucie. */
        const val CFG_TK = "cfg_tk"
        /** Status z "fuel.src": "can" – poziom paliwa z czujnika auta, "est" – szacunek z tankowań. */
        const val FUEL_SRC = "fuel_src"
        /** Status z "look" i komenda "look". */
        const val LOOK = "look"
        /** "park" z "dest" – cel trasy, do którego zostało dojść pieszo (ostatnia mila). */
        const val PARK_DEST = "park_dest"
    }

    /** Komendy obsługiwane przez CAR OS 1.x (przed "caps"). */
    private val V1_BASE = setOf(
        Cmd.ROUTE, Cmd.FAV_ADD, Cmd.FAV_DEL, Cmd.CFG, Cmd.ALERT, Cmd.ALERT_END, Cmd.CAMSAM, Cmd.CAMSAM_HUD,
        Cmd.PLACE_SET, Cmd.FUEL_ADD, Cmd.FUEL_DEL, Cmd.SVC_ITEM, Cmd.SVC_DONE, Cmd.SVC_ODO, Cmd.SYNC, Cmd.RESTORE_PART,
    )

    /** Wszystko, co obsługuje CAR OS z tym protokołem. */
    val CAR_CAPS: Set<String> = V1_BASE + setOf(
        Cmd.FUEL_TANK, Cmd.FUEL_RK, Cmd.LOOK, Cmd.SHARE, Cmd.SHARE_END,
        Feat.STOPS8, Feat.FUEL_LEFT, Feat.NACK, Feat.ACK, Feat.CFG_TK, Feat.FUEL_SRC, Feat.LOOK, Feat.PARK_DEST,
        Feat.STOPS_MANY, Feat.PARTS,
    )

    /** Najwięcej punktów po drodze w jednej trasie (praktycznie bez limitu; OSRM i TomTom przyjmują więcej). */
    const val MAX_STOPS = 100

    /** Druga strona połączenia: wersja protokołu, wersja aplikacji, obsługiwane komendy. */
    data class Peer(val pv: Int, val app: String?, val caps: Set<String>) {
        fun supports(cap: String) = cap in caps
        /** Druga strona ma starszy protokół niż ta aplikacja. */
        val older get() = pv < VERSION
        /** Druga strona ma nowszy protokół – zaktualizuj tę aplikację. */
        val newer get() = pv > VERSION
    }

    /** Dopisuje wersję protokołu i aplikacji do wysyłanej wiadomości. */
    fun stamp(o: JSONObject, app: String): JSONObject = o.put(PV, VERSION).put(APP, app).also { if (!it.has(SENT)) it.put(SENT, System.currentTimeMillis()) }

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

    /** Odpowiedź radia na nieznaną komendę (z "mid", jeśli komenda go miała). */
    fun nack(type: String, app: String, mid: String = ""): JSONObject =
        stamp(JSONObject().put(TYPE, Msg.NACK).put("of", type).also { if (mid.isNotBlank()) it.put(MID, mid) }, app)

    /** Potwierdzenie wykonania komendy [mid]: [ok] – udało się, [text] – co zrobiło radio albo opis błędu. */
    fun ack(type: String, mid: String, ok: Boolean, text: String?, app: String): JSONObject =
        stamp(JSONObject().put(TYPE, Msg.ACK).put("of", type).put(MID, mid).put("ok", ok).put("r", text ?: ""), app)

    /** Potwierdzenie z radia (odczytane po stronie telefonu). */
    data class Ack(val mid: String, val of: String, val ok: Boolean, val text: String, val known: Boolean = true)

    fun ackFrom(o: JSONObject): Ack? {
        val mid = o.optString(MID).ifBlank { return null }
        return when (o.optString(TYPE)) {
            Msg.ACK -> Ack(mid, o.optString("of"), o.optBoolean("ok", true), o.optString("r"))
            Msg.NACK -> Ack(mid, o.optString("of"), false, "", known = false)
            else -> null
        }
    }

    private val rnd = java.security.SecureRandom()

    /** Nowe id komendy (10 znaków hex). */
    fun newMid(): String = ByteArray(5).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Komenda „na żywo”, która przyszła za późno (np. radio było wyłączone). */
    fun isStaleLive(o: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        if (o.optString(TYPE) !in LIVE_CMDS) return false
        val st = o.optLong(SENT, 0L)
        return st > 0 && now - st > LIVE_MAX_MS
    }
}
