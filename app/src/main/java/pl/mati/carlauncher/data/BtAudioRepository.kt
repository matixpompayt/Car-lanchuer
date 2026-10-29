package pl.mati.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Muzyka z telefonu przez Bluetooth radia (A2DP sink + AVRCP).
 * Systemowy stos BT Androida udostępnia ją jako MediaBrowserService w pakiecie com.android.bluetooth –
 * łączymy się z nim bezpośrednio, bo fabryczna aplikacja radia nie publikuje sesji.
 */
object BtAudioRepository {

    data class State(
        val connected: Boolean = false,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val duration: Long = 0,
        val service: String? = null,
    )

    val state = MutableStateFlow(State())
    /** Diagnostyka: znalezione usługi przeglądania multimediów. */
    val services = MutableStateFlow<List<String>>(emptyList())

    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null
    private var controller: MediaController? = null
    private var appCtx: Context? = null
    private var candidates: List<ComponentName> = emptyList()
    private var tryIndex = 0

    private val known = listOf(
        ComponentName("com.android.bluetooth", "com.android.bluetooth.avrcpcontroller.BluetoothMediaBrowserService"),
        ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"),
    )

    fun connect(ctx: Context) {
        if (browser?.isConnected == true) return
        appCtx = ctx.applicationContext
        val pm = ctx.packageManager
        val found = try {
            pm.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        } catch (_: Exception) {
            emptyList()
        }
        services.value = found.map { it.flattenToShortString() }
        candidates = (known.filter { k -> found.any { it == k } } +
            found.filter { it.packageName.contains("bluetooth", true) || it.className.contains("avrcp", true) || it.className.contains("a2dp", true) })
            .distinct()
            .ifEmpty { known }
        tryIndex = 0
        tryNext()
    }

    private fun tryNext() {
        val ctx = appCtx ?: return
        val cn = candidates.getOrNull(tryIndex) ?: return
        try { browser?.disconnect() } catch (_: Exception) {}
        val b = MediaBrowser(ctx, cn, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val br = browser ?: return
                try {
                    val c = MediaController(ctx, br.sessionToken)
                    controller?.unregisterCallback(cb)
                    controller = c
                    c.registerCallback(cb, handler)
                    state.value = state.value.copy(connected = true, service = cn.flattenToShortString())
                    publish()
                } catch (_: Exception) {
                }
            }

            override fun onConnectionFailed() {
                tryIndex++
                if (tryIndex < candidates.size) tryNext()
                else state.value = State(connected = false)
            }

            override fun onConnectionSuspended() {
                controller?.unregisterCallback(cb)
                controller = null
                state.value = State(connected = false)
                handler.postDelayed({ appCtx?.let { connect(it) } }, 5000)
            }
        }, null)
        browser = b
        try { b.connect() } catch (_: Exception) {}
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(st: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            state.value = State(connected = false)
            handler.postDelayed({ appCtx?.let { connect(it) } }, 3000)
        }
    }

    private fun publish() {
        val c = controller ?: return
        val md = c.metadata
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val wasPlaying = state.value.playing
        state.value = state.value.copy(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            playing = playing,
        )
        // muzyka z telefonu ruszyła → odtwarzacz CAR OS przełącza się na Bluetooth
        if (playing && !wasPlaying) MediaRepository.preferred.value = "bt"
    }

    fun position(): Long {
        val ps = controller?.playbackState ?: return 0L
        if (ps.state != PlaybackState.STATE_PLAYING) return ps.position
        return ps.position + ((android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
    }

    val controllable: Boolean get() = controller != null

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
}
