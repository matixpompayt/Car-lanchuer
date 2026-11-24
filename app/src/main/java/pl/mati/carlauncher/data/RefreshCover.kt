package pl.mati.carlauncher.data

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Zasłona na czas odświeżania danych z auta: zrzut ekranu CAR OS położony nad wszystkim, a pod
 * spodem MainApp ma otwarty ekran „Informacje” (tylko wtedy przejściówka CAN podaje dane).
 *
 * Usługa ułatwień dostępu CAR OS (blokada zasłony) zakrywa też górny pasek radia; samo
 * „wyświetlanie nad innymi” – wszystko poza paskiem systemu.
 */
object RefreshCover {
    enum class Method { ACCESSIBILITY, OVERLAY }

    private var view: View? = null
    private var wm: WindowManager? = null

    /** Czym da się zasłonić (null = niczym – odświeżanie będzie widać). */
    fun method(ctx: Context): Method? = when {
        ShadeGuard.service != null -> Method.ACCESSIBILITY
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx) -> Method.OVERLAY
        else -> null
    }

    /** Połóż zrzut CAR OS nad ekranem. [onTouch] – dotknięcie zasłony (przerwij odświeżanie). */
    suspend fun show(a: Activity, onTouch: () -> Unit): Boolean {
        hide()
        val m = method(a) ?: return false
        val dv = a.window?.decorView ?: return false
        val bmp = snapshot(a) ?: return false
        val loc = IntArray(2)
        try { dv.getLocationOnScreen(loc) } catch (_: Exception) {}
        val svc = ShadeGuard.service
        val (ctx, type) = when {
            m == Method.ACCESSIBILITY && svc != null -> svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= 26 -> a.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> {
                @Suppress("DEPRECATION")
                a.applicationContext to WindowManager.LayoutParams.TYPE_PHONE
            }
        }
        val w = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val iv = ImageView(ctx).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
                true
            }
        }
        @Suppress("DEPRECATION")
        val lp = WindowManager.LayoutParams(
            bmp.width, bmp.height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = loc[0]
            y = loc[1]
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "CAR OS odświeżanie"
        }
        return try {
            w.addView(iv, lp)
            view = iv
            wm = w
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        val v = view ?: return
        try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
        view = null
        wm = null
    }

    /** Zrzut okna CAR OS razem z mapą (PixelCopy – zwykłe rysowanie widoku pomija mapę). */
    private suspend fun snapshot(a: Activity): Bitmap? {
        if (Build.VERSION.SDK_INT < 26) return null
        val win = a.window ?: return null
        val dv = win.decorView
        if (dv.width <= 0 || dv.height <= 0) return null
        val bmp = try { Bitmap.createBitmap(dv.width, dv.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        val ok = withTimeoutOrNull(800) {
            suspendCancellableCoroutine<Boolean> { c ->
                try {
                    PixelCopy.request(win, bmp, { r -> if (c.isActive) c.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
                } catch (_: Exception) {
                    if (c.isActive) c.resume(false)
                }
            }
        } ?: false
        return if (ok) bmp else null
    }
}
