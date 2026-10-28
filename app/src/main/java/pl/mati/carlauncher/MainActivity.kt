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
import pl.mati.carlauncher.data.LocationRepository
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
        Prefs.init(this)

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

        setContent {
            CarRoot { CarApp() }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        MediaRepository.attach(this)
        SystemControls.refresh(this)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
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

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
