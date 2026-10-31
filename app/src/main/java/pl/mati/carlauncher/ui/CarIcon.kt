package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * Ikona auta na mapie (widok z góry, przód do góry): typ nadwozia + kolor lakieru.
 * Rysowana w kodzie – ostra w każdym rozmiarze.
 */
object CarIcon {

    data class Body(
        val id: String, val label: String,
        val len: Float, val wid: Float,
        val hood: Float, val roofStart: Float, val roofEnd: Float,
        val corner: Float, val pickup: Boolean = false,
    )

    val BODIES = listOf(
        Body("hatchback", "Hatchback", 0.86f, 0.92f, 0.24f, 0.40f, 0.88f, 0.30f),
        Body("sedan", "Sedan", 1.00f, 0.90f, 0.27f, 0.40f, 0.72f, 0.28f),
        Body("kombi", "Kombi", 1.00f, 0.90f, 0.25f, 0.38f, 0.93f, 0.24f),
        Body("suv", "SUV", 0.96f, 1.00f, 0.23f, 0.36f, 0.91f, 0.20f),
        Body("minivan", "Minivan", 0.95f, 0.96f, 0.17f, 0.29f, 0.94f, 0.24f),
        Body("van", "Van / bus", 1.06f, 1.00f, 0.11f, 0.20f, 0.97f, 0.12f),
        Body("pickup", "Pickup", 1.06f, 1.00f, 0.24f, 0.35f, 0.55f, 0.16f, pickup = true),
        Body("coupe", "Coupé", 0.92f, 0.92f, 0.33f, 0.45f, 0.72f, 0.34f),
    )

    val COLORS: List<Pair<String, Int>> = listOf(
        "Biały" to 0xFFF2F3F5.toInt(),
        "Srebrny" to 0xFFC3C8CF.toInt(),
        "Szary" to 0xFF6B727C.toInt(),
        "Czarny" to 0xFF1A1C20.toInt(),
        "Granatowy" to 0xFF1F2F57.toInt(),
        "Niebieski" to 0xFF2E6FD8.toInt(),
        "Czerwony" to 0xFFD32F2F.toInt(),
        "Bordowy" to 0xFF7A1F2B.toInt(),
        "Zielony" to 0xFF2E7D4F.toInt(),
        "Żółty" to 0xFFF2C230.toInt(),
        "Pomarańczowy" to 0xFFF07B22.toInt(),
        "Beżowy" to 0xFFCDB79A.toInt(),
    )

    const val DEFAULT_BODY = "minivan"
    val DEFAULT_COLOR = 0xFFC3C8CF.toInt()

    fun body(id: String?) = BODIES.firstOrNull { it.id == id } ?: BODIES.first { it.id == DEFAULT_BODY }

