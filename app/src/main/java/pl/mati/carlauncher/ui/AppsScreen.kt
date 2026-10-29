package pl.mati.carlauncher.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(
    val label: String,
    val pkg: String,
    val activity: String,
    val icon: ImageBitmap,
)

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CAR OS", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(CarColors.Card)
                    .clickable { if (!Launch.start(ctx, Intent(android.provider.Settings.ACTION_SETTINGS))) Toaster.show("Nie można otworzyć ustawień Androida") }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Settings, null, tint = CarColors.Text, modifier = Modifier.size(26.dp))
                Text("Ustawienia Android", fontSize = 19.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SysTile(Icons.Rounded.DirectionsCar, "Dziennik tras", Modifier.weight(1f)) { UiState.go(Screen.TRIPS) }
            SysTile(Icons.Rounded.Build, "Serwis", Modifier.weight(1f)) { UiState.go(Screen.SERVICE) }
            SysTile(Icons.Rounded.WbSunny, "Pogoda", Modifier.weight(1f)) { UiState.go(Screen.WEATHER) }
            SysTile(Icons.Rounded.Settings, "Ustawienia CAR OS", Modifier.weight(1f)) { UiState.openSettings("look") }
        }
        Caption("Aplikacje", size = 15.sp)
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ładowanie…", fontSize = 20.sp, color = CarColors.Muted)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.pkg + "/" + it.activity }) { app ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { launch(ctx, app) }
                            .padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Image(app.icon, contentDescription = null, modifier = Modifier.size(80.dp))
                        Text(
                            app.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .mapNotNull { ri ->
            try {
                AppEntry(
                    label = ri.loadLabel(pm).toString(),
                    pkg = ri.activityInfo.packageName,
                    activity = ri.activityInfo.name,
                    icon = ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
                )
            } catch (_: Exception) {
                null
            }
        }
        .sortedBy { it.label.lowercase() }
}

fun launch(ctx: Context, app: AppEntry) {
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(app.pkg, app.activity)
        .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    Launch.start(ctx, i)
}

@Composable
private fun SysTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CarColors.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, 52.dp)
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}
