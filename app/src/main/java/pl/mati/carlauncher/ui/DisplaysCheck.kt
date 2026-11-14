package pl.mati.carlauncher.ui

import android.app.Presentation
import android.content.Context
import android.graphics.Color as AColor
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sprawdzenie ekranów: czy Android widzi licznik (box HDMI na USB) jako drugi ekran.
 * Jeśli tak – na takim ekranie można pokazać własny obraz (Presentation), niezależny od radia.
 */
@Composable
fun DisplaysCard() {
    val ctx = LocalContext.current
    val dm = remember { ctx.getSystemService(DisplayManager::class.java) }
    var gen by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<String?>(null) }
    // box bywa podłączany po starcie – lista odświeża się sama
    DisposableEffect(dm) {
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) { gen++ }
            override fun onDisplayRemoved(displayId: Int) { gen++ }
            override fun onDisplayChanged(displayId: Int) { gen++ }
        }
        dm?.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(l) }
    }
    val all = remember(gen) { dm?.displays?.toList().orEmpty() }
    val pres = remember(gen) { dm?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.map { it.displayId }?.toSet().orEmpty() }
    val apps = remember { screenApps(ctx) }

    CarCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ekrany (licznik przez HDMI)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            for (d in all) {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION") d.getRealMetrics(m)
                val kind = when {
                    d.displayId == Display.DEFAULT_DISPLAY -> "główny – ekran radia"
                    d.displayId in pres -> "DRUGI EKRAN – można na nim pokazać osobny obraz"
                    d.flags and Display.FLAG_PRIVATE != 0 -> "prywatny (należy do innej aplikacji)"
                    else -> "dodatkowy"
                }
                Text(
                    "#${d.displayId} · ${d.name} · ${m.widthPixels}×${m.heightPixels} · $kind",
                    fontSize = 16.sp, color = if (d.displayId in pres) CarColors.Green else CarColors.Text2,
                )
            }
            val second = all.filter { it.displayId != Display.DEFAULT_DISPLAY }
            Text(
                when {
                    pres.isNotEmpty() -> "Android widzi licznik jako osobny ekran – da się zrobić na nim własny widok CAR OS. Sprawdź przyciskiem „Test na liczniku”."
                    second.isNotEmpty() -> "Jest dodatkowy ekran, ale prywatny – obraz na nim robi inna aplikacja. Własny widok raczej się nie da."
                    else -> "Android widzi tylko ekran radia – licznik dostaje kopię (lustro), robioną przez box albo jego aplikację. Osobnego obrazu nie da się tak zrobić. Sprawdź przy włączonym liczniku."
                },
                fontSize = 15.sp, color = CarColors.Muted,
            )
            if (apps.isNotEmpty()) Text("Aplikacje w radiu, które mogą obsługiwać box: " + apps.joinToString(", "), fontSize = 14.sp, color = CarColors.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val target = all.firstOrNull { it.displayId in pres } ?: second.firstOrNull()
                if (target != null) {
                    PrimaryButton("Test na liczniku", {
                        result = try {
                            TestScreen(ctx, target).show()
                            "Na liczniku powinien być napis „CAR OS – drugi ekran” na 15 s. Widać go? Napisz – wtedy robię widok licznika."
                        } catch (e: Exception) {
                            "Nie da się pokazać obrazu na tym ekranie (${e.javaClass.simpleName}) – zostaje lustro."
                        }
                    })
                }
                SecondaryButton("Odśwież", { gen++ }, height = 56.dp)
            }
            result?.let { Text(it, fontSize = 15.sp, color = CarColors.Orange) }
        }
    }
}

/** Aplikacje w radiu z nazwą sugerującą obsługę zewnętrznego ekranu (do diagnostyki). */
private fun screenApps(ctx: Context): List<String> = try {
    val re = Regex("(hdmi|display|mirror|cast|projection|screen|zlink|carlink|autokit|tlink|dual|cluster|dashboard|instrument)", RegexOption.IGNORE_CASE)
    @Suppress("DEPRECATION")
    ctx.packageManager.getInstalledApplications(0)
        .filter { re.containsMatchIn(it.packageName) || re.containsMatchIn(ctx.packageManager.getApplicationLabel(it).toString()) }
        .map { "${ctx.packageManager.getApplicationLabel(it)} (${it.packageName})" }
        .take(8)
} catch (_: Exception) {
    emptyList()
}

/** Prosta plansza testowa na drugim ekranie – znika po 15 s. */
private class TestScreen(ctx: Context, display: Display) : Presentation(ctx, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(m)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(AColor.rgb(10, 14, 22))
        }
        box.addView(TextView(context).apply {
            text = "CAR OS – drugi ekran"
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AColor.rgb(47, 211, 240))
            gravity = Gravity.CENTER
        })
        box.addView(TextView(context).apply {
            text = "${m.widthPixels} × ${m.heightPixels} · ${display.name}"
            textSize = 24f
            setTextColor(AColor.WHITE)
            gravity = Gravity.CENTER
        })
        setContentView(box)
        Handler(Looper.getMainLooper()).postDelayed({ try { dismiss() } catch (_: Exception) {} }, 15_000)
    }
}
