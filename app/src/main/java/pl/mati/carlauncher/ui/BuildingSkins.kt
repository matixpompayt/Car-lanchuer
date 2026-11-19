package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Realistyczne budynki 3D: ściany z oknami (wzór na ścianach), sklepy na parterze i osobne dachy.
 * Dane OSM w kafelkach mają tylko wysokość, więc rodzaj jest dobierany z wysokości i „losu” budynku
 * (stałego – ten sam budynek zawsze wygląda tak samo):
 *  – do 7 m: domy (8 rodzajów: tynk, cegła, siding, nowoczesny, okiennice, łukowe okna…),
 *  – 7–12 m: hale i fabryki (3), kamienice (2), biurowce, małe bloki, ceglane,
 *  – 12–45 m: kamienice, bloki z wielkiej płyty (6: białe, szare, kolorowe pasy, loggie…), biurowce,
 *  – ponad 45 m: wieżowce (5: niebieskie, zielone, ciemne, złote szkło, betonowa siatka).
 * Dachy w kilku kolorach (niezależnie od ścian) i różnej grubości. Wieczorem część okien świeci.
 */
object BuildingSkins {

    private val H: Expression = Expression.toNumber(Expression.coalesce(Expression.get("render_height"), Expression.literal(6)))
    private val MINH: Expression = Expression.toNumber(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0)))
    /** Stały „los” budynku 0…9972: z identyfikatora i wysokości. */
    private val HASH: Expression = Expression.mod(
        Expression.round(
            Expression.sum(
                Expression.product(Expression.toNumber(Expression.coalesce(Expression.id(), Expression.literal(0))), Expression.literal(7)),
                Expression.product(H, Expression.literal(131)),
            ),
        ),
        Expression.literal(9973),
    )
    private fun hm(n: Int): Expression = Expression.mod(HASH, Expression.literal(n))
    /** Druga, niezależna „kostka” (np. kolor dachu inny niż ściany). */
    private fun hm2(n: Int): Expression = Expression.mod(Expression.floor(Expression.division(HASH, Expression.literal(16))), Expression.literal(n))

    private val HAS_SHOP: Expression = Expression.all(Expression.gt(H, Expression.literal(12)), Expression.lte(MINH, Expression.literal(0.5)))
    private const val SHOP_H = 3.4

    private fun grow(v: Expression): Expression =
        Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0), Expression.stop(15.5, v))

    private fun pick(sel: Expression, names: List<String>): Expression {
        val stops = names.drop(1).mapIndexed { i, n -> Expression.stop(i + 1, n) }.toTypedArray()
        return Expression.match(sel, Expression.literal(names[0]), *stops)
    }

    fun layers(style: Style, prefix: String, src: String, miami: Boolean, light: Boolean): List<FillExtrusionLayer> {
        val key = (if (miami) "m" else "s") + (if (light) "l" else "d")
        ensureImages(style, key, miami, light)
        fun n(k: String) = "bld_${key}_$k"
        val houses = (0 until 8).map { n("house$it") }
        val inds = (0 until 3).map { n("ind$it") }
        val tens = (0 until 2).map { n("ten$it") }
        val lows = listOf(n("office0"), n("low0"), n("brick0"), n("low1"))
        val blocks = (0 until 6).map { n("block$it") }
        val towers = (0 until 5).map { n("tower$it") }
        val pattern = Expression.switchCase(
            Expression.lte(H, Expression.literal(7)), pick(hm(8), houses),
            Expression.lte(H, Expression.literal(12)), Expression.match(
                hm(8), pick(hm2(4), lows),
                Expression.stop(0, pick(hm2(3), inds)), Expression.stop(1, pick(hm2(3), inds)),
                Expression.stop(2, pick(hm2(2), tens)), Expression.stop(3, pick(hm2(2), tens)),
            ),
            Expression.lte(H, Expression.literal(24)), Expression.match(
                hm(6), pick(hm2(6), blocks),
                Expression.stop(0, pick(hm2(2), tens)), Expression.stop(1, n("office1")),
            ),
            Expression.lte(H, Expression.literal(45)), Expression.match(
                hm(5), pick(hm2(6), blocks),
                Expression.stop(0, n("office1")), Expression.stop(1, n("tower4")),
            ),
            pick(hm(5), towers),
        )
        val wallBase = Expression.switchCase(HAS_SHOP, Expression.literal(SHOP_H), MINH)
        val walls = FillExtrusionLayer("$prefix-walls", src).withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionPattern(pattern),
            PropertyFactory.fillExtrusionHeight(grow(H)),
            PropertyFactory.fillExtrusionBase(grow(wallBase)),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(true),
        )
        walls.minZoom = 14f
        val shops = FillExtrusionLayer("$prefix-shops", src).withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionPattern(pick(hm2(3), (0 until 3).map { n("shop$it") })),
            PropertyFactory.fillExtrusionHeight(grow(Expression.literal(SHOP_H))),
            PropertyFactory.fillExtrusionBase(Expression.literal(0)),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(false),
        )
        shops.minZoom = 14f
        shops.setFilter(HAS_SHOP)
        // dachy: domy – dachówka/blacha w 6 kolorach i różnej grubości; reszta – płaskie (papa, żwir, blacha)
        val rc = roofColors(miami, light)
        fun col(i: Int) = Expression.color(rc[i])
        val roofColor = Expression.switchCase(
            Expression.lte(H, Expression.literal(7)),
            Expression.match(hm2(6), col(0), Expression.stop(1, col(1)), Expression.stop(2, col(2)), Expression.stop(3, col(3)), Expression.stop(4, col(4)), Expression.stop(5, col(5))),
            Expression.gt(H, Expression.literal(45)), Expression.match(hm2(2), col(9), Expression.stop(1, col(10))),
            Expression.match(hm2(3), col(6), Expression.stop(1, col(7)), Expression.stop(2, col(8))),
        )
        val capH = Expression.switchCase(
            Expression.lte(H, Expression.literal(7)),
            Expression.match(hm2(3), Expression.literal(1.4), Expression.stop(1, 2.0), Expression.stop(2, 2.6)),
            Expression.gt(H, Expression.literal(45)), Expression.literal(1.2),
            Expression.literal(0.6),
        )
        val roofs = FillExtrusionLayer("$prefix-roofs", src).withSourceLayer("building").withProperties(
            PropertyFactory.fillExtrusionColor(roofColor),
            PropertyFactory.fillExtrusionHeight(grow(Expression.sum(H, capH))),
            PropertyFactory.fillExtrusionBase(grow(H)),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(false),
        )
        roofs.minZoom = 14f
        return listOf(walls, shops, roofs)
    }

    /** Dachy: 6 dla domów, 3 płaskie, 2 wieżowce. */
    private fun roofColors(miami: Boolean, light: Boolean): IntArray = when {
        miami && light -> longArrayOf(
            0xFFD9785F, 0xFFE58A7A, 0xFF3E9C9C, 0xFFC96A8A, 0xFFE0A050, 0xFF8A6AB0,
            0xFFF3E6EE, 0xFFE3D6E8, 0xFFC8B8D8, 0xFF8FA6C8, 0xFFB7C9E0,
        )
        miami -> longArrayOf(
            0xFF8A3A4A, 0xFF9C4A5E, 0xFF2E5E6A, 0xFF7A3A6A, 0xFF8A5A2E, 0xFF4E3A7A,
            0xFF4A3466, 0xFF3A2C52, 0xFF2E2448, 0xFF2C2A5A, 0xFF3A3070,
        )
        light -> longArrayOf(
            0xFFA9503A, 0xFF5A5F68, 0xFF7A4E3A, 0xFF9A3C32, 0xFF4E7A62, 0xFF3A3E44,
            0xFFC9CDD2, 0xFFB0B5BB, 0xFF9AA4AE, 0xFF5E6B78, 0xFF7A8794,
        )
        else -> longArrayOf(
            0xFF4E2A24, 0xFF2A2E35, 0xFF3A2A24, 0xFF46241F, 0xFF243A30, 0xFF1C1F24,
            0xFF33383F, 0xFF2B3138, 0xFF262B31, 0xFF1E252E, 0xFF252D38,
        )
    }.map { it.toInt() }.toIntArray()

    // ======================= wzory ścian =======================

    /** 16×16 punktów mapy (≈ 2 piętra × 2 okna przy przybliżeniu nawigacji), rysowane 4× gęściej. */
    private const val PX = 64
    private const val RATIO = 4

    private enum class Mat { PLASTER, BRICK, SIDING, CLADDING, PANEL, STONE }
    private enum class Win { SQUARE, CROSS, ARCH, TALL, WIDE, SMALL }

    private class Pal(
        val house: IntArray, val block: IntArray, val accent: IntArray, val ind: IntArray, val glass: IntArray,
        val brick: Int, val tenement: IntArray, val concrete: Int,
        val win: Int, val winLit: IntArray, val litShare: Int, val frame: Int, val night: Boolean,
    ) {
        companion object {
            fun of(miami: Boolean, light: Boolean): Pal = when {
                miami && light -> Pal(
                    c(0xFFFFE3D6, 0xFFFFD1DC, 0xFFFFF1C9, 0xFFD6F1EE, 0xFFE8DDF8, 0xFFFFE9D2, 0xFFDDF4E2, 0xFFFFDCC2),
                    c(0xFFFBE5EF, 0xFFE8DDF8, 0xFFFFE9D2, 0xFFDDF0F4, 0xFFFFF4E0, 0xFFF4E0EA),
                    c(0xFFFF5C9E, 0xFF22C3D6, 0xFFFF9F45, 0xFF9B6BE0), c(0xFFC9B8D6, 0xFFE2C7C7, 0xFFD4E4EC), c(0xFF5FC7D9, 0xFF7AE0C0, 0xFF6A88D8, 0xFFE8B06A),
                    0xFFE09A86.toInt(), c(0xFFFFD9A8, 0xFFFFC2D0), 0xFFE6DCE6.toInt(),
                    0xFF7FC9DE.toInt(), c(0xFF7FC9DE), 0, 0xFFFFFFFF.toInt(), false,
                )
                miami -> Pal(
                    c(0xFF8A5070, 0xFF9A5A7A, 0xFF7A5590, 0xFF6A5A88, 0xFF5E6A8A, 0xFF8A6070, 0xFF5A7A80, 0xFF9A6A60),
                    c(0xFF6E4A7E, 0xFF7E4E72, 0xFF5E4A82, 0xFF4E5A80, 0xFF7A5A6E, 0xFF68507A),
                    c(0xFFFF4FB8, 0xFF22D3E6, 0xFFFFA545, 0xFFB57BFF), c(0xFF4A3060, 0xFF3E2D55, 0xFF2E3A5A), c(0xFF3A2A6E, 0xFF1E4A5E, 0xFF2E2F70, 0xFF5A3A50),
                    0xFF7A3E4E.toInt(), c(0xFF8A6A5A, 0xFF8A5A7A), 0xFF5A4E6A.toInt(),
                    0xFF241634.toInt(), c(0xFFFFB86B, 0xFFFF7AC8, 0xFF7FE8FF, 0xFFFFE08A), 62, 0xFF3A2448.toInt(), true,
                )
                light -> Pal(
                    c(0xFFE8E2D6, 0xFFD9C9B4, 0xFFF0EDE6, 0xFFCFD6DB, 0xFFE6D3B8, 0xFFD8DED0, 0xFFF2E6D0, 0xFFC9C2B8),
                    c(0xFFE9EAEC, 0xFFDCDFE3, 0xFFEDE6DA, 0xFFE2E8EC, 0xFFF0ECE4, 0xFFD6DCE0),
                    c(0xFF5BA35B, 0xFFE38B3A, 0xFF4A86C5, 0xFFC94A4A), c(0xFF9AA3AD, 0xFFB8BEC4, 0xFFC9CED2), c(0xFF5D7F9C, 0xFF5E8C7A, 0xFF3E4E60, 0xFF9C8A5E),
                    0xFFA4553E.toInt(), c(0xFFE0C89A, 0xFFD8B0A0), 0xFFB9BCC0.toInt(),
                    0xFF6F8FA8.toInt(), c(0xFF6F8FA8), 0, 0xFFFFFFFF.toInt(), false,
                )
                else -> Pal(
                    c(0xFF3E4048, 0xFF4A443C, 0xFF383C44, 0xFF40464E, 0xFF463E36, 0xFF3A4038, 0xFF4A4438, 0xFF3C3A38),
                    c(0xFF3D4148, 0xFF383C42, 0xFF433E38, 0xFF3A4046, 0xFF44403A, 0xFF363A40),
                    c(0xFF4E8A4E, 0xFFB86E2E, 0xFF3E6EA0, 0xFFA03C3C), c(0xFF2E343B, 0xFF3A4047, 0xFF34383C), c(0xFF1E2C3A, 0xFF1E3430, 0xFF15191F, 0xFF3A3020),
                    0xFF4E2E26.toInt(), c(0xFF4E4436, 0xFF4A3A36), 0xFF33373C.toInt(),
                    0xFF181D25.toInt(), c(0xFFFFC66B, 0xFFFFE2A0, 0xFFFFD48A), 55, 0xFF2A2E35.toInt(), true,
                )
            }

            private fun c(vararg v: Long) = v.map { it.toInt() }.toIntArray()
        }
    }

    private fun ensureImages(style: Style, key: String, miami: Boolean, light: Boolean) {
        if (style.getImage("bld_${key}_shop2") != null) return
        val p = Pal.of(miami, light)
        val img = HashMap<String, Bitmap>()
        // domy: materiał × okna × dodatki
        img["house0"] = facade(p, p.house[0], Mat.PLASTER, Win.CROSS, 1)
        img["house1"] = facade(p, p.house[1], Mat.PLASTER, Win.SQUARE, 2, shutters = 0xFF5E7F5A.toInt())
        img["house2"] = facade(p, p.brick, Mat.BRICK, Win.CROSS, 3)
        img["house3"] = facade(p, p.house[2], Mat.SIDING, Win.SQUARE, 4, shutters = 0xFF4A6A8A.toInt())
        img["house4"] = facade(p, p.house[3], Mat.CLADDING, Win.WIDE, 5)
        img["house5"] = facade(p, p.house[4], Mat.STONE, Win.ARCH, 6)
        img["house6"] = facade(p, p.house[5], Mat.PLASTER, Win.ARCH, 7, shutters = 0xFF7A4E3A.toInt())
        img["house7"] = facade(p, p.house[6], Mat.PLASTER, Win.SMALL, 8, band = p.house[7])
        // hale i fabryki
        img["ind0"] = industrial(p, p.ind[0], 0)
        img["ind1"] = industrial(p, p.ind[1], 1)
        img["ind2"] = facade(p, p.brick, Mat.BRICK, Win.ARCH, 21)
        // kamienice: wysokie okna z gzymsami
        img["ten0"] = facade(p, p.tenement[0], Mat.PLASTER, Win.TALL, 31, cornice = true)
        img["ten1"] = facade(p, p.tenement[1], Mat.STONE, Win.TALL, 32, cornice = true, balcony = 0xFF2A2A2A.toInt())
        // niższe: biurowiec, mały blok, ceglany
        img["office0"] = office(p, p.concrete, 41, ribbon = true)
        img["office1"] = office(p, p.concrete, 42, ribbon = false)
        img["low0"] = facade(p, p.block[0], Mat.PANEL, Win.WIDE, 43)
        img["low1"] = facade(p, p.block[2], Mat.PLASTER, Win.SQUARE, 44, band = p.accent[1])
        img["brick0"] = facade(p, p.brick, Mat.BRICK, Win.WIDE, 45)
        // bloki z wielkiej płyty
        img["block0"] = facade(p, p.block[0], Mat.PANEL, Win.WIDE, 51, balcony = p.accent[0])
        img["block1"] = facade(p, p.block[1], Mat.PANEL, Win.WIDE, 52, balcony = p.accent[1])
        img["block2"] = facade(p, p.block[2], Mat.PANEL, Win.WIDE, 53, stripe = p.accent[2])
        img["block3"] = facade(p, p.block[3], Mat.PANEL, Win.SQUARE, 54, stripe = p.accent[3], balcony = p.accent[3])
        img["block4"] = facade(p, p.block[4], Mat.PANEL, Win.WIDE, 55, band = p.accent[0])
        img["block5"] = loggia(p, p.block[5], 56)
        // wieżowce
        img["tower0"] = tower(p, p.glass[0], 61, 4)
        img["tower1"] = tower(p, p.glass[1], 62, 4)
        img["tower2"] = tower(p, p.glass[2], 63, 8)
        img["tower3"] = tower(p, p.glass[3], 64, 4)
        img["tower4"] = office(p, p.concrete, 65, ribbon = false, grid = true)
        // parter
        img["shop0"] = shop(p, 0)
        img["shop1"] = shop(p, 1)
        img["shop2"] = shop(p, 2)
        img.forEach { (k, b) ->
            b.density = 160 * RATIO
            try { style.addImage("bld_${key}_$k", b) } catch (_: Exception) {}
        }
    }

    // ---------- pomocnicze ----------

    private fun shade(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255),
    )

    private fun paint(c: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c }

    private fun rnd(seed: Int): Int = ((seed * 1103515245 + 12345) ushr 8) and 0x7FFFFF

    private fun newBmp(): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }

    /** Szyba: wieczorem część świeci, w dzień odbija niebo (gradient). */
    private fun glass(p: Pal, seed: Int, x: Float, y: Float, h: Float): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (p.night) {
            val r = rnd(seed) % 100
            paint.color = if (r < p.litShare) p.winLit[rnd(seed + 7) % p.winLit.size] else p.win
        } else {
            paint.shader = LinearGradient(x, y, x, y + h, shade(p.win, 1.3f), shade(p.win, 0.78f), Shader.TileMode.CLAMP)
        }
        return paint
    }

    /** Materiał ściany. */
    private fun wall(c: Canvas, base: Int, mat: Mat, seed: Int) {
        c.drawColor(base)
        when (mat) {
            Mat.PLASTER -> {
                // lekka faktura tynku
                for (i in 0 until 90) {
                    val r = rnd(seed * 97 + i)
                    c.drawRect((r % 64).toFloat(), ((r / 64) % 64).toFloat(), (r % 64) + 2f, ((r / 64) % 64) + 2f, paint(shade(base, if (i % 2 == 0) 0.96f else 1.04f)))
                }
            }
            Mat.BRICK -> {
                val mortar = paint(shade(base, 1.45f))
                val dark = paint(shade(base, 0.88f))
                var row = 0
                var y = 0f
                while (y < PX) {
                    c.drawRect(0f, y, PX.toFloat(), y + 0.8f, mortar)
                    var x = if (row % 2 == 0) 0f else 4f
                    while (x < PX) {
                        c.drawRect(x, y, x + 0.8f, y + 4f, mortar)
                        if (rnd(seed + row * 31 + x.toInt()) % 4 == 0) c.drawRect(x + 1f, y + 1f, x + 7.5f, y + 3.6f, dark)
                        x += 8f
                    }
                    y += 4f
                    row++
                }
            }
            Mat.SIDING -> {
                val line = paint(shade(base, 0.85f))
                val hi = paint(shade(base, 1.08f))
                var y = 0f
                while (y < PX) {
                    c.drawRect(0f, y, PX.toFloat(), y + 0.9f, line)
                    c.drawRect(0f, y + 0.9f, PX.toFloat(), y + 1.6f, hi)
                    y += 4f
                }
            }
            Mat.CLADDING -> {
                var x = 0f
                var i = 0
                while (x < PX) {
                    c.drawRect(x, 0f, x + 8f, PX.toFloat(), paint(shade(base, if (i % 2 == 0) 0.92f else 1.05f)))
                    c.drawRect(x, 0f, x + 0.6f, PX.toFloat(), paint(shade(base, 0.7f)))
                    x += 8f
                    i++
                }
            }
            Mat.PANEL -> {
                val joint = paint(shade(base, 0.8f))
                c.drawRect(0f, 31f, PX.toFloat(), 32f, joint)
                c.drawRect(0f, 63f, PX.toFloat(), 64f, joint)
                c.drawRect(31f, 0f, 32f, PX.toFloat(), joint)
                c.drawRect(63f, 0f, 64f, PX.toFloat(), joint)
            }
            Mat.STONE -> {
                val joint = paint(shade(base, 0.82f))
                var y = 0f
                var row = 0
                while (y < PX) {
                    c.drawRect(0f, y, PX.toFloat(), y + 0.7f, joint)
                    var x = if (row % 2 == 0) 0f else 6f
                    while (x < PX) {
                        c.drawRect(x, y, x + 0.7f, y + 6f, joint)
                        x += 12f
                    }
                    y += 6f
                    row++
                }
            }
        }
    }

    /** Okno w polu 32×32 (jedno piętro × jedna oś). */
    private fun window(c: Canvas, p: Pal, wallCol: Int, ox: Float, oy: Float, win: Win, seed: Int, shutters: Int?) {
        val frame = paint(if (p.night) shade(wallCol, 0.65f) else p.frame)
        val sill = paint(shade(wallCol, 0.72f))
        when (win) {
            Win.SQUARE, Win.CROSS, Win.SMALL -> {
                val small = win == Win.SMALL
                val x = if (small) 11f else 9f
                val y = if (small) 10f else 8f
                val w = if (small) 10f else 14f
                val h = if (small) 10f else 15f
                shutters?.let { s ->
                    c.drawRect(ox + x - 5f, oy + y, ox + x - 1f, oy + y + h, paint(s))
                    c.drawRect(ox + x + w + 1f, oy + y, ox + x + w + 5f, oy + y + h, paint(s))
                }
                c.drawRect(ox + x - 1f, oy + y - 1f, ox + x + w + 1f, oy + y + h + 1f, frame)
                c.drawRect(ox + x + 1f, oy + y + 1f, ox + x + w - 1f, oy + y + h - 1f, glass(p, seed, ox + x, oy + y, h))
                c.drawRect(ox + x + w / 2 - 0.5f, oy + y + 1f, ox + x + w / 2 + 0.5f, oy + y + h - 1f, frame)
                if (win == Win.CROSS) c.drawRect(ox + x + 1f, oy + y + h * 0.4f, ox + x + w - 1f, oy + y + h * 0.4f + 1f, frame)
                c.drawRect(ox + x - 2f, oy + y + h + 1f, ox + x + w + 2f, oy + y + h + 3f, sill)
            }
            Win.ARCH -> {
                val x = 9f; val y = 7f; val w = 14f; val h = 18f
                val outer = Path().apply {
                    addRect(ox + x - 1f, oy + y + w / 2, ox + x + w + 1f, oy + y + h + 1f, Path.Direction.CW)
                    addOval(RectF(ox + x - 1f, oy + y - 1f, ox + x + w + 1f, oy + y + w + 1f), Path.Direction.CW)
                }
                c.drawPath(outer, frame)
                val inner = Path().apply {
                    addRect(ox + x + 1f, oy + y + w / 2, ox + x + w - 1f, oy + y + h - 1f, Path.Direction.CW)
                    addOval(RectF(ox + x + 1f, oy + y + 1f, ox + x + w - 1f, oy + y + w - 1f), Path.Direction.CW)
                }
                c.drawPath(inner, glass(p, seed, ox + x, oy + y, h))
                c.drawRect(ox + x + w / 2 - 0.5f, oy + y + 2f, ox + x + w / 2 + 0.5f, oy + y + h - 1f, frame)
                shutters?.let { s ->
                    c.drawRect(ox + x - 5f, oy + y + 4f, ox + x - 1f, oy + y + h, paint(s))
                    c.drawRect(ox + x + w + 1f, oy + y + 4f, ox + x + w + 5f, oy + y + h, paint(s))
                }
                c.drawRect(ox + x - 2f, oy + y + h + 1f, ox + x + w + 2f, oy + y + h + 3f, sill)
            }
            Win.TALL -> {
                val x = 10f; val y = 5f; val w = 12f; val h = 21f
                // gzyms nad oknem i opaska
                c.drawRect(ox + x - 3f, oy + y - 4f, ox + x + w + 3f, oy + y - 1.5f, paint(shade(wallCol, 1.18f)))
                c.drawRect(ox + x - 2f, oy + y - 1.5f, ox + x + w + 2f, oy + y + h + 1f, paint(shade(wallCol, 1.1f)))
                c.drawRect(ox + x, oy + y, ox + x + w, oy + y + h, frame)
                c.drawRect(ox + x + 1f, oy + y + 1f, ox + x + w - 1f, oy + y + h - 1f, glass(p, seed, ox + x, oy + y, h))
                c.drawRect(ox + x + w / 2 - 0.5f, oy + y + 1f, ox + x + w / 2 + 0.5f, oy + y + h - 1f, frame)
                c.drawRect(ox + x + 1f, oy + y + 6f, ox + x + w - 1f, oy + y + 7f, frame)
                c.drawRect(ox + x - 3f, oy + y + h + 1f, ox + x + w + 3f, oy + y + h + 3f, sill)
            }
            Win.WIDE -> {
                val x = 4f; val y = 8f; val w = 24f; val h = 13f
                c.drawRect(ox + x - 1f, oy + y - 1f, ox + x + w + 1f, oy + y + h + 1f, frame)
                c.drawRect(ox + x + 1f, oy + y + 1f, ox + x + w - 1f, oy + y + h - 1f, glass(p, seed, ox + x, oy + y, h))
                c.drawRect(ox + x + w / 3, oy + y + 1f, ox + x + w / 3 + 1f, oy + y + h - 1f, frame)
                c.drawRect(ox + x + 2 * w / 3, oy + y + 1f, ox + x + 2 * w / 3 + 1f, oy + y + h - 1f, frame)
            }
        }
    }

    /** Elewacja: 2 piętra × 2 osie okien + dodatki (okiennice, balkony, pas, pionowy pas koloru, gzymsy). */
    private fun facade(
        p: Pal, base: Int, mat: Mat, win: Win, seed: Int,
        shutters: Int? = null, balcony: Int? = null, band: Int? = null, stripe: Int? = null, cornice: Boolean = false,
    ): Bitmap {
        val (b, c) = newBmp()
        wall(c, base, mat, seed)
        // pionowy pas koloru (kolorowe bloki) – jedna oś okien
        stripe?.let { c.drawRect(32f, 0f, 64f, PX.toFloat(), paint(it)) }
        for (row in 0 until 2) {
            band?.let { c.drawRect(0f, row * 32f + 27f, PX.toFloat(), row * 32f + 31f, paint(it)) }
            if (cornice) {
                c.drawRect(0f, row * 32f + 30f, PX.toFloat(), row * 32f + 32f, paint(shade(base, 1.22f)))
                c.drawRect(0f, row * 32f + 32f, PX.toFloat(), row * 32f + 32.6f, paint(shade(base, 0.7f)))
            }
            for (col in 0 until 2) {
                val ox = col * 32f
                val oy = row * 32f
                val wallHere = if (stripe != null && col == 1) stripe else base
                window(c, p, wallHere, ox, oy, win, seed * 13 + row * 5 + col * 3, shutters)
                if (balcony != null && (col + row + seed) % 2 == 0) {
                    // balkon: płyta + barierka
                    c.drawRect(ox + 1f, oy + 25f, ox + 31f, oy + 27f, paint(shade(base, 0.6f)))
                    c.drawRect(ox + 1f, oy + 19f, ox + 31f, oy + 25f, paint(balcony))
                    c.drawRect(ox + 1f, oy + 19f, ox + 31f, oy + 20f, paint(shade(balcony, 1.25f)))
                }
            }
        }
        return b
    }

    /** Blok z loggiami: głębokie, ciemne wnęki z oknami i murkiem. */
    private fun loggia(p: Pal, base: Int, seed: Int): Bitmap {
        val (b, c) = newBmp()
        wall(c, base, Mat.PANEL, seed)
        for (row in 0 until 2) for (col in 0 until 2) {
            val ox = col * 32f; val oy = row * 32f
            c.drawRect(ox + 3f, oy + 4f, ox + 29f, oy + 26f, paint(shade(base, 0.55f)))
            c.drawRect(ox + 6f, oy + 6f, ox + 26f, oy + 17f, glass(p, seed * 7 + row * 3 + col, ox + 6f, oy + 6f, 11f))
            c.drawRect(ox + 3f, oy + 18f, ox + 29f, oy + 26f, paint(shade(base, 0.92f)))
        }
        return b
    }

    /** Hala / fabryka: blacha trapezowa i pas okien pod dachem. */
    private fun industrial(p: Pal, base: Int, v: Int): Bitmap {
        val (b, c) = newBmp()
        c.drawColor(base)
        val dark = paint(shade(base, 0.82f))
        val hi = paint(shade(base, 1.12f))
        var x = 0f
        while (x < PX) {
            c.drawRect(x, 0f, x + 2f, PX.toFloat(), dark)
            c.drawRect(x + 2f, 0f, x + 3f, PX.toFloat(), hi)
            x += 6f
        }
        // pas firmowy w drugim wariancie
        if (v == 1) c.drawRect(0f, 26f, PX.toFloat(), 31f, paint(p.accent[2]))
        for (row in 0 until 2) {
            val y = row * 32f + 4f
            c.drawRect(0f, y - 1f, PX.toFloat(), y + 8f, paint(shade(base, 0.6f)))
            var wx = 1f
            var i = 0
            while (wx < PX) {
                c.drawRect(wx, y, wx + 6f, y + 7f, glass(p, 77 + v * 31 + row * 13 + i, wx, y, 7f))
                wx += 8f
                i++
            }
        }
        return b
    }

    /** Biurowiec: pasy okien (ribbon) albo betonowa siatka z oknami. */
    private fun office(p: Pal, base: Int, seed: Int, ribbon: Boolean, grid: Boolean = false): Bitmap {
        val (b, c) = newBmp()
        c.drawColor(base)
        if (ribbon) {
            for (row in 0 until 2) {
                val y = row * 32f + 9f
                c.drawRect(0f, y - 1f, PX.toFloat(), y + 15f, paint(shade(base, 0.5f)))
                var x = 0f
                var i = 0
                while (x < PX) {
                    c.drawRect(x + 0.6f, y, x + 7.4f, y + 14f, glass(p, seed * 11 + row * 17 + i, x, y, 14f))
                    x += 8f
                    i++
                }
            }
        } else {
            val n = if (grid) 4 else 3
            val cell = PX / n.toFloat()
            for (row in 0 until 4) for (col in 0 until n) {
                val x = col * cell; val y = row * 16f
                c.drawRect(x + 2f, y + 3f, x + cell - 2f, y + 14f, paint(shade(base, 0.62f)))
                c.drawRect(x + 3f, y + 4f, x + cell - 3f, y + 13f, glass(p, seed * 5 + row * 7 + col, x, y + 4f, 9f))
            }
        }
        return b
    }

    /** Wieżowiec: szklana ściana, słupki ([cols] na pole), odbicie nieba, wieczorem świecące piętra. */
    private fun tower(p: Pal, g: Int, seed: Int, cols: Int): Bitmap {
        val (b, c) = newBmp()
        c.drawRect(0f, 0f, PX.toFloat(), PX.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, PX.toFloat(), PX.toFloat(), shade(g, 1.3f), shade(g, 0.8f), Shader.TileMode.CLAMP)
        })
        val cw = PX / cols.toFloat()
        for (row in 0 until 4) for (col in 0 until cols) {
            val x = col * cw + 1f
            val y = row * 16f + 1.5f
            if (p.night) c.drawRect(x, y, x + cw - 2f, y + 13f, glass(p, seed * 23 + row * 8 + col, x, y, 13f))
            else if (rnd(seed + row * 8 + col) % 5 == 0) c.drawRect(x, y, x + cw - 2f, y + 13f, paint(shade(g, 1.4f)))
        }
        val mull = paint(if (p.night) 0xFF0E1218.toInt() else shade(g, 0.55f))
        for (i in 0..cols) c.drawRect(i * cw - 0.6f, 0f, i * cw + 0.6f, PX.toFloat(), mull)
        // pasy międzykondygnacyjne
        for (i in 0..4) c.drawRect(0f, i * 16f - 1f, PX.toFloat(), i * 16f + 1f, mull)
        return b
    }

    /** Parter: witryny i szyldy (3 rodzaje: szyld, markiza w paski, ciemna witryna). */
    private fun shop(p: Pal, v: Int): Bitmap {
        val (b, c) = newBmp()
        c.drawColor(if (p.night) 0xFF14161C.toInt() else 0xFF3A3F47.toInt())
        c.drawRect(0f, 0f, PX.toFloat(), 33f, paint(if (p.night) 0xFF2A2E35.toInt() else 0xFFB9BEC4.toInt()))
        for (col in 0 until 2) {
            val x = col * 32f
            val sign = p.accent[(col + v) % p.accent.size]
            if (v == 1) {
                // markiza w paski
                for (k in 0 until 7) c.drawRect(x + 2f + k * 4f, 34f, x + 6f + k * 4f, 41f, paint(if (k % 2 == 0) sign else 0xFFF4F4F4.toInt()))
            } else {
                c.drawRect(x + 2f, 34f, x + 30f, 40f, paint(sign))
            }
            val lit = if (p.night) (if (v == 2) 0xFFB8E0FF.toInt() else 0xFFFFE4B0.toInt()) else shade(p.win, if (v == 2) 0.8f else 1.2f)
            c.drawRect(x + 3f, 42f, x + 29f, 62f, paint(lit))
            c.drawRect(x + 15.5f, 42f, x + 16.5f, 62f, paint(0xFF20242A.toInt()))
        }
        return b
    }
}
