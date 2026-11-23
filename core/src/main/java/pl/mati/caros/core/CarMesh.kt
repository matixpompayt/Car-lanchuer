package pl.mati.caros.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modele 3D aut (i ludzika) dla CAR OS i CAR OS Connect – czysty Kotlin, bez Androida.
 *
 * Siatka: karoseria jako „loft” – gęste przekroje poprzeczne (dół z blachy i kabina z szybami, słupkami i dachem),
 * z gładkimi normalnymi w wierzchołkach. Rysowanie: programowy rasteryzator z z-buforem i cieniowaniem na piksel
 * (normalne interpolowane jak w grach) – lakier z lakierem bezbarwnym (odbicia „studia” z efektem Fresnela,
 * ostry odblask), szyby odbijające softboxy, metaliczne felgi, matowe opony, przyciemnienie przy ziemi.
 * Bitmapę z cieniem robi [Model3D].
 *
 * Układ: x – w bok (prawo +), y – wzdłuż auta (przód +), z – w górę, metry, środek auta w (0, 0).
 */
object CarMesh {
    const val PAINT = 0
    const val GLASS = 1
    const val TRIM = 2
    const val RIM = 3
    const val TIRE = 4
    const val LIGHT_F = 5
    const val LIGHT_R = 6
    const val PLATE = 7
    const val MATTE = 8

    class V(val x: Double, val y: Double, val z: Double)

    /** Ściana: [vn] – normalne w wierzchołkach (x, y, z po kolei) do gładkiego cieniowania; null = płaska. */
    class Face(val pts: List<V>, val color: Int, val mat: Int = PAINT) {
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        var minZ = 0.0
        var vn: DoubleArray? = null
    }

    // ---------- budowanie ----------

