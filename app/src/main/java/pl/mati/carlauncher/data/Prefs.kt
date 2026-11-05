package pl.mati.carlauncher.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow

/** Profil kierowcy i auta z kreatora pierwszego uruchomienia. */
data class Profile(
    val name: String = "",
    val brand: String = "Volkswagen",
    val model: String = "",
    val engine: String = "",
    val year: String = "",
    val fuel: String = "Diesel",
    val home: String = "",
    val work: String = "",
    val setupDone: Boolean = false,
    /** Współrzędne domu / pracy (np. ustawione z telefonu) – trasa bez szukania adresu. */
    val homeLat: Double? = null,
    val homeLon: Double? = null,
    val workLat: Double? = null,
    val workLon: Double? = null,
) {
    val carName: String
        get() = listOf(brand, model, engine).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Mój samochód" }
    val shortCar: String
        get() = listOf(model, engine).filter { it.isNotBlank() }.joinToString(" ")
    val isDiesel: Boolean get() = fuel == "Diesel"
    val rpmMax: Int get() = if (isDiesel) 5000 else 7000
    val rpmRed: Int get() = if (isDiesel) 4200 else 6200
}

object Prefs {
    const val DEFAULT_ACCENT = 0xFF2FD3F0.toInt()

    private var sp: SharedPreferences? = null

    val accent = MutableStateFlow(DEFAULT_ACCENT)
    val obdMac = MutableStateFlow<String?>(null)
    val profile = MutableStateFlow(Profile())
    val wallpaper = MutableStateFlow(0)
    val autoNight = MutableStateFlow(true)
    val nightNow = MutableStateFlow(false)
    val bootAnim = MutableStateFlow(true)
    val autoUpdate = MutableStateFlow(true)
    val updateWifiOnly = MutableStateFlow(true)
    val reverseCam = MutableStateFlow(true)
    val mapsInBackground = MutableStateFlow(true)
    val roadAlerts = MutableStateFlow(true)
    val homeTiles = MutableStateFlow("speed,coolant,boost")
    /** Układ ekranu głównego: udział mapy w szerokości, mapa po prawej, widoczność mapy i muzyki. */
    val homeSplit = MutableStateFlow(0.6f)
    /** Testowy wygląd 2.0 ekranu głównego (styl minimalistyczny). */
    val ui2 = MutableStateFlow(false)
    val homeSwap = MutableStateFlow(false)
    val homeShowMap = MutableStateFlow(true)
    val homeShowMedia = MutableStateFlow(true)
    val autoResumeMusic = MutableStateFlow(true)
    val suggestHome = MutableStateFlow(true)
    val homeFrom = MutableStateFlow(14)
    val homeTo = MutableStateFlow(19)
    val homeWeekdaysOnly = MutableStateFlow(true)
    /** Wybrane aplikacje źródeł: "pakiet/aktywność", pusto = wykrywanie automatyczne. */
    val radioApp = MutableStateFlow("")
    val btApp = MutableStateFlow("")
    val animations = MutableStateFlow(true)
    /** Nie pozwalaj wysuwać systemowego paska / zasłony powiadomień nad CAR OS. */
    val blockStatusBar = MutableStateFlow(true)
    /** Tryb dzień/noc: "lights" (światła auta), "sun", "light", "dark". */
    val themeMode = MutableStateFlow("lights")
    /** Jasny wariant: "perla", "piasek", "mgla". */
    val lightVariant = MutableStateFlow("perla")
    /** Jasność ekranu (%) w dzień i w nocy (-1 = jeszcze nie zapamiętana). */
    val briDay = MutableStateFlow(-1)
    val briNight = MutableStateFlow(35)
    /** Mapa: "vector" = OpenFreeMap 3D (MapLibre), "osm" = klasyczne kafelki OSM. */
    val mapEngine = MutableStateFlow("vector")
    val map3d = MutableStateFlow(true)
    val mapHeadingUp = MutableStateFlow(true)
    /** Przyciąganie pozycji auta do drogi na mapie. */
    val mapSnap = MutableStateFlow(true)
    /** Światła, STOP, ustąp, przejścia, przejazdy, fotoradary na mapie (OSM). */
    val mapSigns = MutableStateFlow(true)
    val turboTimer = MutableStateFlow(true)
    /** Kto prowadzi: "caros" (własna nawigacja z głosem) albo "google" (Mapy Google w tle). */
    val navEngine = MutableStateFlow("caros")
    val navVoice = MutableStateFlow(true)
    /** Pozycja auta na żywo w CAR OS Connect (gdy auto jedzie). */
    val shareLive = MutableStateFlow(true)
    /** Ile razy licznik rozciąga obraz z radia w poziomie (1 = bez dopasowania). */
    val clusterStretch = MutableStateFlow(1f)
    /** Powiększenie przycisków i napisów na mapie pełnoekranowej (licznik). */
    val clusterUiScale = MutableStateFlow(1.5f)
    /** Ikona auta na mapie: typ nadwozia i kolor lakieru. */
    val carBody = MutableStateFlow("minivan")
    val carPaint = MutableStateFlow(0xFFC3C8CF.toInt())
    val speedLimits = MutableStateFlow(true)
    val speedTolerance = MutableStateFlow(5)
    val speedBeep = MutableStateFlow(false)

