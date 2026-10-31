package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mały silnik 3D (programowy, z-bufor): modele auta i ludzika renderowane do ikon mapy.
 * Auto renderowane z wielu kątów – na mapie wybierana klatka zgodna z kierunkiem jazdy względem kamery.
 */
object Model3D {

    class V(val x: Double, val y: Double, val z: Double)
    class Face(val pts: List<V>, val color: Int, val glass: Boolean = false, var nx: Double = 0.0, var ny: Double = 0.0, var nz: Double = 0.0)

    /** Część bryły – normalne ścian ustawiane na zewnątrz względem jej środka. */
    private class Part(val faces: MutableList<Face> = ArrayList()) {
        fun finish(out: MutableList<Face>) {
            var cx = 0.0; var cy = 0.0; var cz = 0.0; var n = 0
            faces.forEach { f -> f.pts.forEach { cx += it.x; cy += it.y; cz += it.z; n++ } }
            cx /= n; cy /= n; cz /= n
            for (f in faces) {
                // normalna metodą Newella
                var nx = 0.0; var ny = 0.0; var nz = 0.0
                for (i in f.pts.indices) {
                    val a = f.pts[i]; val b = f.pts[(i + 1) % f.pts.size]
                    nx += (a.y - b.y) * (a.z + b.z)
                    ny += (a.z - b.z) * (a.x + b.x)
                    nz += (a.x - b.x) * (a.y + b.y)
                }
                val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9 } ?: continue
                nx /= l; ny /= l; nz /= l
                var fx = 0.0; var fy = 0.0; var fz = 0.0
                f.pts.forEach { fx += it.x; fy += it.y; fz += it.z }
                fx /= f.pts.size; fy /= f.pts.size; fz /= f.pts.size
                if ((fx - cx) * nx + (fy - cy) * ny + (fz - cz) * nz < 0) { nx = -nx; ny = -ny; nz = -nz }
                f.nx = nx; f.ny = ny; f.nz = nz
                out += f
            }
        }
    }

    /** Graniastosłup: wielokąt w rzucie z góry (x,y), dół z0, góra zTop(x,y). */
    private fun prism(poly: List<Pair<Double, Double>>, z0: Double, zTop: (Double, Double) -> Double, side: Int, top: Int, out: MutableList<Face>) {
        val p = Part()
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            p.faces += Face(listOf(V(a.first, a.second, z0), V(b.first, b.second, z0), V(b.first, b.second, zTop(b.first, b.second)), V(a.first, a.second, zTop(a.first, a.second))), side)
        }
        p.faces += Face(poly.map { V(it.first, it.second, zTop(it.first, it.second)) }, top)
        p.finish(out)
    }

    private fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, color: Int, out: MutableList<Face>, topColor: Int = color) =
        prism(listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1), z0, { _, _ -> z1 }, color, topColor, out)

    /** Ostrosłup ścięty: dolny prostokąt → górny prostokąt (kabina). */
    private fun frustum(
        bx: Double, by0: Double, by1: Double, z0: Double,
        tx: Double, ty0: Double, ty1: Double, z1: Double,
        front: Int, rear: Int, sides: Int, top: Int, out: MutableList<Face>,
    ) {
        val p = Part()
        val b = listOf(V(-bx, by0, z0), V(bx, by0, z0), V(bx, by1, z0), V(-bx, by1, z0))
        val t = listOf(V(-tx, ty0, z1), V(tx, ty0, z1), V(tx, ty1, z1), V(-tx, ty1, z1))
        p.faces += Face(listOf(b[0], b[1], t[1], t[0]), rear, glass = rear != top)
        p.faces += Face(listOf(b[2], b[3], t[3], t[2]), front, glass = true)
        p.faces += Face(listOf(b[1], b[2], t[2], t[1]), sides, glass = true)
        p.faces += Face(listOf(b[3], b[0], t[0], t[3]), sides, glass = true)
        p.faces += Face(t, top)
        p.finish(out)
    }

    /** Koło: ośmiokąt w płaszczyźnie y-z, wyciągnięty wzdłuż x. */
    private fun wheel(xIn: Double, xOut: Double, yc: Double, zc: Double, r: Double, out: MutableList<Face>) {
        val p = Part()
        val n = 10
        val ring = (0 until n).map { k -> val a = 2 * PI * k / n; Pair(yc + r * cos(a), zc + r * sin(a)) }
        for (i in 0 until n) {
            val a = ring[i]; val b = ring[(i + 1) % n]
            p.faces += Face(listOf(V(xIn, a.first, a.second), V(xIn, b.first, b.second), V(xOut, b.first, b.second), V(xOut, a.first, a.second)), 0xFF14171C.toInt())
        }
        p.faces += Face(ring.map { V(xOut, it.first, it.second) }, 0xFF3A4048.toInt())
        p.faces += Face(ring.reversed().map { V(xIn, it.first, it.second) }, 0xFF14171C.toInt())
        p.finish(out)
    }

    private fun shade(c: Int, f: Double): Int {
        fun ch(v: Int) = (if (f >= 1.0) v + (255 - v) * (f - 1.0) else v * f).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
    }

    // ---------- modele ----------

    private class Spec(
        val len: Double, val wid: Double, val belt: Double, val roof: Double,
        val cabRear: Double, val cabFront: Double, val roofRear: Double, val roofFront: Double,
        val pickup: Boolean = false, val sedanTrunk: Boolean = false,
    )

    private fun spec(body: String?) = when (body) {
        "hatchback" -> Spec(4.1, 1.78, 0.92, 1.46, -1.85, 0.72, -1.62, -0.05)
        "sedan" -> Spec(4.75, 1.80, 0.92, 1.42, -1.15, 0.85, -0.78, 0.15, sedanTrunk = true)
        "kombi" -> Spec(4.75, 1.80, 0.93, 1.48, -2.18, 0.85, -2.05, 0.10)
        "suv" -> Spec(4.6, 1.90, 1.08, 1.72, -2.12, 0.95, -1.98, 0.25)
        "van" -> Spec(5.2, 2.00, 1.05, 2.25, -2.58, 1.95, -2.58, 1.45)
        "pickup" -> Spec(5.3, 1.90, 1.05, 1.82, -0.62, 1.10, -0.52, 0.45, pickup = true)
        "coupe" -> Spec(4.5, 1.82, 0.86, 1.32, -1.15, 0.70, -0.58, 0.0, sedanTrunk = true)
        else -> Spec(4.45, 1.82, 0.98, 1.68, -2.08, 1.35, -1.98, 0.55) // minivan
    }

    fun car(body: String?, paint: Int): List<Face> {
        val s = spec(body)
        val out = ArrayList<Face>()
        val hl = s.len / 2; val hw = s.wid / 2
        val c = 0.30; val c2 = 0.42
        val outline = listOf(
            -hw + c to hl, hw - c to hl, hw to hl - c2, hw to -hl + c2, hw - c to -hl, -hw + c to -hl, -hw to -hl + c2, -hw to hl - c2,
        )
        val bodyTop = { _: Double, y: Double ->
            when {
                y > s.cabFront -> s.belt - 0.16 * (y - s.cabFront) / (hl - s.cabFront)   // maska opada do przodu
                y < s.cabRear -> s.belt - (if (s.sedanTrunk) 0.04 else 0.10) * (s.cabRear - y) / (s.cabRear + hl).coerceAtLeast(0.01)
                else -> s.belt
            }
        }
        val glass = 0xFF1C2635.toInt()
        // koła (pod nadwoziem)
        val wb = s.len * 0.31
        for (sx in listOf(-1.0, 1.0)) for (wy in listOf(-wb, wb)) {
            wheel(sx * (hw - 0.30), sx * (hw - 0.04), wy, 0.33, 0.33, out)
        }
        prism(outline, 0.30, bodyTop, shade(paint, 0.92), paint, out)
        // kabina
        frustum(
            hw - 0.07, s.cabRear, s.cabFront, s.belt - 0.005,
            hw - 0.22, s.roofRear, s.roofFront, s.roof,
            glass, if (s.pickup) glass else glass, glass, shade(paint, 1.06), out,
        )
        // skrzynia pickupa
        if (s.pickup) {
            box(-hw + 0.12, hw - 0.12, -hl + 0.14, s.cabRear - 0.10, s.belt - 0.30, s.belt + 0.004, shade(paint, 0.45), out)
        }
        // światła
        val ly = 0.012
        for (sx in listOf(-1.0, 1.0)) {
            box(sx * 0.42 - 0.20, sx * 0.42 + 0.20, hl - c2 * 0.15, hl + ly, bodyTop(0.0, hl) - 0.22, bodyTop(0.0, hl) - 0.08, 0xFFFFF2CC.toInt(), out)
            box(sx * 0.48 - 0.22, sx * 0.48 + 0.22, -hl - ly, -hl + c2 * 0.15, s.belt - 0.30, s.belt - 0.16, 0xFFE53935.toInt(), out)
        }
        return out
    }

    fun person(shirt: Int): List<Face> {
        val out = ArrayList<Face>()
        val pants = 0xFF2B3442.toInt()
        val skin = 0xFFE2B48F.toInt()
        box(-0.17, -0.03, -0.08, 0.08, 0.0, 0.84, pants, out)
        box(0.03, 0.17, -0.08, 0.08, 0.0, 0.84, pants, out)
        box(-0.21, 0.21, -0.12, 0.12, 0.84, 1.42, shirt, out)
        box(-0.32, -0.21, -0.07, 0.07, 0.86, 1.40, shade(shirt, 0.85), out)
        box(0.21, 0.32, -0.07, 0.07, 0.86, 1.40, shade(shirt, 0.85), out)
        box(-0.05, 0.05, -0.05, 0.05, 1.42, 1.50, skin, out)
        // głowa: niska siatka kuli, górna część to włosy
        val p = Part()
        val cz = 1.64; val r = 0.15
        val lat = 6; val lon = 10
        for (i in 0 until lat) for (j in 0 until lon) {
            fun pt(a: Int, b: Int): V {
                val th = PI * a / lat
                val ph = 2 * PI * b / lon
                return V(r * sin(th) * cos(ph), r * sin(th) * sin(ph), cz + r * cos(th))
            }
            val q = listOf(pt(i, j), pt(i, j + 1), pt(i + 1, j + 1), pt(i + 1, j))
            p.faces += Face(q, if (i < 2) 0xFF2A1F18.toInt() else skin)
        }
        p.finish(out)
        return out
    }

    // ---------- renderowanie ----------

    /**
     * @param yawDeg kierunek modelu względem kamery (0 = tyłem do patrzącego, zgodnie z ruchem wskazówek zegara)
     * @param elevDeg kąt patrzenia nad horyzontem (90 = z góry)
     * @param metersAcross ile metrów mieści się w szerokości obrazka
     */
    fun render(
        faces: List<Face>, yawDeg: Double, elevDeg: Double, sizePx: Int, metersAcross: Double,
        shadowW: Double = 1.0, shadowL: Double = 2.2,
        /** gdzie w pionie leży punkt na ziemi (0.5 = środek, 0.88 = przy dole – ludzik) */
        originY: Double = 0.5,
        /** jasny obrys sylwetki (ciemny lakier na ciemnej mapie) */
        rim: Int? = null,
    ): Bitmap {
        val ss = 2
        val n = (sizePx * ss).coerceAtLeast(8)
        val scale = n / metersAcross
        val a = Math.toRadians(yawDeg)
        val e = Math.toRadians(elevDeg)
        val ca = cos(a); val sa = sin(a); val ce = cos(e); val se = sin(e)
        // światło: z góry, z lewej, od strony patrzącego
        var lx = -0.45; var ly = -0.55; var lz = 1.0
        val ll = sqrt(lx * lx + ly * ly + lz * lz); lx /= ll; ly /= ll; lz /= ll
        val color = IntArray(n * n)
        val depth = FloatArray(n * n) { Float.MAX_VALUE }

        for (f in faces) {
            // obrót normalnej
            val nx = f.nx * ca + f.ny * sa
            val ny = -f.nx * sa + f.ny * ca
            val nz = f.nz
            // widoczność: normalna w stronę kamery (0, -cos e, sin e)
            if (-ny * ce + nz * se <= 0.0) continue
            val diff = max(0.0, nx * lx + ny * ly + nz * lz)
            var k = 0.52 + 0.55 * diff
            if (f.glass) k = 0.75 + 0.6 * diff * diff
            val col = shade(f.color, k.coerceIn(0.2, 1.6))
            // rzutowanie
            val px = DoubleArray(f.pts.size); val py = DoubleArray(f.pts.size); val pz = DoubleArray(f.pts.size)
            for (i in f.pts.indices) {
                val v = f.pts[i]
                val x = v.x * ca + v.y * sa
                val y = -v.x * sa + v.y * ca
                px[i] = n / 2.0 + x * scale
                py[i] = n * originY - (y * se + v.z * ce) * scale
                pz[i] = y * ce - v.z * se
            }
            for (t in 1 until f.pts.size - 1) tri(px[0], py[0], pz[0], px[t], py[t], pz[t], px[t + 1], py[t + 1], pz[t + 1], col, n, color, depth)
        }

        if (rim != null) {
            val src = color.copyOf()
            val offs = intArrayOf(1, -1, n, -n, 2, -2, 2 * n, -2 * n)
            for (py in 2 until n - 2) for (px in 2 until n - 2) {
                val i = py * n + px
                if (src[i] != 0) continue
                for (d in offs) if (src[i + d] != 0) { color[i] = rim; break }
            }
        }
        val big = Bitmap.createBitmap(color, n, n, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // cień na ziemi (rzut elipsy pod modelem)
        val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(150, 0, 0, 0)
            maskFilter = BlurMaskFilter(sizePx * 0.045f, BlurMaskFilter.Blur.NORMAL)
        }
        val sc = scale / ss
        val wX = (abs(ca) * shadowW + abs(sa) * shadowL) * sc / 2
        val wY = (abs(sa) * shadowW + abs(ca) * shadowL) * se * sc / 2
        val oy = sizePx * originY
        c.drawOval(RectF((sizePx / 2f - wX).toFloat(), (oy - wY).toFloat(), (sizePx / 2f + wX).toFloat(), (oy + wY).toFloat()), sh)
        c.drawBitmap(Bitmap.createScaledBitmap(big, sizePx, sizePx, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        big.recycle()
        return out
    }

    private fun tri(
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double,
        col: Int, n: Int, color: IntArray, depth: FloatArray,
    ) {
        val minX = max(0, min(x0, min(x1, x2)).toInt())
        val maxX = min(n - 1, max(x0, max(x1, x2)).toInt() + 1)
        val minY = max(0, min(y0, min(y1, y2)).toInt())
        val maxY = min(n - 1, max(y0, max(y1, y2)).toInt() + 1)
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (abs(area) < 1e-9) return
        for (py in minY..maxY) {
            val sy = py + 0.5
            for (px in minX..maxX) {
                val sx = px + 0.5
                val w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area
                val w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area
                val w2 = 1.0 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = (w0 * z0 + w1 * z1 + w2 * z2).toFloat()
                val i = py * n + px
                if (z < depth[i]) {
                    depth[i] = z
                    color[i] = col
                }
            }
        }
    }

    /** Wymiary modelu auta do cienia (szerokość, długość w metrach). */
    fun carSize(body: String?): Pair<Double, Double> = spec(body).let { it.wid to it.len }

    private fun lum(c: Int) = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    /** Gotowa ikona auta 3D (ciemne lakiery z jasnym obrysem). */
    fun carIcon(body: String?, paint: Int, yawDeg: Double, elevDeg: Double, sizePx: Int, faces: List<Face> = car(body, paint)): Bitmap {
        val (w, l) = carSize(body)
        return render(faces, yawDeg, elevDeg, sizePx, 6.0, w, l, 0.5, if (lum(paint) < 0.35) Color.argb(235, 200, 210, 222) else null)
    }

    /** Ludzik 3D (Twoja pozycja w telefonie). Punkt na ziemi przy dole obrazka. */
    fun personIcon(shirt: Int, sizePx: Int): Bitmap = render(person(shirt), 25.0, 30.0, sizePx, 2.4, 0.6, 0.5, 0.88)
}
