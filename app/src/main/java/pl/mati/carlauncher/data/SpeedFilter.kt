package pl.mati.carlauncher.data

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wygładzanie prędkości do wyświetlania: mediana z 3 odczytów (wycina pojedyncze skoki),
 * potem średnia wykładnicza, pojedyncze „zera” w czasie jazdy ignorowane, a liczba na ekranie
 * zmienia się dopiero, gdy wygładzona wartość odejdzie o ≥ 0,75 km/h (bez migotania 57/58/57).
 */
class SpeedFilter(private val alpha: Double = 0.5) {
    private val win = ArrayDeque<Double>()
    private var ema: Double? = null
    private var shown: Int? = null
    private var zeroRun = 0

    fun reset() {
        win.clear(); ema = null; shown = null; zeroRun = 0
    }

    fun push(raw: Double): Int {
        if (raw < 1.0 && (ema ?: 0.0) > 15.0) {
            zeroRun++
            if (zeroRun < 3) return shown ?: 0
        } else zeroRun = 0
        win.addLast(raw.coerceIn(0.0, 300.0))
        if (win.size > 3) win.removeFirst()
        val med = win.sorted()[win.size / 2]
        val e = ema?.let { it + alpha * (med - it) } ?: med
        ema = e
        val v = if (e < 2.5) 0 else e.roundToInt()
        val s = shown
        shown = if (s == null || abs(e - s) >= 0.75 || v == 0) v else s
        return shown!!
    }
}