    /** Surowy dostęp dla repozytoriów (trasy, serwis) i kopii zapasowej. */
    val store: SharedPreferences? get() = sp

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences("car_launcher", Context.MODE_PRIVATE)
        sp = p
        accent.value = p.getInt("accent", DEFAULT_ACCENT)
        obdMac.value = p.getString("obd_mac", null)
        wallpaper.value = p.getInt("wallpaper", 0)
        autoNight.value = p.getBoolean("auto_night", true)
        nightNow.value = p.getBoolean("night_now", false)
        bootAnim.value = p.getBoolean("boot_anim", true)
        autoUpdate.value = p.getBoolean("auto_update", true)
        updateWifiOnly.value = p.getBoolean("update_wifi_only", true)
        reverseCam.value = p.getBoolean("reverse_cam", true)
        mapsInBackground.value = p.getBoolean("maps_bg", true)
        roadAlerts.value = p.getBoolean("road_alerts", true)
        homeTiles.value = p.getString("home_tiles", "speed,coolant,boost") ?: "speed,coolant,boost"
        homeSplit.value = p.getFloat("home_split", 0.6f)
        ui2.value = p.getBoolean("ui2", false)
        homeSwap.value = p.getBoolean("home_swap", false)
        homeShowMap.value = p.getBoolean("home_map", true)
        homeShowMedia.value = p.getBoolean("home_media", true)
        autoResumeMusic.value = p.getBoolean("auto_music", true)
        suggestHome.value = p.getBoolean("suggest_home", true)
        homeFrom.value = p.getInt("home_from", 14)
        homeTo.value = p.getInt("home_to", 19)
        homeWeekdaysOnly.value = p.getBoolean("home_weekdays", true)
        radioApp.value = p.getString("radio_app", "") ?: ""
        btApp.value = p.getString("bt_app", "") ?: ""
        animations.value = p.getBoolean("animations", true)
        blockStatusBar.value = p.getBoolean("block_status_bar", true)
        themeMode.value = p.getString("theme_mode", "lights") ?: "lights"
        lightVariant.value = p.getString("light_variant", "perla") ?: "perla"
        briDay.value = p.getInt("bri_day", -1)
        briNight.value = p.getInt("bri_night", 35)
        mapEngine.value = p.getString("map_engine", "vector") ?: "vector"
        map3d.value = p.getBoolean("map_3d", true)
        mapHeadingUp.value = p.getBoolean("map_heading_up", true)
        mapSnap.value = p.getBoolean("map_snap", true)
        mapSigns.value = p.getBoolean("map_signs", true)
        turboTimer.value = p.getBoolean("turbo_timer", true)
        navEngine.value = p.getString("nav_engine", "caros") ?: "caros"
        navVoice.value = p.getBoolean("nav_voice", true)
        shareLive.value = p.getBoolean("share_live", true)
        clusterStretch.value = p.getFloat("cluster_stretch", 1f)
        clusterUiScale.value = p.getFloat("cluster_ui", 1.5f)
        carBody.value = p.getString("car_body", "minivan") ?: "minivan"
        carPaint.value = p.getInt("car_paint", 0xFFC3C8CF.toInt())
        speedLimits.value = p.getBoolean("speed_limits", true)
        speedTolerance.value = p.getInt("speed_tol", 5)
        speedBeep.value = p.getBoolean("speed_beep", false)
        profile.value = Profile(
            name = p.getString("p_name", "") ?: "",
            brand = p.getString("p_brand", "Volkswagen") ?: "Volkswagen",
            model = p.getString("p_model", "") ?: "",
            engine = p.getString("p_engine", "") ?: "",
            year = p.getString("p_year", "") ?: "",
            fuel = p.getString("p_fuel", "Diesel") ?: "Diesel",
            home = p.getString("p_home", "") ?: "",
            work = p.getString("p_work", "") ?: "",
            setupDone = p.getBoolean("p_done", false),
            homeLat = p.getString("p_home_lat", null)?.toDoubleOrNull(),
            homeLon = p.getString("p_home_lon", null)?.toDoubleOrNull(),
            workLat = p.getString("p_work_lat", null)?.toDoubleOrNull(),
            workLon = p.getString("p_work_lon", null)?.toDoubleOrNull(),
        )
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        sp?.edit()?.apply { block(); apply() }
    }

    fun setAccent(color: Int) { accent.value = color; edit { putInt("accent", color) } }
    fun setObdMac(mac: String?) { obdMac.value = mac; edit { putString("obd_mac", mac) } }
    fun setWallpaper(i: Int) { wallpaper.value = i; edit { putInt("wallpaper", i) } }
    fun setAutoNight(v: Boolean) { autoNight.value = v; edit { putBoolean("auto_night", v) } }
    fun setNightNow(v: Boolean) { nightNow.value = v; edit { putBoolean("night_now", v) } }
    fun setBootAnim(v: Boolean) { bootAnim.value = v; edit { putBoolean("boot_anim", v) } }
    fun setAutoUpdate(v: Boolean) { autoUpdate.value = v; edit { putBoolean("auto_update", v) } }
    fun setUpdateWifiOnly(v: Boolean) { updateWifiOnly.value = v; edit { putBoolean("update_wifi_only", v) } }
    fun setReverseCam(v: Boolean) { reverseCam.value = v; edit { putBoolean("reverse_cam", v) } }
    fun setHomeTiles(v: String) { homeTiles.value = v; edit { putString("home_tiles", v) } }
    fun setHomeSplit(v: Float) { val c = v.coerceIn(0.35f, 0.8f); homeSplit.value = c; edit { putFloat("home_split", c) } }
    fun setUi2(v: Boolean) { ui2.value = v; edit { putBoolean("ui2", v) } }
    fun setHomeSwap(v: Boolean) { homeSwap.value = v; edit { putBoolean("home_swap", v) } }
    fun setHomeShowMap(v: Boolean) { homeShowMap.value = v; edit { putBoolean("home_map", v) } }
    fun setHomeShowMedia(v: Boolean) { homeShowMedia.value = v; edit { putBoolean("home_media", v) } }
    fun resetHomeLayout() {
        setHomeSplit(0.6f); setHomeSwap(false); setHomeShowMap(true); setHomeShowMedia(true); setHomeTiles("speed,coolant,boost")
    }
    fun setAutoResumeMusic(v: Boolean) { autoResumeMusic.value = v; edit { putBoolean("auto_music", v) } }
    fun setSuggestHome(v: Boolean) { suggestHome.value = v; edit { putBoolean("suggest_home", v) } }
    fun setHomeFrom(v: Int) { homeFrom.value = v.coerceIn(0, 23); edit { putInt("home_from", homeFrom.value) } }
    fun setHomeTo(v: Int) { homeTo.value = v.coerceIn(1, 24); edit { putInt("home_to", homeTo.value) } }
    fun setRadioApp(v: String) { radioApp.value = v; edit { putString("radio_app", v) } }
    fun setBtApp(v: String) { btApp.value = v; edit { putString("bt_app", v) } }
    fun setHomeWeekdaysOnly(v: Boolean) { homeWeekdaysOnly.value = v; edit { putBoolean("home_weekdays", v) } }
    fun setRoadAlerts(v: Boolean) { roadAlerts.value = v; edit { putBoolean("road_alerts", v) } }
    fun setCarBody(v: String) { carBody.value = v; edit { putString("car_body", v) }; ConnectRepository.publishStatusSoon() }
    fun setCarPaint(v: Int) { carPaint.value = v; edit { putInt("car_paint", v) }; ConnectRepository.publishStatusSoon() }
    fun setMapEngine(v: String) { mapEngine.value = v; edit { putString("map_engine", v) } }
    fun setMap3d(v: Boolean) { map3d.value = v; edit { putBoolean("map_3d", v) } }
    fun setNavEngine(v: String) { navEngine.value = v; edit { putString("nav_engine", v) } }
    fun setClusterStretch(v: Float) {
        val r = (Math.round(v.coerceIn(1f, 2.2f) * 100) / 100f)
        clusterStretch.value = r
        edit { putFloat("cluster_stretch", r) }
    }
    fun setClusterUiScale(v: Float) {
        val r = Math.round(v.coerceIn(1f, 2.5f) * 10) / 10f
        clusterUiScale.value = r
        edit { putFloat("cluster_ui", r) }
    }
    fun setShareLive(v: Boolean) { shareLive.value = v; edit { putBoolean("share_live", v) } }
    fun setNavVoice(v: Boolean) { navVoice.value = v; edit { putBoolean("nav_voice", v) } }
    fun setMapSigns(v: Boolean) { mapSigns.value = v; edit { putBoolean("map_signs", v) } }
    fun setTurboTimer(v: Boolean) { turboTimer.value = v; edit { putBoolean("turbo_timer", v) } }
    fun setMapSnap(v: Boolean) { mapSnap.value = v; edit { putBoolean("map_snap", v) } }
    fun setMapHeadingUp(v: Boolean) { mapHeadingUp.value = v; edit { putBoolean("map_heading_up", v) } }
    fun setThemeMode(v: String) { themeMode.value = v; edit { putString("theme_mode", v) } }
    fun setBriDay(v: Int) { briDay.value = v; edit { putInt("bri_day", v) } }
    fun setBriNight(v: Int) { briNight.value = v.coerceIn(5, 100); edit { putInt("bri_night", briNight.value) } }
    fun setLightVariant(v: String) { lightVariant.value = v; edit { putString("light_variant", v) } }
    fun setBlockStatusBar(v: Boolean) { blockStatusBar.value = v; edit { putBoolean("block_status_bar", v) } }
    fun setAnimations(v: Boolean) { animations.value = v; edit { putBoolean("animations", v) } }
    fun setSpeedLimits(v: Boolean) { speedLimits.value = v; edit { putBoolean("speed_limits", v) } }
    fun setSpeedTolerance(v: Int) { speedTolerance.value = v.coerceIn(0, 20); edit { putInt("speed_tol", speedTolerance.value) } }
    fun setSpeedBeep(v: Boolean) { speedBeep.value = v; edit { putBoolean("speed_beep", v) } }
    fun setMapsInBackground(v: Boolean) { mapsInBackground.value = v; edit { putBoolean("maps_bg", v) } }

    fun setProfile(np: Profile) {
        val old = profile.value
        // adres zmieniony ręcznie w radiu → stare współrzędne już nie pasują
        var p = np
        if (p.home != old.home && p.homeLat == old.homeLat) p = p.copy(homeLat = null, homeLon = null)
        if (p.work != old.work && p.workLat == old.workLat) p = p.copy(workLat = null, workLon = null)
        profile.value = p
        edit {
            putString("p_name", p.name)
            putString("p_brand", p.brand)
            putString("p_model", p.model)
            putString("p_engine", p.engine)
            putString("p_year", p.year)
            putString("p_fuel", p.fuel)
            putString("p_home", p.home)
            putString("p_work", p.work)
            putBoolean("p_done", p.setupDone)
            putString("p_home_lat", p.homeLat?.toString())
            putString("p_home_lon", p.homeLon?.toString())
            putString("p_work_lat", p.workLat?.toString())
            putString("p_work_lon", p.workLon?.toString())
        }
        if (old.home != p.home || old.work != p.work || old.homeLat != p.homeLat || old.workLat != p.workLat) {
            ConnectRepository.publishStatusSoon()
        }
    }

    /** Dom albo praca (np. z CAR OS Connect): adres + opcjonalnie współrzędne. Pusty adres = usuń. */
    fun setPlace(work: Boolean, address: String, lat: Double?, lon: Double?) {
        val a = address.trim()
        val ok = a.isNotBlank() && lat != null && lon != null
        val p = profile.value
        setProfile(
            if (work) p.copy(work = a, workLat = if (ok) lat else null, workLon = if (ok) lon else null)
            else p.copy(home = a, homeLat = if (ok) lat else null, homeLon = if (ok) lon else null),
        )
    }

    fun resetLook() {
        setAccent(DEFAULT_ACCENT); setWallpaper(0); setAutoNight(true); setNightNow(false); setBootAnim(true); setAnimations(true)
    }
}
