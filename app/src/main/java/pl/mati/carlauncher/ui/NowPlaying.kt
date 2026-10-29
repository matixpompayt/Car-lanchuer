package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.SourceInfoRepository

/** Co pokazać jako „teraz gra”: sesja multimediów albo fabryczna aplikacja radia/BT. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
    val appName: String?,
    val pkg: String?,
    val controllable: Boolean, // true = sterujemy przez sesję multimediów
    val playing: Boolean,
    val duration: Long,
    val isRadio: Boolean,
)

@Composable
fun rememberNowPlaying(): NowPlaying {
    val ctx = LocalContext.current
    val m by MediaRepository.state.collectAsState()
    val pref by MediaRepository.preferredPkg.collectAsState()
    val infos by SourceInfoRepository.byPkg.collectAsState()
    val radioPkg = remember { SourceApps.pkgOf(ctx, SourceApps.Kind.RADIO) }
    val btPkg = remember { SourceApps.pkgOf(ctx, SourceApps.Kind.BT) }

    val p = pref
    if (p != null && m.pkg != p && (p == radioPkg || p == btPkg)) {
        val info = infos[p]
        val isRadio = p == radioPkg
        val label = SourceApps.label(ctx, p) ?: if (isRadio) "Radio" else "Bluetooth"
        return NowPlaying(
            title = info?.title?.ifBlank { null } ?: if (isRadio) "Radio" else "Muzyka Bluetooth",
            artist = listOfNotNull(info?.text?.ifBlank { null }, info?.sub?.ifBlank { null }).joinToString(" · ").ifBlank { "Gra w aplikacji: $label" },
            art = info?.icon,
            appName = label,
            pkg = p,
            controllable = false,
            playing = true,
            duration = 0,
            isRadio = isRadio,
        )
    }
    return NowPlaying(m.title, m.artist, m.art, m.appName, m.pkg, m.pkg != null, m.playing, m.duration, m.pkg == radioPkg)
}
