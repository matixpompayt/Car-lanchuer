package pl.mati.carlauncher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.osmdroid.config.Configuration
import android.content.IntentFilter
import pl.mati.carlauncher.data.AutostartManager
import pl.mati.carlauncher.data.ForecastRepository
import pl.mati.carlauncher.data.LocationRepository
import pl.mati.carlauncher.data.ServiceRepository
import pl.mati.carlauncher.data.TripRepository
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.ObdManager
import pl.mati.carlauncher.data.Prefs
import pl.mati.carlauncher.data.SystemControls
import pl.mati.carlauncher.data.UpdateManager
import pl.mati.carlauncher.data.WeatherRepository
import pl.mati.carlauncher.ui.CarApp
import pl.mati.carlauncher.ui.CarRoot
import pl.mati.carlauncher.ui.Phase
import pl.mati.carlauncher.ui.Screen
import pl.mati.carlauncher.ui.UiState
import java.io.File

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            LocationRepository.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pl.mati.carlauncher.data.CrashLog.install(this)
        Prefs.init(this)
        pl.mati.carlauncher.data.Changelog.init(this)
        // tryb dzień/noc znany od pierwszej klatki (także ekran startowy)
        pl.mati.carlauncher.data.ThemeManager.start(this)
        pl.mati.carlauncher.data.CanRepository.init(this)
        pl.mati.carlauncher.data.OfflineMaps.init(this)
        pl.mati.carlauncher.data.FuelRepository.load()
        window.decorView.postDelayed({
            Thread { pl.mati.carlauncher.data.CrashLog.reportPrevious(applicationContext) }.start()
        }, 5000)
        pl.mati.carlauncher.ui.CarColors.palette =
            if (pl.mati.carlauncher.data.ThemeManager.light.value) pl.mati.carlauncher.ui.Palettes.light(Prefs.lightVariant.value)
            else pl.mati.carlauncher.ui.Palettes.DARK
        SystemControls.attach(this)

        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        if (savedInstanceState == null) UiState.start()

        requestMissingPermissions()
        LocationRepository.start(this)
        MediaRepository.attach(this)
        WeatherRepository.start()
        SystemControls.refresh(this)
        Prefs.obdMac.value?.let { mac ->
            if (ObdManager.status.value == ObdManager.Status.OFF) ObdManager.start(mac)
        }
        if (Prefs.autoUpdate.value && Prefs.profile.value.setupDone) UpdateManager.check(this, silent = true)
        ServiceRepository.load()
        TripRepository.dir = File(filesDir, "trips")
        TripRepository.start()
        ForecastRepository.start()
        pl.mati.carlauncher.data.OfflineSpeed.init(this)
        pl.mati.carlauncher.data.SpeedLimitRepository.start()
        pl.mati.carlauncher.data.ThemeManager.start(this)
        pl.mati.carlauncher.data.ConnectRepository.init()
        pl.mati.carlauncher.data.ParkingRepository.start()
        pl.mati.carlauncher.data.PlacesRepository.load()
        ContextCompat.registerReceiver(
            this, AutostartManager.screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SHUTDOWN)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (savedInstanceState == null) AutostartManager.run(this)
        pl.mati.carlauncher.data.BackupManager.autoBackup(this)

        setContent {
            CarRoot { CarApp() }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pl.mati.carlauncher.data.HeadlightDetector.onUiMode(newConfig)
    }

    override fun onPause() {
        super.onPause()
        pl.mati.carlauncher.data.ShadeGuard.launcherResumed = false
        pl.mati.carlauncher.data.BackupManager.autoBackup(this)
    }

    override fun onDestroy() {
        try { unregisterReceiver(AutostartManager.screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun watchSources() {
        pl.mati.carlauncher.data.SourceInfoRepository.watched = setOfNotNull(
            pl.mati.carlauncher.ui.SourceApps.pkgOf(this, pl.mati.carlauncher.ui.SourceApps.Kind.RADIO),
            pl.mati.carlauncher.ui.SourceApps.pkgOf(this, pl.mati.carlauncher.ui.SourceApps.Kind.BT),
        )
        pl.mati.carlauncher.data.NotificationRepository.rescan(this)
        pl.mati.carlauncher.data.BtAudioRepository.connect(this)
        if (!probeStarted) {
            probeStarted = true
            pl.mati.carlauncher.data.BroadcastProbe.start(this, pl.mati.carlauncher.ui.SourceApps.pkgOf(this, pl.mati.carlauncher.ui.SourceApps.Kind.RADIO))
        }
    }

    private var probeStarted = false

    override fun onResume() {
        super.onResume()
        pl.mati.carlauncher.data.ShadeGuard.launcherResumed = true
        SystemControls.attach(this)
        watchSources()
        hideSystemBars()
        MediaRepository.attach(this)
        SystemControls.refresh(this)
    }

    /** Przycisk głosowy na kierownicy / radiu → komenda głosowa CAR OS. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            if (pl.mati.carlauncher.data.VoiceCommands.hasMic(this)) pl.mati.carlauncher.data.VoiceCommands.listen(this)
            else UiState.go(Screen.HOME)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Przycisk HOME na radiu / kierownicy -> ekran główny
        if (intent.action == Intent.ACTION_MAIN && UiState.phase.value == Phase.MAIN) UiState.go(Screen.HOME)
        if (intent.action == "pl.mati.carlauncher.RETURN" && UiState.phase.value == Phase.MAIN) {
            UiState.go(Screen.HOME)
            pl.mati.carlauncher.ui.Toaster.show("Mapy Google prowadzą w tle – głos i manewry działają")
        }
    }

    /**
     * Systemowy pasek / zasłona powiadomień nad CAR OS: od razu je zamykamy.
     * Gdy okno traci fokus, a CAR OS dalej jest na wierzchu (nie otwarto innej aplikacji),
     * to znaczy, że ktoś wysunął systemową zasłonę – zamykamy ją
     * (Android ≤ 11 broadcastem, Android 12+ przez usługę ułatwień dostępu ShadeGuardService).
     */
    private val closeShade = Runnable {
        if (!Prefs.blockStatusBar.value) return@Runnable
        if (hasWindowFocus() || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@Runnable
        pl.mati.carlauncher.data.ShadeGuard.dismiss(this)
        hideSystemBars()
    }

    /**
     * Android 11+: pasek pokazany gestem jest „tymczasowy” i system odbiera nam nad nim kontrolę
     * na ~3 s. Prośba o pokazanie przerywa ten tryb i oddaje kontrolę – zaraz potem go chowamy.
     */
    private val abortTransient = Runnable {
        if (!Prefs.blockStatusBar.value || !hasWindowFocus()) return@Runnable
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@Runnable
        val c = window.insetsController ?: return@Runnable
        c.show(android.view.WindowInsets.Type.systemBars())
        window.decorView.postDelayed({ if (Prefs.blockStatusBar.value) hideSystemBars() }, 60)
    }
    private var hadBarControl = false

    private val rehide = Runnable { if (Prefs.blockStatusBar.value) hideSystemBars() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        pl.mati.carlauncher.data.ShadeGuard.launcherFocused = hasFocus
        window.decorView.removeCallbacks(closeShade)
        if (hasFocus) hideSystemBars()
        else window.decorView.postDelayed(closeShade, 120)
    }

    private fun requestMissingPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) perms += Manifest.permission.BLUETOOTH_CONNECT
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private var barsListener = false

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // pasek wysunięty gestem z krawędzi – schowaj go od razu (zamiast po kilku sekundach)
        if (!barsListener && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            barsListener = true
            window.insetsController?.addOnControllableInsetsChangedListener { _, types ->
                val has = types and android.view.WindowInsets.Type.statusBars() != 0
                if (hadBarControl && !has && hasWindowFocus() && Prefs.blockStatusBar.value &&
                    lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                ) {
                    window.decorView.removeCallbacks(abortTransient)
                    window.decorView.postDelayed(abortTransient, 150)
                }
                hadBarControl = has
            }
        }
        if (!barsListener) {
            barsListener = true
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { vis ->
                if (vis and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    window.decorView.removeCallbacks(rehide)
                    window.decorView.postDelayed(rehide, 250)
                }
            }
        }
    }
}