    /** Ściana z normalną (Newell) skierowaną od punktu [cx, cy, cz]; [vn] – opcjonalne normalne wierzchołków. */
    private fun add(out: MutableList<Face>, pts: List<V>, color: Int, mat: Int, cx: Double, cy: Double, cz: Double, vn: List<DoubleArray?>? = null) {
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            nx += (a.y - b.y) * (a.z + b.z)
            ny += (a.z - b.z) * (a.x + b.x)
            nz += (a.x - b.x) * (a.y + b.y)
        }
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        if (l < 1e-12) return
        nx /= l; ny /= l; nz /= l
        var fx = 0.0; var fy = 0.0; var fz = 0.0
        var mz = Double.MAX_VALUE
        for (p in pts) { fx += p.x; fy += p.y; fz += p.z; mz = min(mz, p.z) }
        fx /= pts.size; fy /= pts.size; fz /= pts.size
        if ((fx - cx) * nx + (fy - cy) * ny + (fz - cz) * nz < 0) { nx = -nx; ny = -ny; nz = -nz }
        val f = Face(pts, color, mat)
        f.nx = nx; f.ny = ny; f.nz = nz; f.minZ = mz
        if (vn != null) {
            val arr = DoubleArray(pts.size * 3)
            for (i in pts.indices) {
                var a = vn[i]
                if (a == null || a[0].isNaN()) a = doubleArrayOf(nx, ny, nz)
                // normalna wierzchołka po tej samej stronie co ściana
                val s = if (a[0] * nx + a[1] * ny + a[2] * nz < 0) -1.0 else 1.0
                arr[i * 3] = a[0] * s; arr[i * 3 + 1] = a[1] * s; arr[i * 3 + 2] = a[2] * s
            }
            f.vn = arr
        }
        out += f
    }

    private fun box(out: MutableList<Face>, x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double, color: Int, mat: Int) {
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2; val cz = (z0 + z1) / 2
        fun q(vararg p: V) = add(out, p.toList(), color, mat, cx, cy, cz)
        q(V(x0, y0, z0), V(x1, y0, z0), V(x1, y0, z1), V(x0, y0, z1))
        q(V(x0, y1, z0), V(x1, y1, z0), V(x1, y1, z1), V(x0, y1, z1))
        q(V(x0, y0, z0), V(x0, y1, z0), V(x0, y1, z1), V(x0, y0, z1))
        q(V(x1, y0, z0), V(x1, y1, z0), V(x1, y1, z1), V(x1, y0, z1))
        q(V(x0, y0, z1), V(x1, y0, z1), V(x1, y1, z1), V(x0, y1, z1))
        q(V(x0, y0, z0), V(x1, y0, z0), V(x1, y1, z0), V(x0, y1, z0))
    }

    private fun smooth(t: Double): Double { val c = t.coerceIn(0.0, 1.0); return c * c * (3 - 2 * c) }
    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    /**
     * Gładkie normalne siatki przekrojów: [g][i][j] = (x, z) punktu j w przekroju i (na wysokości y = [ys][i]).
     * [closed] – przekrój zamknięty (dół nadwozia) albo otwarty (kabina); [center] – wysokość środka przekroju.
     */
    private fun gridNormals(ys: DoubleArray, g: List<List<Pair<Double, Double>>>, closed: Boolean, center: (Int) -> Double): Array<Array<DoubleArray?>> {
        val n = ys.size; val m = g[0].size
        return Array(n) { i ->
            Array(m) { j ->
                val ia = max(0, i - 1); val ib = min(n - 1, i + 1)
                val ja = if (closed) (j - 1 + m) % m else max(0, j - 1)
                val jb = if (closed) (j + 1) % m else min(m - 1, j + 1)
                // styczna wzdłuż auta i wzdłuż przekroju
                val tx = g[ib][j].first - g[ia][j].first; val ty = ys[ib] - ys[ia]; val tz = g[ib][j].second - g[ia][j].second
                val rx = g[i][jb].first - g[i][ja].first; val rz = g[i][jb].second - g[i][ja].second
                var nx = ty * rz
                var ny = tz * rx - tx * rz
                var nz = -ty * rx
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-9) null else {
                    nx /= l; ny /= l; nz /= l
                    // na zewnątrz względem środka przekroju
                    val px = g[i][j].first; val pz = g[i][j].second - center(i)
                    if (px * nx + pz * nz < 0) { nx = -nx; ny = -ny; nz = -nz }
                    doubleArrayOf(nx, ny, nz)
                }
            }
        }
    }

    // ---------- sylwetki ----------

    /**
     * [len], [wid], [h] – długość, szerokość, wysokość; [belt] – linia okien; [hoodF] – maska przy zderzaku;
     * [ws] – podstawa przedniej szyby (y), [roofF]/[roofR] – płaski dach od/do (y), [rear] – podstawa tylnej szyby (y),
     * [deck] – wysokość klapy bagażnika (sedan), [notch] – osobny bagażnik, [r] – promień koła,
     * [fo]/[ro] – zwisy (oś przednia / tylna od końca), [clear] – prześwit, [clad] – czarne nakładki (SUV),
     * [bed] – skrzynia (pickup), [doors4] – słupek B, [quarter] – boczna szyba za słupkiem C.
     */
    private class Prof(
        val len: Double, val wid: Double, val h: Double, val belt: Double, val hoodF: Double,
        val ws: Double, val roofF: Double, val roofR: Double, val rear: Double,
        val deck: Double, val notch: Boolean, val r: Double, val fo: Double, val ro: Double, val clear: Double,
        val clad: Boolean = false, val bed: Boolean = false, val doors4: Boolean = true, val quarter: Boolean = false,
    )

    private fun prof(body: String?): Prof = when (body) {
        "hatchback" -> Prof(4.26, 1.79, 1.47, 0.97, 0.78, 1.06, 0.20, -1.30, -1.86, 0.97, false, 0.32, 0.88, 0.74, 0.19)
        "sedan" -> Prof(4.80, 1.83, 1.46, 0.97, 0.76, 1.24, 0.38, -0.86, -1.52, 1.02, true, 0.33, 0.95, 1.05, 0.19)
        "kombi" -> Prof(4.80, 1.83, 1.50, 0.97, 0.76, 1.24, 0.38, -1.98, -2.30, 0.97, false, 0.33, 0.95, 1.05, 0.19, quarter = true)
        "suv" -> Prof(4.52, 1.86, 1.68, 1.10, 0.93, 1.18, 0.33, -1.60, -2.04, 1.10, false, 0.36, 0.92, 0.94, 0.25, clad = true, quarter = true)
        "van" -> Prof(5.00, 1.95, 1.98, 1.10, 0.96, 1.92, 1.36, -2.34, -2.46, 1.10, false, 0.34, 0.92, 1.05, 0.21, quarter = true)
        "pickup" -> Prof(5.30, 1.86, 1.80, 1.14, 1.02, 1.38, 0.64, -0.44, -0.56, 1.14, false, 0.38, 0.98, 1.30, 0.27, clad = true, bed = true)
        "coupe" -> Prof(4.56, 1.85, 1.33, 0.92, 0.74, 1.10, 0.12, -0.62, -1.66, 0.98, true, 0.33, 0.92, 0.95, 0.17, doors4 = false)
        else -> Prof(4.53, 1.83, 1.66, 1.04, 0.85, 1.31, 0.44, -1.78, -2.12, 1.04, false, 0.33, 0.92, 0.94, 0.20, quarter = true) // minivan
    }

    /** Wymiary do cienia (szerokość, długość). */
    fun size(body: String?): Pair<Double, Double> = prof(body).let { it.wid to it.len }

    fun car(body: String?, paint: Int): List<Face> {
        val p = prof(body)
        val out = ArrayList<Face>()
        val hl = p.len / 2
        val hw0 = p.wid / 2

        // szerokość w rzucie z góry: zaokrąglone narożniki przodu i tyłu
        fun halfW(y: Double): Double {
            val rf = 0.52; val rr = 0.44
            val s = when {
                y > hl - rf -> ((y - (hl - rf)) / rf) * 0.93
                y < -hl + rr -> ((-hl + rr - y) / rr) * 0.93
                else -> 0.0
            }
            return hw0 * (1 - s.pow(2.2)).pow(1 / 2.2)
        }

        // górna krawędź blachy (maska, linia okien, bagażnik)
        fun top(y: Double): Double = when {
            y >= p.ws -> {
                val t = (y - p.ws) / (hl - p.ws)
                val z = lerp(p.belt - 0.03, p.hoodF, smooth(t * 1.05))
                z - 0.10 * smooth((t - 0.80) / 0.20) // zaokrąglony nos
            }
            p.notch && y <= p.rear -> {
                val t = (p.rear - y) / (p.rear + hl)
                lerp(p.belt + 0.02, p.deck, smooth(t * 1.6)) - 0.08 * smooth((t - 0.78) / 0.22)
            }
            y < -hl + 0.28 -> p.belt - 0.06 * smooth((-hl + 0.28 - y) / 0.28)
            else -> p.belt
        }

        fun bottom(y: Double): Double {
            val e = max(smooth((y - (hl - 0.45)) / 0.45), smooth((-hl + 0.40 - y) / 0.40))
            return p.clear + 0.10 * e
        }

        // ---- dół nadwozia: 20 punktów w przekroju, gęsto przy końcach ----
        val n = 40
        val ys = DoubleArray(n) { i -> -hl + p.len * (1 - cos(PI * i / (n - 1))) / 2 }
        fun ring(y: Double): List<Pair<Double, Double>> {
            val hw = halfW(y); val zb = bottom(y); val zt = top(y); val hh = zt - zb
            val r = listOf(
                0.80 * hw to zb, hw - 0.06 to zb + 0.012, hw - 0.02 to zb + 0.06, hw to zb + 0.18 * hh, hw to zb + 0.42 * hh,
                hw - 0.008 to zb + 0.62 * hh, hw - 0.022 to zb + 0.80 * hh, hw - 0.055 to zt - 0.025, hw - 0.10 to zt - 0.004, hw - 0.17 to zt,
            )
            return r + r.reversed().map { -it.first to it.second }
        }
        val rings = ys.map { ring(it) }
        val segs = rings[0].size
        val nrm = gridNormals(ys, rings, true) { i -> (bottom(ys[i]) + top(ys[i])) / 2 }
        for (i in 0 until n - 1) {
            val y0 = ys[i]; val y1 = ys[i + 1]; val ym = (y0 + y1) / 2
            val a = rings[i]; val b = rings[i + 1]
            val zc = (bottom(ym) + top(ym)) / 2
            for (j in 0 until segs) {
                val k = (j + 1) % segs
                val pts = listOf(V(a[j].first, y0, a[j].second), V(a[k].first, y0, a[k].second), V(b[k].first, y1, b[k].second), V(b[j].first, y1, b[j].second))
                // j: 0..8 prawa strona od dołu (0..2 – zaokrąglenie dołu, 3..4 – drzwi, 5..6 – nad linią ramion, 7..8 – narożnik),
                // 9 – góra (maska / blacha pod szybami), 10..18 – lewa strona odwrotnie, 19 – spód
                val side = when { j <= 9 -> j; j <= 18 -> 18 - j; else -> -1 }
                val mat = when {
                    j == segs - 1 -> TRIM                                                    // spód
                    (side == 5 || side == 6) && ym > hl - 0.30 && ym < hl - 0.012 -> LIGHT_F  // reflektory (owinięte na bok)
                    (side == 5 || side == 6) && ym < -hl + 0.22 && ym > -hl + 0.008 -> LIGHT_R // lampy tylne
                    p.clad && side in 0..3 -> TRIM                                           // nakładki SUV
                    side in 0..1 && (ym > hl - 0.30 || ym < -hl + 0.28) -> TRIM              // dolne zderzaki
                    p.bed && j == 9 && ym < p.rear - 0.04 && ym > -hl + 0.06 -> TRIM         // skrzynia (zakryta)
                    else -> PAINT
                }
                add(out, pts, if (mat == PAINT) paint else 0, mat, 0.0, ym, zc, listOf(nrm[i][j], nrm[i][k], nrm[i + 1][k], nrm[i + 1][j]))
            }
        }
        // czoło i tył (zaślepki), atrapa, tablice
        val zMid = (p.clear + p.belt) / 2
        add(out, rings[n - 1].map { V(it.first, ys[n - 1], it.second) }, paint, PAINT, 0.0, 0.0, zMid)
        add(out, rings[0].map { V(it.first, ys[0], it.second) }, paint, PAINT, 0.0, 0.0, zMid)
        run {
            val y = ys[n - 1] + 0.004
            val hw = halfW(ys[n - 1]); val zb = bottom(ys[n - 1]); val zt = top(ys[n - 1]); val hh = zt - zb
            add(out, listOf(V(-0.66 * hw, y, zb + 0.30 * hh), V(0.66 * hw, y, zb + 0.30 * hh), V(0.58 * hw, y, zb + 0.66 * hh), V(-0.58 * hw, y, zb + 0.66 * hh)), 0, TRIM, 0.0, 0.0, zMid)
            add(out, listOf(V(-0.26, y + 0.002, zb + 0.08 * hh), V(0.26, y + 0.002, zb + 0.08 * hh), V(0.26, y + 0.002, zb + 0.25 * hh), V(-0.26, y + 0.002, zb + 0.25 * hh)), 0, PLATE, 0.0, 0.0, zMid)
            val yr = ys[0] - 0.004
            val zbr = bottom(ys[0]); val ztr = top(ys[0]); val hr = ztr - zbr
            add(out, listOf(V(-0.26, yr, zbr + 0.40 * hr), V(0.26, yr, zbr + 0.40 * hr), V(0.26, yr, zbr + 0.60 * hr), V(-0.26, yr, zbr + 0.60 * hr)), 0, PLATE, 0.0, 0.0, zMid)
        }

        // ---- kabina: szyby, słupki, dach (gładko przechodzące) ----
        val gm = 34
        val gys = DoubleArray(gm) { i -> lerp(p.rear, p.ws, i / (gm - 1.0)) }
        fun roofZ(y: Double): Double {
            val zb = top(y) - 0.004
            return when {
                y >= p.roofF -> lerp(p.h, zb, ((y - p.roofF) / (p.ws - p.roofF)).coerceIn(0.0, 1.0).pow(1.18))
                y <= p.roofR -> lerp(p.h, zb, ((p.roofR - y) / (p.roofR - p.rear)).coerceIn(0.0, 1.0).pow(1.10))
                else -> p.h - 0.035 * ((2 * (y - p.roofR) / (p.roofF - p.roofR) - 1).pow(2))
            }
        }
        fun gring(y: Double): List<Pair<Double, Double>> {
            val zb = top(y) - 0.004; val zt = roofZ(y)
            val hb = halfW(y) - 0.075
            val f = ((zt - zb) / 0.45).coerceIn(0.0, 1.0)
            val ht = hb - 0.19 * f
            val r = listOf(hb to zb, ht + 0.012 to zt - 0.075 * f, ht - 0.022 to zt - 0.028 * f, ht - 0.07 to zt - 0.005 * f, ht - 0.12 to zt)
            return r + r.reversed().map { -it.first to it.second }
        }
        val grings = gys.map { gring(it) }
        val gn = gridNormals(gys, grings, false) { i -> top(gys[i]) }
        val bp = (p.roofF + p.roofR) / 2 + 0.10
        val gsegs = grings[0].size - 1 // 9: 0 – szyba boczna P, 1..3 – narożnik dachu, 4 – środek dachu, 5..7 – narożnik L, 8 – szyba L
        for (i in 0 until gm - 1) {
            val y0 = gys[i]; val y1 = gys[i + 1]; val ym = (y0 + y1) / 2
            val a = grings[i]; val b = grings[i + 1]
            val zc = top(ym)
            val region = when { ym >= p.roofF -> 'W'; ym <= p.roofR -> 'B'; else -> 'R' }
            for (j in 0 until gsegs) {
                val pts = listOf(V(a[j].first, y0, a[j].second), V(a[j + 1].first, y0, a[j + 1].second), V(b[j + 1].first, y1, b[j + 1].second), V(b[j].first, y1, b[j].second))
                val sideSeg = j == 0 || j == gsegs - 1
                val pillar = j == 1 || j == gsegs - 2
                val mat = when (region) {
                    'W' -> if (pillar) PAINT else GLASS                                            // przednia szyba, słupek A
                    'R' -> when {
                        !sideSeg -> PAINT                                                           // dach
                        p.doors4 && abs(ym - bp) < 0.065 -> TRIM                                    // słupek B
                        ym < p.roofR + (if (p.quarter) 0.10 else 0.26) -> PAINT                     // słupek C
                        else -> GLASS
                    }
                    else -> when {
                        !sideSeg && !pillar -> GLASS                                                // tylna szyba
                        sideSeg && p.quarter && ym > p.rear + 0.06 -> GLASS                         // szyba za słupkiem C
                        else -> PAINT
                    }
                }
                add(out, pts, if (mat == PAINT) paint else 0, mat, 0.0, ym, zc, listOf(gn[i][j], gn[i][j + 1], gn[i + 1][j + 1], gn[i + 1][j]))
            }
        }

        // ---- lusterka (przy podstawie słupka A) ----
        for (sx in listOf(-1.0, 1.0)) {
            val y = p.ws - 0.30
            val hw = halfW(y)
            val zb = top(y)
            val x0 = sx * (hw - 0.11); val x1 = sx * (hw + 0.10)
            box(out, min(x0, x1), max(x0, x1), y - 0.11, y + 0.01, zb + 0.02, zb + 0.13, paint, PAINT)
        }

        // ---- koła z felgami i nadkolami ----
        val axleF = hl - p.fo; val axleR = -hl + p.ro
        for (yc in listOf(axleF, axleR)) for (sx in listOf(-1.0, 1.0)) wheel(out, sx, hw0, yc, p.r, p.clad)
        return out
    }

    private fun wheel(out: MutableList<Face>, sx: Double, hw: Double, yc: Double, r: Double, clad: Boolean) {
        val zc = r
        val xo = sx * (hw + 0.006); val xi = sx * (hw - 0.21)
        val m = 28
        val ring = (0 until m).map { k -> val a = 2 * PI * k / m; Pair(yc + r * cos(a), zc + r * sin(a)) }
        val rn = (0 until m).map { k -> val a = 2 * PI * k / m; doubleArrayOf(0.0, cos(a), sin(a)) }
        val cx = (xo + xi) / 2
        val xs = sx * 0.035
        for (i in 0 until m) {
            val k = (i + 1) % m
            val a = ring[i]; val b = ring[k]
            // bieżnik (gładki walec)
            add(out, listOf(V(xi, a.first, a.second), V(xi, b.first, b.second), V(xo - xs, b.first, b.second), V(xo - xs, a.first, a.second)), 0, TIRE, cx, yc, zc, listOf(rn[i], rn[k], rn[k], rn[i]))
            // zaokrąglony bark opony
            val a2 = Pair(yc + (a.first - yc) * 0.93, zc + (a.second - zc) * 0.93)
            val b2 = Pair(yc + (b.first - yc) * 0.93, zc + (b.second - zc) * 0.93)
            val n1 = doubleArrayOf(sx * 0.7, rn[i][1] * 0.7, rn[i][2] * 0.7); val n2 = doubleArrayOf(sx * 0.7, rn[k][1] * 0.7, rn[k][2] * 0.7)
            add(out, listOf(V(xo - xs, a.first, a.second), V(xo - xs, b.first, b.second), V(xo, b2.first, b2.second), V(xo, a2.first, a2.second)), 0, TIRE, cx, yc, zc, listOf(rn[i], rn[k], n2, n1))
        }
        add(out, ring.map { V(xo, yc + (it.first - yc) * 0.93, zc + (it.second - zc) * 0.93) }, 0, TIRE, cx, yc, zc)
        add(out, ring.map { V(xi, it.first, it.second) }, 0, TIRE, cx, yc, zc)
        // felga: 10 klinów (5 ramion jasnych, 5 ciemnych wpuszczonych), obręcz i piasta
        val xr = xo + sx * 0.004
        val rr = r * 0.68
        val w = 10
        for (k in 0 until w) {
            val a0 = 2 * PI * k / w; val a1 = 2 * PI * (k + 1) / w
            val spoke = k % 2 == 0
            val inset = if (spoke) 0.0 else -sx * 0.03
            val pts = listOf(
                V(xr, yc + rr * 0.22 * cos(a0), zc + rr * 0.22 * sin(a0)),
                V(xr + inset, yc + rr * cos(a0), zc + rr * sin(a0)),
                V(xr + inset, yc + rr * cos((a0 + a1) / 2), zc + rr * sin((a0 + a1) / 2)),
                V(xr + inset, yc + rr * cos(a1), zc + rr * sin(a1)),
                V(xr, yc + rr * 0.22 * cos(a1), zc + rr * 0.22 * sin(a1)),
            )
            add(out, pts, 0, if (spoke) RIM else TRIM, 0.0, yc, zc)
        }
        for (k in 0 until 20) {
            val a0 = 2 * PI * k / 20; val a1 = 2 * PI * (k + 1) / 20
            add(
                out,
                listOf(V(xr, yc + rr * cos(a0), zc + rr * sin(a0)), V(xr, yc + rr * 1.10 * cos(a0), zc + rr * 1.10 * sin(a0)), V(xr, yc + rr * 1.10 * cos(a1), zc + rr * 1.10 * sin(a1)), V(xr, yc + rr * cos(a1), zc + rr * sin(a1))),
                0, RIM, 0.0, yc, zc,
            )
        }
        add(out, (0 until 12).map { k -> val a = 2 * PI * k / 12; V(xr + sx * 0.004, yc + rr * 0.24 * cos(a), zc + rr * 0.24 * sin(a)) }, 0, RIM, 0.0, yc, zc)
        // nadkole: ciemny łuk nad kołem
        val x = sx * (hw + 0.004)
        val r0 = r + 0.012; val r1 = r + (if (clad) 0.10 else 0.05)
        val steps = 18
        for (k in 0 until steps) {
            val a0 = PI * k / steps; val a1 = PI * (k + 1) / steps
            add(
                out,
                listOf(
                    V(x, yc + r0 * cos(a0), zc + r0 * sin(a0)), V(x, yc + r1 * cos(a0), zc + r1 * sin(a0)),
                    V(x, yc + r1 * cos(a1), zc + r1 * sin(a1)), V(x, yc + r0 * cos(a1), zc + r0 * sin(a1)),
                ),
                0, TRIM, 0.0, yc, zc,
            )
        }
    }

    fun person(shirt: Int): List<Face> {
        val out = ArrayList<Face>()
        val pants = 0xFF2B3442.toInt()
        val skin = 0xFFE2B48F.toInt()
        box(out, -0.17, -0.03, -0.08, 0.08, 0.0, 0.84, pants, MATTE)
        box(out, 0.03, 0.17, -0.08, 0.08, 0.0, 0.84, pants, MATTE)
        box(out, -0.21, 0.21, -0.12, 0.12, 0.84, 1.42, shirt, MATTE)
        box(out, -0.32, -0.21, -0.07, 0.07, 0.86, 1.40, shirt, MATTE)
        box(out, 0.21, 0.32, -0.07, 0.07, 0.86, 1.40, shirt, MATTE)
        box(out, -0.05, 0.05, -0.05, 0.05, 1.42, 1.50, skin, MATTE)
        val cz = 1.64; val r = 0.15
        val lat = 6; val lon = 10
        for (i in 0 until lat) for (j in 0 until lon) {
            fun pt(a: Int, b: Int): V {
                val th = PI * a / lat
                val ph = 2 * PI * b / lon
                return V(r * sin(th) * cos(ph), r * sin(th) * sin(ph), cz + r * cos(th))
            }
            add(out, listOf(pt(i, j), pt(i, j + 1), pt(i + 1, j + 1), pt(i + 1, j)), if (i < 2) 0xFF2A1F18.toInt() else skin, MATTE, 0.0, 0.0, cz)
        }
        return out
    }

    // ---------- cieniowanie ----------

    private fun ipow(x: Double, n: Int): Double { var r = 1.0; var b = x; var e = n; while (e > 0) { if (e and 1 == 1) r *= b; b *= b; e = e shr 1 }; return r }
    private fun sstep(a: Double, b: Double, x: Double): Double = smooth((x - a) / (b - a))

    /**
     * „Studio fotograficzne” (układ kamery: x – w prawo, y – w głąb, z – w górę): ciemna podłoga, jasny horyzont,
     * duży softbox nad autem, drugi z lewej i światło konturowe z prawej z tyłu. Zwraca jasność 0..~2.
     */
    private fun env(rx: Double, ry: Double, rz: Double): Double {
        if (rz < -0.02) return 0.05 + 0.12 * (1 + rz) * (1 + rz)
        var e = 0.28 + 0.18 * rz + 0.55 * exp(-(rz / 0.10) * (rz / 0.10))
        e += 1.6 * sstep(0.62, 0.80, rz) * (1 - sstep(0.30, 0.48, abs(rx)))                     // softbox z góry
        e += 0.9 * sstep(-0.55, -0.75, rx) * sstep(0.05, 0.2, rz) * (1 - sstep(0.55, 0.7, rz))   // softbox z lewej
        e += 0.55 * sstep(0.62, 0.82, rx) * sstep(0.1, 0.4, ry) * (1 - sstep(0.4, 0.6, rz))      // kontra z prawej z tyłu
        return e
    }

    private class Shader(val lx: Double, val ly: Double, val lz: Double, val vx: Double, val vy: Double, val vz: Double, val hx: Double, val hy: Double, val hz: Double)

    /** Kolor piksela (ARGB) dla normalnej (w układzie kamery) i wysokości nad ziemią [zw]. */
    private fun shade(s: Shader, mat: Int, base: Int, nx0: Double, ny0: Double, nz0: Double, zw: Double): Int {
        var nx = nx0; var ny = ny0; var nz = nz0
        val nl0 = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl0 > 1e-9) { nx /= nl0; ny /= nl0; nz /= nl0 }
        var nv = nx * s.vx + ny * s.vy + nz * s.vz
        if (nv < 0.02) nv = 0.02
        val nl = max(0.0, nx * s.lx + ny * s.ly + nz * s.lz)
        val nh = max(0.0, nx * s.hx + ny * s.hy + nz * s.hz)
        // odbicie kierunku patrzenia
        val rx = 2 * nv * nx - s.vx; val ry = 2 * nv * ny - s.vy; val rz = 2 * nv * nz - s.vz
        val fres = 0.04 + 0.96 * ipow(1 - nv, 5)
        val ao = 0.50 + 0.50 * smooth((zw - 0.04) / 0.55)
        val sky = 0.5 + 0.5 * nz
        val br = ((base shr 16) and 0xFF) / 255.0; val bg = ((base shr 8) and 0xFF) / 255.0; val bb = (base and 0xFF) / 255.0
        val r: Double; val g: Double; val b: Double
        when (mat) {
            PAINT -> {
                val d = (0.20 + 0.14 * sky + 0.72 * nl) * ao
                val coat = env(rx, ry, rz) * (0.06 + 0.94 * fres) * 0.95 * (0.6 + 0.4 * ao)
                val sp = 1.4 * ipow(nh, 96) + 0.25 * ipow(nh, 16)
                r = br * d + coat + sp; g = bg * d + coat + sp; b = bb * d + coat + sp
            }
            GLASS -> {
                val e = env(rx, ry, rz) * (0.30 + 0.70 * fres) * 0.85
                val sp = 2.0 * ipow(nh, 160)
                r = 0.035 + e + sp; g = 0.045 + e + sp; b = 0.06 + e * 1.04 + sp
            }
            TRIM -> {
                val d = (0.05 + 0.06 * nl) * ao
                val e = env(rx, ry, rz) * (0.03 + 0.25 * fres)
                r = d + e; g = d + e; b = d * 1.08 + e
            }
            RIM -> {
                val e = env(rx, ry, rz) * 0.55
                val d = 0.40 + 0.45 * nl
                val sp = 0.9 * ipow(nh, 48)
                r = 0.62 * d + e + sp; g = 0.64 * d + e + sp; b = 0.67 * d + e + sp
            }
            TIRE -> {
                val d = (0.045 + 0.07 * nl) * ao + 0.05 * ipow(nh, 6)
                r = d; g = d; b = d * 1.05
            }
            LIGHT_F -> {
                val e = env(rx, ry, rz) * (0.25 + 0.75 * fres)
                val sp = 1.6 * ipow(nh, 120)
                r = 0.62 + 0.25 * nl + e + sp; g = 0.66 + 0.25 * nl + e + sp; b = 0.72 + 0.25 * nl + e + sp
            }
            LIGHT_R -> {
                val e = env(rx, ry, rz) * (0.10 + 0.6 * fres)
                val sp = 1.2 * ipow(nh, 120)
                r = 0.55 + 0.25 * nl + e + sp; g = 0.04 + e * 0.6 + sp; b = 0.06 + e * 0.6 + sp
            }
            PLATE -> {
                val d = 0.55 + 0.40 * nl
                r = d * 0.93; g = d * 0.94; b = d * 0.95
            }
            else -> {
                val d = 0.45 + 0.6 * nl
                r = br * d; g = bg * d; b = bb * d
            }
        }
        return (0xFF shl 24) or (tm(r) shl 16) or (tm(g) shl 8) or tm(b)
    }

    /** Miękkie przejście w biel (bez „przepalonych” plam). */
    private fun tm(x: Double): Int {
        val y = if (x <= 0.82) x else 0.82 + 0.18 * (1 - exp(-(x - 0.82) / 0.18))
        return (y * 255).toInt().coerceIn(0, 255)
    }

    // ---------- rysowanie ----------

    /**
     * Piksele ARGB (n×n, przezroczyste tło). [yawDeg] – 0 = tyłem do patrzącego, zgodnie z ruchem wskazówek zegara,
     * [elevDeg] – kąt patrzenia nad horyzontem (90 = z góry), [metersAcross] – ile metrów na szerokość obrazka,
     * [originY] – gdzie w pionie leży punkt na ziemi (0.5 = środek), [rim] – jasny obrys sylwetki (albo null).
     */
    fun rasterize(faces: List<Face>, yawDeg: Double, elevDeg: Double, n: Int, metersAcross: Double, originY: Double = 0.5, rim: Int? = null): IntArray {
        val scale = n / metersAcross
        val a = Math.toRadians(yawDeg)
        val e = Math.toRadians(elevDeg)
        val ca = cos(a); val sa = sin(a); val ce = cos(e); val se = sin(e)
        val l = norm(-0.45, -0.62, 1.0)
        val vx = 0.0; val vy = -ce; val vz = se
        val h = norm(l[0] + vx, l[1] + vy, l[2] + vz)
        val sh = Shader(l[0], l[1], l[2], vx, vy, vz, h[0], h[1], h[2])
        val color = IntArray(n * n)
        val depth = FloatArray(n * n) { Float.MAX_VALUE }
        val cap = 64
        val px = DoubleArray(cap); val py = DoubleArray(cap); val pz = DoubleArray(cap); val wz = DoubleArray(cap)
        val nxs = DoubleArray(cap); val nys = DoubleArray(cap); val nzs = DoubleArray(cap)
        for (f in faces) {
            val fnx = f.nx * ca + f.ny * sa
            val fny = -f.nx * sa + f.ny * ca
            val fnz = f.nz
            if (-fny * ce + fnz * se <= 0.0) continue
            val m = f.pts.size
            if (m > cap) continue
            val vn = f.vn
            for (i in 0 until m) {
                val p = f.pts[i]
                val x = p.x * ca + p.y * sa
                val y = -p.x * sa + p.y * ca
                px[i] = n / 2.0 + x * scale
                py[i] = n * originY - (y * se + p.z * ce) * scale
                pz[i] = y * ce - p.z * se
                wz[i] = p.z
                if (vn != null) {
                    val ox = vn[i * 3]; val oy = vn[i * 3 + 1]
                    nxs[i] = ox * ca + oy * sa; nys[i] = -ox * sa + oy * ca; nzs[i] = vn[i * 3 + 2]
                } else {
                    nxs[i] = fnx; nys[i] = fny; nzs[i] = fnz
                }
            }
            for (t in 1 until m - 1) tri(sh, f, t, px, py, pz, wz, nxs, nys, nzs, n, color, depth)
        }
        if (rim != null) {
            val src = color.copyOf()
            val offs = intArrayOf(1, -1, n, -n, 2, -2, 2 * n, -2 * n)
            for (y in 2 until n - 2) for (x in 2 until n - 2) {
                val i = y * n + x
                if (src[i] != 0) continue
                for (d in offs) if (src[i + d] != 0) { color[i] = rim; break }
            }
        }
        return color
    }

    private fun norm(x: Double, y: Double, z: Double): DoubleArray {
        val l = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / l, y / l, z / l)
    }

    /** Trójkąt (0, [t], t+1) wachlarza ściany – głębia, normalna i wysokość interpolowane na piksel. */
    private fun tri(
        sh: Shader, f: Face, t: Int,
        px: DoubleArray, py: DoubleArray, pz: DoubleArray, wz: DoubleArray, nx: DoubleArray, ny: DoubleArray, nz: DoubleArray,
        n: Int, color: IntArray, depth: FloatArray,
    ) {
        val i0 = 0; val i1 = t; val i2 = t + 1
        val x0 = px[i0]; val y0 = py[i0]; val x1 = px[i1]; val y1 = py[i1]; val x2 = px[i2]; val y2 = py[i2]
        val minX = max(0, min(x0, min(x1, x2)).toInt())
        val maxX = min(n - 1, max(x0, max(x1, x2)).toInt() + 1)
        val minY = max(0, min(y0, min(y1, y2)).toInt())
        val maxY = min(n - 1, max(y0, max(y1, y2)).toInt() + 1)
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (abs(area) < 1e-9) return
        val flat = f.vn == null
        val flatCol = if (flat) shade(sh, f.mat, f.color, nx[i0], ny[i0], nz[i0], (wz[i0] + wz[i1] + wz[i2]) / 3) else 0
        for (yy in minY..maxY) {
            val sy = yy + 0.5
            for (xx in minX..maxX) {
                val sx = xx + 0.5
                val w0 = ((x1 - sx) * (y2 - sy) - (x2 - sx) * (y1 - sy)) / area
                val w1 = ((x2 - sx) * (y0 - sy) - (x0 - sx) * (y2 - sy)) / area
                val w2 = 1.0 - w0 - w1
                if (w0 < 0 || w1 < 0 || w2 < 0) continue
                val z = (w0 * pz[i0] + w1 * pz[i1] + w2 * pz[i2]).toFloat()
                val i = yy * n + xx
                if (z < depth[i]) {
                    depth[i] = z
                    color[i] = if (flat) flatCol else shade(
                        sh, f.mat, f.color,
                        w0 * nx[i0] + w1 * nx[i1] + w2 * nx[i2],
                        w0 * ny[i0] + w1 * ny[i1] + w2 * ny[i2],
                        w0 * nz[i0] + w1 * nz[i1] + w2 * nz[i2],
                        w0 * wz[i0] + w1 * wz[i1] + w2 * wz[i2],
                    )
                }
            }
        }
    }
}
