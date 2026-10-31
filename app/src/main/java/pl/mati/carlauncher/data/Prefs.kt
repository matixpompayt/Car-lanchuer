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
    val autoResumeMusic = MutableStateFlow(true)
    val suggestHome = MutableStateFlow(true)
    val homeFrom = MutableStateFlow(14)
    val homeTo = MutableStateFlow(19)
    val homeWeekdaysOnly = MutableStateFlow(true)
    /** Wybrane aplikacje źródeł: "pakiet/aktywność", pusto = wykrywanie automatyczne. */
    val radioApp = MutableStateFlow("")
    val btApp = MutableStateFlow("")
    val animations = MutableStateFlow(true)
    /** Mapa: "vector" = OpenFreeMap 3D (MapLibre), "osm" = klasyczne kafelki OSM. */
    val mapEngine = MutableStateFlow("vector")
    val map3d = MutableStateFlow(true)
    val mapHeadingUp = MutableStateFlow(true)
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
        autoResumeMusic.value = p.getBoolean("auto_music", true)
        suggestHome.value = p.getBoolean("suggest_home", true)
        homeFrom.value = p.getInt("home_from", 14)
        homeTo.value = p.getInt("home_to", 19)
        homeWeekdaysOnly.value = p.getBoolean("home_weekdays", true)
        radioApp.value = p.getString("radio_app", "") ?: ""
        btApp.value = p.getString("bt_app", "") ?: ""
        animations.value = p.getBoolean("animations", true)
        mapEngine.value = p.getString("map_engine", "vector") ?: "vector"
        map3d.value = p.getBoolean("map_3d", true)
        mapHeadingUp.value = p.getBoolean("map_heading_up", true)
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
    fun setAutoResumeMusic(v: Boolean) { autoResumeMusic.value = v; edit { putBoolean("auto_music", v) } }
    fun setSuggestHome(v: Boolean) { suggestHome.value = v; edit { putBoolean("suggest_home", v) } }
    fun setHomeFrom(v: Int) { homeFrom.value = v.coerceIn(0, 23); edit { putInt("home_from", homeFrom.value) } }
    fun setHomeTo(v: Int) { homeTo.value = v.coerceIn(1, 24); edit { putInt("home_to", homeTo.value) } }
    fun setRadioApp(v: String) { radioApp.value = v; edit { putString("radio_app", v) } }
    fun setBtApp(v: String) { btApp.value = v; edit { putString("bt_app", v) } }
    fun setHomeWeekdaysOnly(v: Boolean) { homeWeekdaysOnly.value = v; edit { putBoolean("home_weekdays", v) } }
    fun setRoadAlerts(v: Boolean) { roadAlerts.value = v; edit { putBoolean("road_alerts", v) } }
    fun setMapEngine(v: String) { mapEngine.value = v; edit { putString("map_engine", v) } }
    fun setMap3d(v: Boolean) { map3d.value = v; edit { putBoolean("map_3d", v) } }
    fun setMapHeadingUp(v: Boolean) { mapHeadingUp.value = v; edit { putBoolean("map_heading_up", v) } }
    fun setAnimations(v: Boolean) { animations.value = v; edit { putBoolean("animations", v) } }
    fun setSpeedLimits(v: Boolean) { speedLimits.value = v; edit { putBoolean("speed_limits", v) } }
    fun setSpeedTolerance(v: Int) { speedTolerance.value = v.coerceIn(0, 20); edit { putInt("speed_tol", speedTolerance.value) } }
    fun setSpeedBeep(v: Boolean) { speedBeep.value = v; edit { putBoolean("speed_beep", v) } }
    fun setMapsInBackground(v: Boolean) { mapsInBackground.value = v; edit { putBoolean("maps_bg", v) } }

    fun setProfile(p: Profile) {
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
        }
    }

    fun resetLook() {
        setAccent(DEFAULT_ACCENT); setWallpaper(0); setAutoNight(true); setNightNow(false); setBootAnim(true); setAnimations(true)
    }
}
