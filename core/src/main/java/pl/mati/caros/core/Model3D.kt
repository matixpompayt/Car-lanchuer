package pl.mati.caros.core

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ikony 3D aut i ludzika (CAR OS: auto na mapie i w ustawieniach; Connect: mapa, ekran główny).
 * Siatka i rasteryzacja – [CarMesh] (czysty Kotlin, testowany); tu tylko bitmapa z cieniem.
 */
object Model3D {
    fun car(body: String?, paint: Int): List<CarMesh.Face> = CarMesh.car(body, paint)

    /** Wymiary modelu auta do cienia (szerokość, długość w metrach). */
    fun carSize(body: String?): Pair<Double, Double> = CarMesh.size(body)

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /**
     * Gotowa ikona auta 3D. [outline] – delikatny jasny obrys ciemnych lakierów, żeby auto nie ginęło
     * na ciemnej mapie; w podglądach (ekran główny, ustawienia) bez obrysu.
     */
    fun carIcon(
        body: String?, paint: Int, yawDeg: Double, elevDeg: Double, sizePx: Int,
        faces: List<CarMesh.Face> = car(body, paint), outline: Boolean = true,
    ): Bitmap {
        val (w, l) = carSize(body)
        val rim = if (outline && lum(paint) < 0.35) Color.argb(150, 200, 210, 222) else null
        return render(faces, yawDeg, elevDeg, sizePx, 6.0, w, l, 0.5, rim)
    }

    /** Ludzik 3D (Twoja pozycja w telefonie). Punkt na ziemi przy dole obrazka. */
    fun personIcon(shirt: Int, sizePx: Int): Bitmap = render(CarMesh.person(shirt), 25.0, 30.0, sizePx, 2.4, 0.6, 0.5, 0.88)

    /**
     * @param yawDeg kierunek modelu względem kamery (0 = tyłem do patrzącego, zgodnie z ruchem wskazówek zegara)
     * @param elevDeg kąt patrzenia nad horyzontem (90 = z góry)
     * @param metersAcross ile metrów mieści się w szerokości obrazka
     */
    fun render(
        faces: List<CarMesh.Face>, yawDeg: Double, elevDeg: Double, sizePx: Int, metersAcross: Double,
        shadowW: Double = 1.0, shadowL: Double = 2.2, originY: Double = 0.5, rim: Int? = null,
    ): Bitmap {
        val ss = 2
        val n = (sizePx * ss).coerceAtLeast(8)
        val px = CarMesh.rasterize(faces, yawDeg, elevDeg, n, metersAcross, originY, rim)
        val big = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // miękki cień na ziemi
        val a = Math.toRadians(yawDeg)
        val se = sin(Math.toRadians(elevDeg))
        val ca = cos(a); val sa = sin(a)
        val sc = sizePx / metersAcross
        val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0)
            maskFilter = BlurMaskFilter(sizePx * 0.045f, BlurMaskFilter.Blur.NORMAL)
        }
        val wX = (abs(ca) * shadowW + abs(sa) * shadowL) * sc / 2
        val wY = (abs(sa) * shadowW + abs(ca) * shadowL) * se * sc / 2
        val oy = sizePx * originY
        c.drawOval(RectF((sizePx / 2f - wX).toFloat(), (oy - wY).toFloat(), (sizePx / 2f + wX).toFloat(), (oy + wY).toFloat()), sh)
        c.drawBitmap(Bitmap.createScaledBitmap(big, sizePx, sizePx, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        big.recycle()
        return out
    }
}
