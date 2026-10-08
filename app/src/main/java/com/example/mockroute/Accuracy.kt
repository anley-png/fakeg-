package com.example.mockroute

import java.util.Random

/**
 * Location accuracy that drifts like a real GPS: a small random step from the previous value
 * (up to +-1 m), and now and then (about 1 update in 20) a bigger step of 3 to 6 m.
 * The value always stays between min and max (it bounces off the limits).
 */
class AccuracyWalk(minM: Double, maxM: Double, private val rnd: Random = Random()) {
    private val lo = minOf(minM, maxM)
    private val hi = maxOf(minM, maxM)
    private var value = lo + rnd.nextDouble() * (hi - lo)

    fun next(): Float {
        val big = rnd.nextDouble() < 0.05
        val size = if (big) 3.0 + rnd.nextDouble() * 3.0 else rnd.nextDouble()
        val sign = if (rnd.nextBoolean()) 1.0 else -1.0
        var v = value + sign * size
        if (v > hi) v = hi - (v - hi)          // bounce off the limits instead of sticking to them
        if (v < lo) v = lo + (lo - v)
        value = v.coerceIn(lo, hi)
        return value.toFloat()
    }
}
