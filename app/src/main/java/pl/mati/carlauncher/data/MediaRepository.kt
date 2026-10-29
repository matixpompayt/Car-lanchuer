package pl.mati.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import pl.mati.carlauncher.service.CarNotificationListener

/** Aktywna sesja multimediów (Spotify, YT Music, radio…) przez MediaSessionManager. */
object MediaRepository {

    data class MediaState(
        val title: String? = null,
        val artist: String? = null,
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val duration: Long = 0L,
        val pkg: String? = null,
        val appName: String? = null,
    )

    val state = MutableStateFlow(MediaState())
    /** Źródło wybrane w odtwarzaczu CAR OS: "radio", "bt" albo pakiet aplikacji. */
    val preferred = MutableStateFlow<String?>(null)
    /** Diagnostyka: wszystkie aktywne sesje multimediów. */
    val sessionsDebug = MutableStateFlow<List<String>>(emptyList())
    val access = MutableStateFlow(false)

    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
            if (state?.state != PlaybackState.STATE_PLAYING) refresh()
        }
        override fun onSessionDestroyed() = refresh()
    }

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun attach(context: Context) {
        handler.post {
            val ctx = context.applicationContext
            appContext = ctx
            val granted = hasAccess(ctx)
            access.value = granted
            if (!granted) return@post
            if (manager != null) {
                refresh()
                return@post
            }
            val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return@post
            val comp = ComponentName(ctx, CarNotificationListener::class.java)
            try {
                m.addOnActiveSessionsChangedListener(sessionsListener, comp, handler)
                manager = m
                component = comp
                refresh()
            } catch (_: SecurityException) {
            }
        }
    }

    fun refresh() {
        val m = manager ?: return
        try {
            pick(m.getActiveSessions(component))
        } catch (_: SecurityException) {
        }
    }

    private fun pick(list: List<MediaController>?) {
        val sessions = list.orEmpty()
        sessionsDebug.value = sessions.map {
            val st = when (it.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "gra"
                PlaybackState.STATE_PAUSED -> "pauza"
                PlaybackState.STATE_STOPPED -> "stop"
                null -> "brak stanu"
                else -> "stan ${it.playbackState?.state}"
            }
            "${it.packageName} · $st · ${it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "—"}"
        }
        val pref = preferred.value
        val best = sessions.firstOrNull { pref != null && it.packageName == pref }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controller?.takeIf { c -> sessions.any { it.sessionToken == c.sessionToken } }
            ?: sessions.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            state.value = MediaState()
            return
        }
        val md = c.metadata
        val ps = c.playbackState
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        state.value = MediaState(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            art = art,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            pkg = c.packageName,
            appName = appLabel(c.packageName),
        )
    }

    private fun appLabel(pkg: String): String? = try {
        val pm = appContext?.packageManager
        pm?.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString()
    } catch (_: Exception) {
        null
    }

    /** Aktualna pozycja odtwarzania w ms (ekstrapolowana). */
    fun position(): Long {
        val ps = controller?.playbackState ?: return 0L
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        val dt = SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
        return ps.position + (dt * ps.playbackSpeed).toLong()
    }

    fun playPause() {
        val c = controller ?: return sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun prefer(key: String?) {
        preferred.value = key
        refresh()
    }

    fun play() {
        val c = controller
        if (c != null) c.transportControls.play() else sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
    }

    fun next() {
        controller?.transportControls?.skipToNext() ?: sendKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    }

    fun previous() {
        controller?.transportControls?.skipToPrevious() ?: sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    private fun sendKey(code: Int) {
        val am = appContext?.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
