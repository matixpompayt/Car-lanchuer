package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Ikony znaków na mapę (rysowane, bez plików graficznych). */
object SignIcons {
    val KINDS = listOf("signals", "stop", "give_way", "crossing", "rail", "camera")

    fun bitmap(kind: String, sizePx: Int): Bitmap {
        val s = sizePx.toFloat()
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        when (kind) {
            "signals" -> {
                val w = s * 0.42f
                val r = RectF((s - w) / 2, s * 0.04f, (s + w) / 2, s * 0.96f)
                c.drawRoundRect(RectF(r.left + 2, r.top + 3, r.right + 2, r.bottom + 3), w * 0.3f, w * 0.3f, shadow)
                p.color = Color.rgb(25, 28, 32); c.drawRoundRect(r, w * 0.3f, w * 0.3f, p)
                val cols = listOf(Color.rgb(235, 55, 50), Color.rgb(250, 190, 40), Color.rgb(60, 205, 90))
                for (i in 0..2) { p.color = cols[i]; c.drawCircle(s / 2, s * (0.22f + i * 0.28f), w * 0.3f, p) }
            }
            "stop" -> {
                val path = Path()
                for (i in 0 until 8) {
                    val a = Math.toRadians(22.5 + i * 45.0)
                    val x = (s / 2 + Math.cos(a) * s * 0.47).toFloat(); val y = (s / 2 + Math.sin(a) * s * 0.47).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.color = Color.WHITE; c.drawPath(path, p)
                c.save(); c.scale(0.88f, 0.88f, s / 2, s / 2); p.color = Color.rgb(210, 30, 35); c.drawPath(path, p); c.restore()
                p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true; p.textSize = s * 0.3f
                c.drawText("STOP", s / 2, s / 2 + s * 0.105f, p)
            }
            "give_way" -> {
                val path = Path().apply { moveTo(s * 0.04f, s * 0.12f); lineTo(s * 0.96f, s * 0.12f); lineTo(s / 2, s * 0.92f); close() }
                p.color = Color.rgb(210, 30, 35); c.drawPath(path, p)
                val inner = Path().apply { moveTo(s * 0.22f, s * 0.23f); lineTo(s * 0.78f, s * 0.23f); lineTo(s / 2, s * 0.72f); close() }
                p.color = Color.WHITE; c.drawPath(inner, p)
            }
            "crossing" -> {
                val r = RectF(s * 0.06f, s * 0.06f, s * 0.94f, s * 0.94f)
                p.color = Color.WHITE; c.drawRoundRect(r, s * 0.12f, s * 0.12f, p)
                p.color = Color.rgb(25, 95, 200); c.drawRoundRect(RectF(s * 0.11f, s * 0.11f, s * 0.89f, s * 0.89f), s * 0.1f, s * 0.1f, p)
                val tri = Path().apply { moveTo(s / 2, s * 0.18f); lineTo(s * 0.84f, s * 0.8f); lineTo(s * 0.16f, s * 0.8f); close() }
                p.color = Color.WHITE; c.drawPath(tri, p)
                p.color = Color.rgb(20, 20, 20)
                for (i in 0..2) c.drawRect(s * (0.3f + i * 0.15f), s * 0.6f, s * (0.37f + i * 0.15f), s * 0.76f, p)
                c.drawCircle(s / 2, s * 0.4f, s * 0.06f, p)
            }
            "rail" -> {
                p.color = Color.rgb(210, 30, 35); p.strokeWidth = s * 0.17f; p.strokeCap = Paint.Cap.ROUND
                c.drawLine(s * 0.14f, s * 0.14f, s * 0.86f, s * 0.86f, p)
                c.drawLine(s * 0.86f, s * 0.14f, s * 0.14f, s * 0.86f, p)
                p.color = Color.WHITE; p.strokeWidth = s * 0.07f
                c.drawLine(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f, p)
                c.drawLine(s * 0.8f, s * 0.2f, s * 0.2f, s * 0.8f, p)
            }
            else -> { // camera
                p.color = Color.rgb(250, 190, 40); c.drawRoundRect(RectF(s * 0.06f, s * 0.2f, s * 0.94f, s * 0.8f), s * 0.12f, s * 0.12f, p)
                p.color = Color.rgb(25, 28, 32); c.drawCircle(s / 2, s / 2, s * 0.2f, p)
                p.color = Color.WHITE; c.drawCircle(s / 2, s / 2, s * 0.08f, p)
            }
        }
        return b
    }
}
