package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import pl.mati.carlauncher.data.BtAudioRepository
import pl.mati.carlauncher.data.MediaRepository
import pl.mati.carlauncher.data.SourceInfoRepository

/** Co pokazać jako „teraz gra”: sesja multimediów, muzyka BT z telefonu albo fabryczne radio. */
data class NowPlaying(
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
    val appName: String?,
    val pkg: String?,
    val controllable: Boolean,
    val playing: Boolean,
    val duration: Long,
    val isRadio: Boolean,
    val key: String?,          // "radio", "bt" albo pakiet
)

/** Sterowanie zależne od źródła. */
object Player {
    fun playPause(np: NowPlaying) = if (np.key == "bt" && BtAudioRepository.controllable) BtAudioRepository.playPause() else MediaRepository.playPause()
    fun next(np: NowPlaying) = if (np.key == "bt" && BtAudioRepository.controllable) BtAudioRepository.next() else MediaRepository.next()
    fun previous(np: NowPlaying) = if (np.key == "bt" && BtAudioRepository.controllable) BtAudioRepository.previous() else MediaRepository.previous()
    fun position(np: NowPlaying): Long = if (np.key == "bt") BtAudioRepository.position() else MediaRepository.position()
}

@Composable
fun rememberNowPlaying(): NowPlaying {
    val ctx = LocalContext.current
    val m by MediaRepository.state.collectAsState()
    val pref by MediaRepository.preferred.collectAsState()
    val infos by SourceInfoRepository.byKey.collectAsState()
    val bt by BtAudioRepository.state.collectAsState()

    val p = pref
    if (p == "bt" && bt.connected) {
        return NowPlaying(
            title = bt.title ?: "Muzyka Bluetooth",
            artist = listOfNotNull(bt.artist, bt.album).joinToString(" · ").ifBlank { "Z telefonu przez Bluetooth" },
            art = bt.art,
            appName = "Bluetooth",
            pkg = SourceApps.pkgOf(ctx, SourceApps.Kind.BT),
            controllable = true,
            playing = bt.playing,
            duration = bt.duration,
            isRadio = false,
            key = "bt",
        )
    }
    if (p == "radio" || p == "bt") {
        val kind = if (p == "radio") SourceApps.Kind.RADIO else SourceApps.Kind.BT
        val pkg = remember(p) { SourceApps.pkgOf(ctx, kind) }
        if (pkg != null && m.pkg == pkg && m.title != null) {
            return NowPlaying(m.title, m.artist, m.art, m.appName, m.pkg, true, m.playing, m.duration, p == "radio", p)
        }
        val info = infos[p] ?: pkg?.let { infos[it] }
        val isRadio = p == "radio"
        return NowPlaying(
            title = info?.title?.ifBlank { null } ?: if (isRadio) "Radio" else "Muzyka Bluetooth",
            artist = listOfNotNull(info?.text?.ifBlank { null }, info?.sub?.ifBlank { null }).joinToString(" · ")
                .ifBlank { if (isRadio) "Radio FM/AM" else "Z telefonu przez Bluetooth radia" },
            art = info?.icon,
            appName = if (isRadio) "Radio" else "Bluetooth",
            pkg = pkg,
            controllable = false,
            playing = true,
            duration = 0,
            isRadio = isRadio,
            key = p,
        )
    }
    return NowPlaying(m.title, m.artist, m.art, m.appName, m.pkg, m.pkg != null, m.playing, m.duration, false, m.pkg)
}
