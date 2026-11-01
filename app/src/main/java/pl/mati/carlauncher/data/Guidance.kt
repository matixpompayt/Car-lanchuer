package pl.mati.carlauncher.data

import android.content.Context
import android.location.Location
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Własne prowadzenie CAR OS: manewry z trasy OSRM, baner ze strzałką i odległością,
 * komunikaty głosowe po polsku (syntezator mowy Androida).
 */
object Guidance {

    /** Manewr na trasie. [alongM] = odległość od startu trasy do manewru. */
    data class Step(
        val type: String,
        val modifier: String?,
        val name: String,
        val ref: String,
        val exit: Int?,
        val destinations: String,
        val lat: Double,
        val lon: Double,
        val alongM: Double,
    )

    /** Co pokazuje baner. [kind] wybiera strzałkę. */
    data class State(
        val stepIndex: Int,
        val kind: String,
        val action: String,
        val street: String,
        val distanceM: Double,
        val exit: Int?,
        val thenKind: String?,
        val arrived: Boolean = false,
    )

    val state = MutableStateFlow<State?>(null)

    private val spoken = HashSet<String>()
    private var lastRouteId = 0L
    private var arrivedSaid = false

    // ---------- kroki z OSRM ----------

    fun parseSteps(route: JSONObject, pts: List<org.osmdroid.util.GeoPoint>, cum: DoubleArray): List<Step> {
        val out = ArrayList<Step>()
        val legs = route.optJSONArray("legs") ?: return out
        var from = 0
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).optJSONArray("steps") ?: continue
            for (si in 0 until steps.length()) {
                val s = steps.getJSONObject(si)
                val m = s.optJSONObject("maneuver") ?: continue
                val loc = m.optJSONArray("location") ?: continue
                val lon = loc.getDouble(0)
                val lat = loc.getDouble(1)
                // najbliższy punkt trasy (szukamy do przodu – trasa może wracać tą samą drogą)
                var best = Double.MAX_VALUE
                var idx = from
                val end = minOf(pts.size, from + 4000)
                for (k in from until end) {
                    val d = RouteRepository.dist(lat, lon, pts[k].latitude, pts[k].longitude)
                    if (d < best) { best = d; idx = k }
                    if (best < 3 && d > 200) break
                }
                from = idx
                out += Step(
                    type = m.optString("type"),
                    modifier = m.optString("modifier").ifBlank { null },
                    name = s.optString("name"),
                    ref = s.optString("ref"),
                    exit = if (m.has("exit")) m.optInt("exit") else null,
                    destinations = s.optString("destinations"),
                    lat = lat, lon = lon,
                    alongM = cum[idx],
                )
            }
        }
        return out
    }

    // ---------- teksty po polsku ----------

    private fun dirPl(mod: String?): String = when (mod) {
        "left" -> "w lewo"
        "right" -> "w prawo"
        "slight left" -> "lekko w lewo"
        "slight right" -> "lekko w prawo"
        "sharp left" -> "ostro w lewo"
        "sharp right" -> "ostro w prawo"
        "uturn" -> "zawróć"
        else -> "prosto"
    }

    private val ORD = listOf("", "pierwszym", "drugim", "trzecim", "czwartym", "piątym", "szóstym", "siódmym", "ósmym")

    /** Czy manewr wymaga komunikatu (jazda prosto / zmiana nazwy ulicy – nie). */
    fun silent(s: Step): Boolean =
        s.type == "depart" || s.type == "new name" || s.type == "notification" ||
            s.type == "exit roundabout" || s.type == "exit rotary" ||
            ((s.type == "continue" || s.type == "turn") && (s.modifier == null || s.modifier == "straight"))

    fun kind(s: Step): String = when (s.type) {
        "arrive" -> "arrive"
        "roundabout", "rotary" -> "roundabout"
        "merge" -> "merge"
        "fork" -> if (s.modifier?.contains("left") == true) "fork left" else "fork right"
        "on ramp", "off ramp" -> if (s.modifier?.contains("left") == true) "ramp left" else "ramp right"
        else -> when (s.modifier) {
            "uturn" -> "uturn"
            null, "straight" -> "straight"
            else -> s.modifier
        }
    }

    /** Krótka nazwa czynności (baner + głos). */
    fun action(s: Step): String {
        val d = dirPl(s.modifier)
        return when (s.type) {
            "arrive" -> when (s.modifier) {
                "left" -> "Cel po lewej"
                "right" -> "Cel po prawej"
                else -> "Jesteś na miejscu"
            }
            "roundabout", "rotary", "roundabout turn" ->
                if (s.exit != null && s.exit in 1..8) "Na rondzie zjedź ${ORD[s.exit]} zjazdem" else "Na rondzie jedź $d"
            "merge" -> "Włącz się do ruchu" + if (s.modifier?.contains("left") == true) " z lewej" else ""
            "fork" -> if (s.modifier?.contains("left") == true) "Trzymaj się lewej" else "Trzymaj się prawej"
            "on ramp" -> "Wjedź " + d.removePrefix("lekko ")
            "off ramp" -> "Zjedź " + d.removePrefix("lekko ")
            "end of road" -> "Na końcu drogi skręć $d"
            else -> if (s.modifier == "uturn") "Zawróć" else if (s.modifier == null || s.modifier == "straight") "Jedź prosto" else "Skręć $d"
        }
    }

    fun street(s: Step): String {
        val ref = s.ref.split(";").firstOrNull()?.trim().orEmpty()
        return when {
            (s.type == "on ramp" || s.type == "off ramp") && s.destinations.isNotBlank() -> s.destinations.split(",").first().trim()
            s.name.isNotBlank() && ref.isNotBlank() && !s.name.contains(ref) -> "$ref · ${s.name}"
            s.name.isNotBlank() -> s.name
            else -> ref
        }
    }

    private fun spokenStreet(s: Step): String {
        val st = street(s).substringBefore(" · ")
        if (st.isBlank() || s.type == "arrive") return ""
        return when (s.type) {
            "on ramp" -> " na $st"
            "off ramp" -> " w kierunku $st"
            "roundabout", "rotary" -> " w $st"
            else -> " w $st"
        }
    }

    /** „300 metrów”, „kilometr”, „półtora kilometra”, „2 kilometry”, „5 kilometrów”. */
    fun distancePl(m: Double): String {
        if (m < 950) {
            val r = if (m < 100) ((m / 10).roundToInt() * 10) else ((m / 50).roundToInt() * 50)
            return "${r.coerceAtLeast(10)} metrów"
        }
        val km = (m / 500).roundToInt() / 2.0
        return when {
            km == 1.0 -> "kilometr"
            km == 1.5 -> "półtora kilometra"
            km % 1.0 != 0.0 -> "${km.toString().replace('.', ',')} kilometra"
            km.toInt() in 2..4 -> "${km.toInt()} kilometry"
            else -> "${km.toInt()} kilometrów"
        }
    }

    fun distanceShort(m: Double): String =
        if (m < 1000) "${((m / 10).roundToInt() * 10).coerceAtLeast(0)} m"
        else String.format(Locale("pl"), "%.1f km", m / 1000)

    // ---------- prowadzenie ----------

    /** Start nowej trasy (także po przeliczeniu). */
    fun begin(ctx: Context, r: RouteRepository.Route, rerouted: Boolean) {
        NavVoice.init(ctx)
        if (r.id != lastRouteId) {
            lastRouteId = r.id
            spoken.clear()
            arrivedSaid = false
        }
        if (rerouted) NavVoice.say("Wyznaczono nową trasę")
        else NavVoice.say(
            "Trasa do ${r.dest.name}. ${distancePl(r.distanceM)}, przyjazd około ${
                java.text.SimpleDateFormat("H:mm", Locale("pl")).format(java.util.Date(System.currentTimeMillis() + (r.durationS * 1000).toLong()))
            }",
        )
    }

    fun end() {
        state.value = null
        spoken.clear()
        lastRouteId = 0L
    }

    /** Co sekundę: [along] = przejechane metry trasy. */
    fun update(r: RouteRepository.Route, along: Double, loc: Location) {
        val steps = r.steps
        if (steps.isEmpty()) {
            state.value = null
            return
        }
        val i = steps.indexOfFirst { it.alongM > along + 4 && it.type != "depart" }
        if (i < 0 || steps[i].type == "arrive" && steps[i].alongM - along < 25) {
            state.value = State(steps.lastIndex, "arrive", "Jesteś na miejscu", r.dest.name, 0.0, null, null, arrived = true)
            if (!arrivedSaid) {
                arrivedSaid = true
                NavVoice.say("Jesteś na miejscu")
            }
            return
        }
        // pomiń „ciche” manewry (prosto, zmiana nazwy) przy wyborze tego, co pokazać
        var shown = i
        while (shown < steps.lastIndex && silent(steps[shown])) shown++
        val s = steps[shown]
        val dist = (s.alongM - along).coerceAtLeast(0.0)
        val next = steps.getOrNull(shown + 1)?.takeIf { !silent(it) && it.alongM - s.alongM < 150 }
        state.value = State(shown, kind(s), action(s), street(s), dist, s.exit, next?.let { kind(it) })

        // głos
        val v = if (loc.hasSpeed()) loc.speed.toDouble().coerceAtLeast(8.0) else 13.0
        val far = (v * 25).coerceIn(350.0, 1500.0)
        val near = (v * 8).coerceIn(90.0, 320.0)
        val now = (v * 3 + 15).coerceIn(25.0, 80.0)
        val then = next?.let { ", a potem " + action(it).replaceFirstChar { c -> c.lowercase() } } ?: ""
        val key = "${r.id}:$shown"
        when {
            dist <= now -> if (spoken.add("$key:now")) NavVoice.say(action(s) + spokenStreet(s) + then)
            dist <= near -> if (spoken.add("$key:near")) NavVoice.say("Za ${distancePl(dist)} " + action(s).replaceFirstChar { it.lowercase() } + spokenStreet(s))
            dist <= far && dist > near + 150 && s.alongM - (steps.getOrNull(shown - 1)?.alongM ?: 0.0) > near + 200 ->
                if (spoken.add("$key:far")) NavVoice.say("Za ${distancePl(dist)} " + action(s).replaceFirstChar { it.lowercase() } + spokenStreet(s))
        }
    }
}

