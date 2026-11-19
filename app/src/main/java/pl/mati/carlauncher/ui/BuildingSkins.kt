package pl.mati.carlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * Realistyczne budynki 3D: zamiast gładkich brył – ściany z oknami (wzór na ścianach) i osobne dachy.
 * Dane OSM w kafelkach mają tylko wysokość budynku, więc rodzaj jest dobierany z wysokości
 * (i „losowo”, ale stale dla danego budynku):
 *  – do 8 m: domy (tynk w kilku kolorach, okna, dachówka),
 *  – 8–15 m: małe bloki albo hale / fabryki (blacha trapezowa, pas okien),
 *  – 15–40 m: bloki (płyta, okna, balkony z kolorowymi barierkami, sklepy na parterze),
 *  – ponad 40 m: wieżowce (szkło, sklepy na parterze).
 * Wieczorem część okien się świeci. Wzory rysowane w kodzie (bez plików), osobno dla stylu i pory dnia.
 */
object BuildingSkins {

    /** Wysokość, dla której rysujemy (rzeczywista albo 6 m). */
    private val H: Expression = Expression.toNumber(Expression.coalesce(Expression.get("render_height"), Expression.literal(6)))
    private val MINH: Expression = Expression.toNumber(Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0)))
    /** Stały „los” budynku: z identyfikatora i wysokości. */
    private val HASH: Expression = Expression.mod(
        Expression.round(Expression.sum(Expression.toNumber(Expression.coalesce(Expression.id(), Expression.literal(0))), Expression.product(H, Expression.literal(13)))),
        Expression.literal(12),
    )
    /** Parter ze sklepami: bloki i wieżowce stojące na ziemi. */
    private val HAS_SHOP: Expression = Expression.all(Expression.gt(H, Expression.literal(15)), Expression.lte(MINH, Expression.literal(0.5)))
    private const val SHOP_H = 3.4

    private fun grow(v: Expression): Expression =
        Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(14, 0), Expression.stop(15.5, v))

    /** Warstwy budynków: ściany z oknami, parter ze sklepami, dachy. Id zaczynają się od [prefix]. */
    fun layers(style: Style, prefix: String, src: String, miami: Boolean, light: Boolean): List<FillExtrusionLayer> {
        val key = (if (miami) "m" else "s") + (if (light) "l" else "d")
        ensureImages(style, key, miami, light)
        fun n(kind: String) = "bld_${key}_$kind"
        val hm4 = Expression.mod(HASH, Expression.literal(4))
        val hm3 = Expression.mod(HASH, Expression.literal(3))
        val hm2 = Expression.mod(HASH, Expression.literal(2))
        val pattern = Expression.switchCase(
            Expression.lte(H, Expression.literal(8)),
            Expression.match(hm4, Expression.literal(n("house0")), Expression.stop(1, n("house1")), Expression.stop(2, n("house2")), Expression.stop(3, n("house3"))),
            Expression.lte(H, Expression.literal(15)),
            Expression.match(hm3, Expression.literal(n("low0")), Expression.stop(0, n("ind")), Expression.stop(1, n("low1"))),
            Expression.lte(H, Expression.literal(40)),
            Expression.match(hm3, Expression.literal(n("block0")), Expression.stop(1, n("block1")), Expression.stop(2, n("block2"))),
            Expression.match(hm2, Expression.literal(n("tower0")), Expression.stop(1, n("tower1"))),
        )
        // ściany (od parteru albo od 1. piętra, gdy pod spodem są sklepy)
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
            PropertyFactory.fillExtrusionPattern(n("shop")),
            PropertyFactory.fillExtrusionHeight(grow(Expression.literal(SHOP_H))),
            PropertyFactory.fillExtrusionBase(Expression.literal(0)),
            PropertyFactory.fillExtrusionOpacity(1f),
            PropertyFactory.fillExtrusionVerticalGradient(false),
        )
        shops.minZoom = 14f
        shops.setFilter(HAS_SHOP)
        // dach: płyta na wierzchu (dom – grubsza, w kolorze dachówki), żeby na dachu nie było okien
        val roofCols = roofColors(miami, light)
        val roofColor = Expression.switchCase(
            Expression.lte(H, Expression.literal(8)),
            Expression.match(
                hm4, Expression.color(roofCols[0]),
                Expression.stop(1, Expression.color(roofCols[1])), Expression.stop(2, Expression.color(roofCols[2])), Expression.stop(3, Expression.color(roofCols[3])),
            ),
            Expression.all(Expression.lte(H, Expression.literal(15)), Expression.eq(hm3, Expression.literal(0))),
            Expression.color(roofCols[4]),
            Expression.gt(H, Expression.literal(40)),
            Expression.color(roofCols[6]),
            Expression.color(roofCols[5]),
        )
        val capH = Expression.switchCase(Expression.lte(H, Expression.literal(8)), Expression.literal(1.6), Expression.literal(0.6))
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

    private fun roofColors(miami: Boolean, light: Boolean): IntArray = when {
        // domy ×4, hala, blok, wieżowiec
        miami && light -> longArrayOf(0xFFD9785F, 0xFFE58A7A, 0xFF3E9C9C, 0xFFC96A8A, 0xFFC8B8D8, 0xFFF3E6EE, 0xFF8FA6C8)
        miami -> longArrayOf(0xFF8A3A4A, 0xFF9C4A5E, 0xFF2E5E6A, 0xFF7A3A6A, 0xFF3A2C52, 0xFF4A3466, 0xFF2C2A5A)
        light -> longArrayOf(0xFFA9503A, 0xFF5A5F68, 0xFF7A4E3A, 0xFF9A3C32, 0xFF9AA4AE, 0xFFC9CDD2, 0xFF5E6B78)
        else -> longArrayOf(0xFF4E2A24, 0xFF2A2E35, 0xFF3A2A24, 0xFF46241F, 0xFF2B3138, 0xFF33383F, 0xFF1E252E)
    }.map { it.toInt() }.toIntArray()

    // ---------- wzory ścian ----------

    /** Rozmiar wzoru: 16×16 punktów mapy (≈ 2 piętra × 2 okna przy przybliżeniu nawigacji), rysowany 4× gęściej. */
    private const val PX = 64
    private const val RATIO = 4

    private fun ensureImages(style: Style, key: String, miami: Boolean, light: Boolean) {
        if (style.getImage("bld_${key}_shop") != null) return
        val pal = Pal.of(miami, light)
        val imgs = HashMap<String, Bitmap>()
        pal.house.forEachIndexed { i, wall -> imgs["house$i"] = house(wall, pal, i) }
        imgs["low0"] = block(pal.block[0], pal, null, 11)
        imgs["low1"] = block(pal.block[1], pal, null, 12)
        imgs["ind"] = industrial(pal)
        pal.block.forEachIndexed { i, wall -> imgs["block$i"] = block(wall, pal, pal.accent[i % pal.accent.size], 20 + i) }
        imgs["tower0"] = tower(pal.glass[0], pal, 31)
        imgs["tower1"] = tower(pal.glass[1], pal, 32)
        imgs["shop"] = shop(pal)
        imgs.forEach { (k, b) ->
            b.density = 160 * RATIO
            try { style.addImage("bld_${key}_$k", b) } catch (_: Exception) {}
        }
    }

    private class Pal(
        val house: IntArray, val block: IntArray, val accent: IntArray, val ind: IntArray, val glass: IntArray,
        val win: Int, val winLit: IntArray, val litShare: Int, val frame: Int, val night: Boolean,
    ) {
        companion object {
            fun of(miami: Boolean, light: Boolean): Pal = when {
                miami && light -> Pal(
                    c(0xFFFFE3D6, 0xFFFFD1DC, 0xFFFFF1C9, 0xFFD6F1EE), c(0xFFFBE5EF, 0xFFE8DDF8, 0xFFFFE9D2),
                    c(0xFFFF5C9E, 0xFF22C3D6, 0xFFFF9F45), c(0xFFC9B8D6, 0xFFB3A2C4), c(0xFF5FC7D9, 0xFF7AA8E8),
                    0xFF7FC9DE.toInt(), c(0xFF7FC9DE), 0, 0xFFFFFFFF.toInt(), false,
                )
                miami -> Pal(
                    c(0xFF8A5070, 0xFF9A5A7A, 0xFF7A5590, 0xFF6A5A88), c(0xFF6E4A7E, 0xFF7E4E72, 0xFF5E4A82),
                    c(0xFFFF4FB8, 0xFF22D3E6, 0xFFFFA545), c(0xFF4A3060, 0xFF3E2D55), c(0xFF3A2A6E, 0xFF2E2F70),
                    0xFF241634.toInt(), c(0xFFFFB86B, 0xFFFF7AC8, 0xFF7FE8FF, 0xFFFFE08A), 62, 0xFF3A2448.toInt(), true,
                )
                light -> Pal(
                    c(0xFFE8E2D6, 0xFFD9C9B4, 0xFFF0EDE6, 0xFFCFD6DB), c(0xFFE9EAEC, 0xFFDCDFE3, 0xFFEDE6DA),
                    c(0xFF5BA35B, 0xFFE38B3A, 0xFF4A86C5), c(0xFF9AA3AD, 0xFFB8BEC4), c(0xFF5D7F9C, 0xFF4F6E86),
                    0xFF6F8FA8.toInt(), c(0xFF6F8FA8), 0, 0xFFFFFFFF.toInt(), false,
                )
                else -> Pal(
                    c(0xFF3E4048, 0xFF4A443C, 0xFF383C44, 0xFF40464E), c(0xFF3D4148, 0xFF383C42, 0xFF433E38),
                    c(0xFF4E8A4E, 0xFFB86E2E, 0xFF3E6EA0), c(0xFF2E343B, 0xFF3A4047), c(0xFF1E2C3A, 0xFF22303F),
                    0xFF181D25.toInt(), c(0xFFFFC66B, 0xFFFFE2A0, 0xFFFFD48A), 55, 0xFF2A2E35.toInt(), true,
                )
            }

            private fun c(vararg v: Long) = v.map { it.toInt() }.toIntArray()
        }
    }

    private fun bmp(fill: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(fill)
        return b to c
    }

    private fun shade(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255),
    )

    /** Szyba: wieczorem część świeci (stały wzór z ziarna), w dzień odbija niebo. */
    private fun glassPaint(p: Pal, seed: Int, x: Float, y: Float, h: Float): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (p.night) {
            val r = ((seed * 1103515245 + 12345) ushr 8) % 100
            paint.color = if (r < p.litShare) p.winLit[(seed ushr 3) % p.winLit.size] else p.win
        } else {
            paint.shader = LinearGradient(x, y, x, y + h, shade(p.win, 1.25f), shade(p.win, 0.8f), Shader.TileMode.CLAMP)
        }
        return paint
    }

    /** Dom: tynk, 2×2 okna z ramą i parapetem, okiennice w co drugim wariancie. */
    private fun house(wall: Int, p: Pal, v: Int): Bitmap {
        val (b, c) = bmp(wall)
        val frame = Paint().apply { color = if (p.night) shade(wall, 0.7f) else p.frame }
        val sill = Paint().apply { color = shade(wall, 0.75f) }
        val shut = Paint().apply { color = when (v % 3) { 0 -> 0xFF5E7F5A.toInt(); 1 -> 0xFF7A4E3A.toInt(); else -> 0xFF4A6A8A.toInt() } }
        for (row in 0 until 2) for (col in 0 until 2) {
            val x = col * 32f + 9f
            val y = row * 32f + 8f
            if (v % 2 == 1) {
                c.drawRect(x - 5f, y, x - 1f, y + 15f, shut)
                c.drawRect(x + 15f, y, x + 19f, y + 15f, shut)
            }
            c.drawRect(x - 1f, y - 1f, x + 15f, y + 16f, frame)
            c.drawRect(x + 1f, y + 1f, x + 13f, y + 14f, glassPaint(p, v * 31 + row * 7 + col * 3, x, y, 14f))
            c.drawRect(x + 6.5f, y + 1f, x + 7.5f, y + 14f, frame)
            c.drawRect(x - 2f, y + 16f, x + 16f, y + 18f, sill)
        }
        return b
    }

    /** Blok: płyta z fugami, szerokie okna, balkony z kolorową barierką (gdy [accent]). */
    private fun block(wall: Int, p: Pal, accent: Int?, seed: Int): Bitmap {
        val (b, c) = bmp(wall)
        val joint = Paint().apply { color = shade(wall, 0.82f); strokeWidth = 1.2f }
        c.drawLine(0f, 31.5f, PX.toFloat(), 31.5f, joint)
        c.drawLine(0f, 63.5f, PX.toFloat(), 63.5f, joint)
        c.drawLine(31.5f, 0f, 31.5f, PX.toFloat(), joint)
        val frame = Paint().apply { color = if (p.night) shade(wall, 0.6f) else p.frame }
        for (row in 0 until 2) for (col in 0 until 2) {
            val x = col * 32f + 4f
            val y = row * 32f + 7f
            c.drawRect(x - 1f, y - 1f, x + 25f, y + 15f, frame)
            c.drawRect(x + 1f, y + 1f, x + 23f, y + 13f, glassPaint(p, seed * 17 + row * 5 + col * 11, x, y, 12f))
            c.drawRect(x + 11.5f, y + 1f, x + 12.5f, y + 13f, frame)
            if (accent != null && col == (row + seed) % 2) {
                // balkon: płyta + barierka w kolorze
                c.drawRect(x - 2f, y + 18f, x + 27f, y + 20f, Paint().apply { color = shade(wall, 0.7f) })
                c.drawRect(x - 2f, y + 14f, x + 27f, y + 18.5f, Paint().apply { color = accent })
            }
        }
        return b
    }

    /** Hala / fabryka: blacha trapezowa i pas okien pod dachem. */
    private fun industrial(p: Pal): Bitmap {
        val base = p.ind[0]
        val (b, c) = bmp(base)
        val dark = Paint().apply { color = shade(base, 0.82f) }
        val light = Paint().apply { color = shade(base, 1.12f) }
        var x = 0f
        while (x < PX) {
            c.drawRect(x, 0f, x + 2f, PX.toFloat(), dark)
            c.drawRect(x + 2f, 0f, x + 3f, PX.toFloat(), light)
            x += 6f
        }
        // pas okien co „piętro” hali
        for (row in 0 until 2) {
            val y = row * 32f + 4f
            c.drawRect(0f, y - 1f, PX.toFloat(), y + 8f, Paint().apply { color = shade(base, 0.6f) })
            var wx = 1f
            var i = 0
            while (wx < PX) {
                c.drawRect(wx, y, wx + 6f, y + 7f, glassPaint(p, 77 + row * 13 + i, wx, y, 7f))
                wx += 8f
                i++
            }
        }
        return b
    }

    /** Wieżowiec: szklana ściana ze słupkami, odbicie nieba, wieczorem świecące piętra. */
    private fun tower(glass: Int, p: Pal, seed: Int): Bitmap {
        val (b, c) = bmp(glass)
        c.drawRect(0f, 0f, PX.toFloat(), PX.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, PX.toFloat(), PX.toFloat(), shade(glass, 1.25f), shade(glass, 0.85f), Shader.TileMode.CLAMP)
        })
        for (row in 0 until 4) for (col in 0 until 4) {
            val x = col * 16f + 1.5f
            val y = row * 16f + 1.5f
            if (p.night) {
                c.drawRect(x, y, x + 13f, y + 13f, glassPaint(p, seed * 23 + row * 4 + col, x, y, 13f))
            } else if ((row + col + seed) % 5 == 0) {
                c.drawRect(x, y, x + 13f, y + 13f, Paint().apply { color = shade(glass, 1.35f) })
            }
        }
        val mull = Paint().apply { color = if (p.night) 0xFF0E1218.toInt() else shade(glass, 0.6f) }
        for (i in 0..4) {
            c.drawRect(i * 16f - 0.75f, 0f, i * 16f + 0.75f, PX.toFloat(), mull)
            c.drawRect(0f, i * 16f - 0.75f, PX.toFloat(), i * 16f + 0.75f, mull)
        }
        return b
    }

    /** Parter: witryny sklepów z szyldem. */
    private fun shop(p: Pal): Bitmap {
        val (b, c) = bmp(if (p.night) 0xFF14161C.toInt() else 0xFF3A3F47.toInt())
        val signs = p.accent
        for (col in 0 until 2) {
            val x = col * 32f
            // szyld
            c.drawRect(x + 2f, 34f, x + 30f, 40f, Paint().apply { color = signs[col % signs.size] })
            // witryna (wieczorem jasna)
            c.drawRect(x + 3f, 42f, x + 29f, 62f, Paint().apply {
                color = if (p.night) 0xFFFFE4B0.toInt() else shade(p.win, 1.2f)
            })
            c.drawRect(x + 15.5f, 42f, x + 16.5f, 62f, Paint().apply { color = 0xFF20242A.toInt() })
        }
        // górna część (nad witrynami) – ściana
        c.drawRect(0f, 0f, PX.toFloat(), 33f, Paint().apply { color = if (p.night) 0xFF2A2E35.toInt() else 0xFFB9BEC4.toInt() })
        return b
    }
}