    private fun shade(c: Int, f: Float): Int {
        fun ch(v: Int) = (if (f >= 1f) v + (255 - v) * (f - 1f) else v * f).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    private fun luminance(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** @param heightPx wysokość obrazka w pikselach (auto zajmuje ok. 85%). */
    fun bitmap(bodyId: String?, color: Int, heightPx: Int): Bitmap {
        val b = body(bodyId)
        val h = heightPx.toFloat()
        val w = (h * 0.62f)
        val bmp = Bitmap.createBitmap(w.toInt().coerceAtLeast(8), heightPx.coerceAtLeast(8), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val carLen = h * 0.80f * b.len.coerceAtMost(1.06f)
        val carW = h * 0.36f * b.wid
        val cx = w / 2f
        val top = h / 2f - carLen / 2f
        val bottom = h / 2f + carLen / 2f
        val left = cx - carW / 2f
        val right = cx + carW / 2f
        val rx = carW * b.corner
        val ry = carLen * b.corner * 0.45f
        fun y(f: Float) = top + carLen * f

        // cień pod autem
        p.color = Color.argb(150, 0, 0, 0)
        p.maskFilter = BlurMaskFilter(h * 0.035f, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(RectF(left - carW * 0.04f, top + carLen * 0.02f, right + carW * 0.04f, bottom + carLen * 0.03f), rx, ry, p)
        p.maskFilter = null

        // lusterka
        p.shader = null
        p.color = shade(color, 0.8f)
        val my = y(b.hood + 0.015f)
        c.drawOval(RectF(left - carW * 0.13f, my, left + carW * 0.04f, my + carLen * 0.045f), p)
        c.drawOval(RectF(right - carW * 0.04f, my, right + carW * 0.13f, my + carLen * 0.045f), p)

        // nadwozie z cieniowaniem (ciemniejsze boki)
        val body = RectF(left, top, right, bottom)
        p.shader = LinearGradient(
            left, 0f, right, 0f,
            intArrayOf(shade(color, 0.62f), shade(color, 1.0f), shade(color, 1.12f), shade(color, 1.0f), shade(color, 0.62f)),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(body, rx, ry, p)
        p.shader = null

        val glass1 = 0xFF1A2230.toInt()
        val glass2 = 0xFF2E3E55.toInt()
        fun glass(path: Path, y0: Float, y1: Float) {
            p.shader = LinearGradient(0f, y0, 0f, y1, glass2, glass1, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
        }

        // przednia szyba (trapez)
        val wsTop = y(b.hood)
        val wsBot = y(b.roofStart)
        glass(Path().apply {
            moveTo(cx - carW * 0.36f, wsTop)
            lineTo(cx + carW * 0.36f, wsTop)
            lineTo(cx + carW * 0.42f, wsBot)
            lineTo(cx - carW * 0.42f, wsBot)
            close()
        }, wsTop, wsBot)

        // tylna szyba
        val rearH = if (b.pickup) 0.04f else 0.08f
        val rwTop = y(b.roofEnd - rearH)
        val rwBot = y(b.roofEnd)
        glass(Path().apply {
            moveTo(cx - carW * 0.42f, rwTop)
            lineTo(cx + carW * 0.42f, rwTop)
            lineTo(cx + carW * 0.36f, rwBot)
            lineTo(cx - carW * 0.36f, rwBot)
            close()
        }, rwTop, rwBot)

        // dach
        p.color = shade(color, 1.06f)
        c.drawRoundRect(RectF(cx - carW * 0.40f, wsBot, cx + carW * 0.40f, rwTop), carW * 0.08f, carW * 0.08f, p)

        // boczne szyby
        p.color = glass1
        val sw = carW * 0.055f
        c.drawRoundRect(RectF(left + carW * 0.035f, wsBot - carLen * 0.01f, left + carW * 0.035f + sw, rwTop), sw, sw, p)
        c.drawRoundRect(RectF(right - carW * 0.035f - sw, wsBot - carLen * 0.01f, right - carW * 0.035f, rwTop), sw, sw, p)

        // pickup: otwarta skrzynia
        if (b.pickup) {
            p.color = shade(color, 0.45f)
            c.drawRoundRect(RectF(cx - carW * 0.40f, y(b.roofEnd + 0.03f), cx + carW * 0.40f, y(0.96f)), carW * 0.04f, carW * 0.04f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = carW * 0.025f
            p.color = shade(color, 0.7f)
            for (k in 1..3) {
                val yy = y(b.roofEnd + 0.03f + (0.93f - b.roofEnd) * k / 4f)
                c.drawLine(cx - carW * 0.36f, yy, cx + carW * 0.36f, yy, p)
            }
            p.style = Paint.Style.FILL
        }

        // światła
        p.color = 0xFFFFF4D6.toInt()
        val lw = carW * 0.22f
        val lh = carLen * 0.03f
        c.drawRoundRect(RectF(left + carW * 0.08f, top + carLen * 0.008f, left + carW * 0.08f + lw, top + carLen * 0.008f + lh), lh, lh, p)
        c.drawRoundRect(RectF(right - carW * 0.08f - lw, top + carLen * 0.008f, right - carW * 0.08f, top + carLen * 0.008f + lh), lh, lh, p)
        p.color = 0xFFE53935.toInt()
        c.drawRoundRect(RectF(left + carW * 0.07f, bottom - carLen * 0.008f - lh, left + carW * 0.07f + lw, bottom - carLen * 0.008f), lh, lh, p)
        c.drawRoundRect(RectF(right - carW * 0.07f - lw, bottom - carLen * 0.008f - lh, right - carW * 0.07f, bottom - carLen * 0.008f), lh, lh, p)

        // obrys – jasny przy ciemnym lakierze (widać auto na ciemnej mapie)
        p.style = Paint.Style.STROKE
        p.strokeWidth = (h * 0.014f).coerceAtLeast(1.5f)
        p.color = if (luminance(color) < 0.35) Color.argb(210, 215, 222, 230) else Color.argb(200, 8, 10, 13)
        c.drawRoundRect(body, rx, ry, p)
        p.style = Paint.Style.FILL
        return bmp
    }
}