/** Głos nawigacji: syntezator mowy Androida po polsku, muzyka przyciszana na czas komunikatu. */
object NavVoice : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var appCtx: Context? = null
    private var focus: AudioFocusRequest? = null
    private var pending: String? = null

    /** Problem z głosem (np. brak polskiego) – do pokazania w ustawieniach. */
    val problem = MutableStateFlow<String?>(null)

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun init(ctx: Context) {
        if (tts != null) return
        appCtx = ctx.applicationContext
        tts = TextToSpeech(ctx.applicationContext, this)
    }

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            problem.value = "Syntezator mowy nie działa – zainstaluj „Usługi mowy Google” ze Sklepu Play"
            return
        }
        val r = t.setLanguage(Locale("pl", "PL"))
        ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        problem.value = if (ready) null else "Brak polskiego głosu – Ustawienia Androida → Zamiana tekstu na mowę → zainstaluj polski"
        t.setAudioAttributes(attrs)
        t.setSpeechRate(1.05f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = release()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release()
        })
        pending?.let { pending = null; say(it) }
    }

    fun say(text: String, force: Boolean = false) {
        if (!force && !Prefs.navVoice.value) return
        val t = tts
        if (t == null || !ready) {
            pending = text
            return
        }
        val am = appCtx?.getSystemService(AudioManager::class.java)
        if (am != null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
            focus = req
            try { am.requestAudioFocus(req) } catch (_: Exception) {}
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "nav${System.nanoTime()}")
    }

    private fun release() {
        val am = appCtx?.getSystemService(AudioManager::class.java) ?: return
        focus?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focus = null
    }
}
