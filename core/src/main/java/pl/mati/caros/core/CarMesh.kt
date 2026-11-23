package pl.mati.caros.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modele 3D aut (i ludzika) dla CAR OS i CAR OS Connect – czysty Kotlin, bez Androida:
 * siatka + programowy rasteryzator z z-buforem i materiałami (lakier z odblaskiem, szyby odbijające niebo,
 * czarne plastiki, felgi, opony, lampy). Bitmapę robi [Model3D].
 *
 * Układ: x – w bok (prawo +), y – wzdłuż auta (przód +), z – w górę, metry, środek auta w (0, 0).
 * Nadwozie to „loft”: przekroje poprzeczne co kilkanaście cm, osobno dół (blacha) i kabina (szyby, słupki, dach).
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

    class Face(val pts: List<V>, val color: Int, val mat: Int = PAINT) {
        var nx = 0.0; var ny = 0.0; var nz = 0.0
        var minZ = 0.0
    }

    // ---------- budowanie ----------

    /** Normalna (Newell) skierowana od punktu odniesienia [cx, cy, cz] na zewnątrz. */
    private fun add(out: MutableList<Face>, pts: List<V>, color: Int, mat: Int, cx: Double, cy: Double, cz: Double) {
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
        out += Face(pts, color, mat).also { it.nx = nx; it.ny = ny; it.nz = nz; it.minZ = mz }
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
            val rf = 0.50; val rr = 0.42
            val s = when {
                y > hl - rf -> ((y - (hl - rf)) / rf) * 0.93
                y < -hl + rr -> ((-hl + rr - y) / rr) * 0.93
                else -> 0.0
            }
            return hw0 * (1 - s.pow(2.3)).pow(1 / 2.3)
        }

        // górna krawędź blachy (maska, linia okien, bagażnik)
        fun top(y: Double): Double = when {
            y >= p.ws -> {
                val t = (y - p.ws) / (hl - p.ws)
                val z = lerp(p.belt - 0.03, p.hoodF, smooth(t * 1.05))
                z - 0.09 * smooth((t - 0.82) / 0.18) // zaokrąglony nos
            }
            p.notch && y <= p.rear -> {
                val t = (p.rear - y) / (p.rear + hl)
                lerp(p.belt + 0.02, p.deck, smooth(t * 1.6)) - 0.07 * smooth((t - 0.80) / 0.20)
            }
            y < -hl + 0.25 -> p.belt - 0.05 * smooth((-hl + 0.25 - y) / 0.25)
            else -> p.belt
        }

        fun bottom(y: Double): Double {
            val e = max(smooth((y - (hl - 0.45)) / 0.45), smooth((-hl + 0.40 - y) / 0.40))
            return p.clear + 0.10 * e
        }

        // ---- dół nadwozia ----
        val n = 34
        val ys = DoubleArray(n) { i -> -hl + p.len * (1 - cos(PI * i / (n - 1))) / 2 }
        fun ring(y: Double): List<Pair<Double, Double>> {
            val hw = halfW(y); val zb = bottom(y); val zt = top(y); val hh = zt - zb
            val r = listOf(
                0.82 * hw to zb, hw - 0.035 to zb + 0.05, hw to zb + 0.30 * hh, hw - 0.012 to zb + 0.64 * hh,
                hw - 0.055 to zt - 0.035, hw - 0.14 to zt,
            )
            return r + r.reversed().map { -it.first to it.second }
        }
        val rings = ys.map { ring(it) }
        val segs = rings[0].size
        for (i in 0 until n - 1) {
            val y0 = ys[i]; val y1 = ys[i + 1]; val ym = (y0 + y1) / 2
            val a = rings[i]; val b = rings[i + 1]
            val zc = (bottom(ym) + top(ym)) / 2
            for (j in 0 until segs) {
                val k = (j + 1) % segs
                val pts = listOf(V(a[j].first, y0, a[j].second), V(a[k].first, y0, a[k].second), V(b[k].first, y1, b[k].second), V(b[j].first, y1, b[j].second))
                // j: 0..4 prawa strona od dołu (0 – zaokrąglenie dołu, 2 – środek boku, 3 – pod linią ramion, 4 – narożnik),
                // 5 – góra (maska / dach blachy), 6..10 – lewa strona w odwrotnej kolejności, 11 – spód
                val side = when { j <= 5 -> j; j <= 10 -> 10 - j; else -> -1 }
                val mat = when {
                    j == segs - 1 -> TRIM                                              // spód
                    side == 3 && ym > hl - 0.30 && ym < hl - 0.015 -> LIGHT_F           // reflektory (owinięte na bok)
                    side == 3 && ym < -hl + 0.22 && ym > -hl + 0.01 -> LIGHT_R          // lampy tylne
                    p.clad && side <= 1 -> TRIM                                         // nakładki SUV
                    side == 0 && (ym > hl - 0.30 || ym < -hl + 0.28) -> TRIM            // dolne zderzaki
                    p.bed && j == 5 && ym < p.rear - 0.04 && ym > -hl + 0.06 -> TRIM    // skrzynia (zakryta)
                    else -> PAINT
                }
                add(out, pts, if (mat == PAINT) paint else 0, mat, 0.0, ym, zc)
            }
        }
        // czoło i tył (zaślepki), atrapa, tablice
        val zMid = (p.clear + p.belt) / 2
        add(out, rings[n - 1].map { V(it.first, ys[n - 1], it.second) }, paint, PAINT, 0.0, 0.0, zMid)
        add(out, rings[0].map { V(it.first, ys[0], it.second) }, paint, PAINT, 0.0, 0.0, zMid)
        run {
            val y = ys[n - 1] + 0.004
            val hw = halfW(ys[n - 1]); val zb = bottom(ys[n - 1]); val zt = top(ys[n - 1]); val hh = zt - zb
            add(out, listOf(V(-0.62 * hw, y, zb + 0.36 * hh), V(0.62 * hw, y, zb + 0.36 * hh), V(0.56 * hw, y, zb + 0.70 * hh), V(-0.56 * hw, y, zb + 0.70 * hh)), 0, TRIM, 0.0, 0.0, zMid)
            add(out, listOf(V(-0.26, y + 0.002, zb + 0.12 * hh), V(0.26, y + 0.002, zb + 0.12 * hh), V(0.26, y + 0.002, zb + 0.30 * hh), V(-0.26, y + 0.002, zb + 0.30 * hh)), 0, PLATE, 0.0, 0.0, zMid)
            val yr = ys[0] - 0.004
            val zbr = bottom(ys[0]); val ztr = top(ys[0]); val hr = ztr - zbr
            add(out, listOf(V(-0.26, yr, zbr + 0.40 * hr), V(0.26, yr, zbr + 0.40 * hr), V(0.26, yr, zbr + 0.60 * hr), V(-0.26, yr, zbr + 0.60 * hr)), 0, PLATE, 0.0, 0.0, zMid)
        }

        // ---- kabina: szyby, słupki, dach ----
        val gm = 26
        val g0 = p.rear; val g1 = p.ws
        val gys = DoubleArray(gm) { i -> lerp(g0, g1, i / (gm - 1.0)) }
        fun roofZ(y: Double): Double {
            val zb = top(y) - 0.004
            return when {
                y >= p.roofF -> lerp(p.h, zb, ((y - p.roofF) / (p.ws - p.roofF)).coerceIn(0.0, 1.0).pow(1.12))
                y <= p.roofR -> lerp(p.h, zb, ((p.roofR - y) / (p.roofR - p.rear)).coerceIn(0.0, 1.0).pow(1.08))
                else -> p.h - 0.035 * ((2 * (y - p.roofR) / (p.roofF - p.roofR) - 1).pow(2))
            }
        }
        fun gring(y: Double): List<Pair<Double, Double>> {
            val zb = top(y) - 0.004; val zt = roofZ(y)
            val hb = halfW(y) - 0.075
            val ht = hb - 0.19 * ((zt - zb) / 0.45).coerceIn(0.0, 1.0)
            return listOf(hb to zb, ht + 0.012 to zt - 0.055, ht - 0.07 to zt, -(ht - 0.07) to zt, -(ht + 0.012) to zt - 0.055, -hb to zb)
        }
        val grings = gys.map { gring(it) }
        val bp = (p.roofF + p.roofR) / 2 + 0.10
        for (i in 0 until gm - 1) {
            val y0 = gys[i]; val y1 = gys[i + 1]; val ym = (y0 + y1) / 2
            val a = grings[i]; val b = grings[i + 1]
            val zc = top(ym)
            val region = when { ym >= p.roofF -> 'W'; ym <= p.roofR -> 'B'; else -> 'R' }
            for (j in 0 until 5) {
                val pts = listOf(V(a[j].first, y0, a[j].second), V(a[j + 1].first, y0, a[j + 1].second), V(b[j + 1].first, y1, b[j + 1].second), V(b[j].first, y1, b[j].second))
                val sideSeg = j == 0 || j == 4
                val corner = j == 1 || j == 3
                val mat = when (region) {
                    'W' -> if (corner) PAINT else GLASS                                    // przednia szyba, słupek A
                    'R' -> when {
                        !sideSeg -> PAINT                                                    // dach
                        p.doors4 && abs(ym - bp) < 0.065 -> TRIM                             // słupek B
                        ym < p.roofR + (if (p.quarter) 0.10 else 0.26) -> PAINT              // słupek C
                        else -> GLASS
                    }
                    else -> when {
                        j == 2 -> GLASS                                                      // tylna szyba
                        corner -> PAINT
                        p.quarter && ym > p.rear + 0.06 -> GLASS                              // szyba za słupkiem C
                        else -> PAINT
                    }
                }
                add(out, pts, if (mat == PAINT) paint else 0, mat, 0.0, ym, zc)
            }
        }

        // ---- lusterka ----
        // przy podstawie słupka A, wpuszczone w kabinę (bez „wiszenia” nad maską)
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
        val m = 20
        val ring = (0 until m).map { k -> val a = 2 * PI * k / m; Pair(yc + r * cos(a), zc + r * sin(a)) }
        val cx = (xo + xi) / 2
        for (i in 0 until m) {
            val a = ring[i]; val b = ring[(i + 1) % m]
            add(out, listOf(V(xi, a.first, a.second), V(xi, b.first, b.second), V(xo, b.first, b.second), V(xo, a.first, a.second)), 0, TIRE, cx, yc, zc)
        }
        add(out, ring.map { V(xo, it.first, it.second) }, 0, TIRE, cx, yc, zc)
        add(out, ring.map { V(xi, it.first, it.second) }, 0, TIRE, cx, yc, zc)
        // felga: 10 klinów (5 ramion jasnych, 5 ciemnych), piasta
        val xr = xo + sx * 0.004
        val rr = r * 0.66
        val w = 10
        for (k in 0 until w) {
            val a0 = 2 * PI * k / w; val a1 = 2 * PI * (k + 1) / w
            val pts = listOf(
                V(xr, yc + rr * 0.24 * cos(a0), zc + rr * 0.24 * sin(a0)),
                V(xr, yc + rr * cos(a0), zc + rr * sin(a0)),
                V(xr, yc + rr * cos((a0 + a1) / 2), zc + rr * sin((a0 + a1) / 2)),
                V(xr, yc + rr * cos(a1), zc + rr * sin(a1)),
                V(xr, yc + rr * 0.24 * cos(a1), zc + rr * 0.24 * sin(a1)),
            )
            add(out, pts, 0, if (k % 2 == 0) RIM else TRIM, 0.0, yc, zc)
        }
        add(out, (0 until 10).map { k -> val a = 2 * PI * k / 10; V(xr + sx * 0.003, yc + rr * 0.26 * cos(a), zc + rr * 0.26 * sin(a)) }, 0, RIM, 0.0, yc, zc)
        // nadkole: ciemny łuk nad kołem
        val x = sx * (hw + 0.004)
        val r0 = r + 0.012; val r1 = r + (if (clad) 0.10 else 0.055)
        val steps = 14
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

    // ---------- rysowanie ----------

    private fun ch(c: Int, s: Int) = (c shr s) and 0xFF

    /** Kolor razy [k] plus biały odblask [w] (0..1). */
    private fun lit(c: Int, k: Double, w: Double): Int {
        fun one(v: Int) = (v * k + 255 * w).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (one(ch(c, 16)) shl 16) or (one(ch(c, 8)) shl 8) or one(ch(c, 0))
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun one(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (one(16) shl 16) or (one(8) shl 8) or one(0)
    }

    private fun shadeFace(f: Face, nx: Double, ny: Double, nz: Double, l: DoubleArray, v: DoubleArray, h: DoubleArray): Int {
        val diff = max(0.0, nx * l[0] + ny * l[1] + nz * l[2])
        val nh = max(0.0, nx * h[0] + ny * h[1] + nz * h[2])
        val nv = max(0.0, nx * v[0] + ny * v[1] + nz * v[2])
        val sky = (nz + 1) / 2
        val ao = if (f.minZ < 0.32) 0.86 else 1.0
        return when (f.mat) {
            PAINT -> {
                val k = (0.36 + 0.16 * sky + 0.58 * diff) * ao
                val spec = 0.50 * nh.pow(26) + 0.10 * (1 - nv).pow(3)
                lit(f.color, k, spec)
            }
            GLASS -> {
                val refl = (0.10 + 0.48 * max(0.0, nz) + 0.30 * (1 - nv).pow(2)).coerceIn(0.0, 0.78)
                val c = mix(0xFF131A23.toInt(), 0xFFA9BED3.toInt(), refl)
                lit(c, 1.0, 0.85 * nh.pow(70))
            }
            TRIM -> lit(0xFF15181C.toInt(), (0.62 + 0.55 * diff) * ao, 0.07 * nh.pow(14))
            RIM -> lit(0xFFC4CAD1.toInt(), 0.48 + 0.62 * diff, 0.45 * nh.pow(30))
            TIRE -> lit(0xFF111316.toInt(), 0.78 + 0.40 * diff, 0.0)
            LIGHT_F -> lit(mix(0xFFDDE6EE.toInt(), 0xFFFFFFFF.toInt(), 0.35 * diff), 1.0, 0.55 * nh.pow(40))
            LIGHT_R -> lit(0xFFC41E2A.toInt(), 0.72 + 0.45 * diff, 0.40 * nh.pow(40))
            PLATE -> lit(0xFFE9ECEF.toInt(), 0.62 + 0.42 * diff, 0.0)
            else -> lit(f.color, 0.52 + 0.55 * diff, 0.0)
        }
    }

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
        val v = doubleArrayOf(0.0, -ce, se)
        val h = norm(l[0] + v[0], l[1] + v[1], l[2] + v[2])
        val color = IntArray(n * n)
        val depth = FloatArray(n * n) { Float.MAX_VALUE }
        val px = DoubleArray(32); val py = DoubleArray(32); val pz = DoubleArray(32)
        for (f in faces) {
            val nx = f.nx * ca + f.ny * sa
            val ny = -f.nx * sa + f.ny * ca
            val nz = f.nz
            if (-ny * ce + nz * se <= 0.0) continue
            val col = shadeFace(f, nx, ny, nz, l, v, h)
            val m = f.pts.size
            for (i in 0 until m) {
                val p = f.pts[i]
                val x = p.x * ca + p.y * sa
                val y = -p.x * sa + p.y * ca
                px[i] = n / 2.0 + x * scale
                py[i] = n * originY - (y * se + p.z * ce) * scale
                pz[i] = y * ce - p.z * se
            }
            for (t in 1 until m - 1) tri(px[0], py[0], pz[0], px[t], py[t], pz[t], px[t + 1], py[t + 1], pz[t + 1], col, n, color, depth)
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
}
