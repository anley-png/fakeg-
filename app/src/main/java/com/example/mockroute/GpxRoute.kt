package com.example.mockroute

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** One route point: position, altitude, time offset from the first point, speed (m/s) and bearing (degrees). */
data class Pt(
    val lat: Double,
    val lon: Double,
    val ele: Double?,
    val offMs: Long,
    val speed: Float,
    val bearing: Float
)

private class Raw(val lat: Double, val lon: Double, val ele: Double?, val t: Long?, val sp: Float?)

object GpxRoute {
    private val trkpt = Regex("""<trkpt lat="([\d.\-]+)" lon="([\d.\-]+)">(.*?)</trkpt>""", RegexOption.DOT_MATCHES_ALL)
    private val eleRe = Regex("""<ele>([\d.\-eE]+)</ele>""")
    private val timeRe = Regex("""<time>(.*?)</time>""")
    private val speedRe = Regex("""<speed>([\d.\-eE]+)</speed>""")

    /** defaultIntervalSec is used only when the GPX file has no <time> tags. */
    fun parse(xml: String, defaultIntervalSec: Double): List<Pt> {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")

        val raws = trkpt.findAll(xml).map { m ->
            val body = m.groupValues[3]
            Raw(
                m.groupValues[1].toDouble(),
                m.groupValues[2].toDouble(),
                eleRe.find(body)?.groupValues?.get(1)?.toDoubleOrNull(),
                timeRe.find(body)?.groupValues?.get(1)?.let { runCatching { fmt.parse(it)!!.time }.getOrNull() },
                speedRe.find(body)?.groupValues?.get(1)?.toFloatOrNull()
            )
        }.toList()
        if (raws.isEmpty()) return emptyList()

        val hasTimes = raws.all { it.t != null }
        val offs = raws.indices.map { i ->
            if (hasTimes) raws[i].t!! - raws[0].t!! else (i * defaultIntervalSec * 1000.0).toLong()
        }

        return raws.indices.map { i ->
            val a = raws[i]
            val b = if (i + 1 < raws.size) raws[i + 1] else null
            val prev = if (i > 0) raws[i - 1] else null
            val d = when {
                b != null -> deltaLL(a.lat, a.lon, b.lat, b.lon)
                prev != null -> deltaLL(prev.lat, prev.lon, a.lat, a.lon)
                else -> Pair(0.0, 0.0)
            }
            val dtMs = when {
                b != null -> offs[i + 1] - offs[i]
                prev != null -> offs[i] - offs[i - 1]
                else -> 0L
            }
            val sp = a.sp ?: (if (dtMs > 0) (hypot(d.first, d.second) / (dtMs / 1000.0)).toFloat() else 0f)
            Pt(a.lat, a.lon, a.ele, offs[i], sp, bearingOf(d.first, d.second))
        }
    }

    /**
     * Makes the route a closed loop. If the last point is not next to the first one, a short return leg
     * is added (straight line, same speed and gap as the route), so the position never jumps.
     * Returns the new point list and the length of one full cycle in milliseconds.
     */
    fun closeLoop(pts: List<Pt>): Pair<List<Pt>, Long> {
        val first = pts.first()
        val last = pts.last()
        val avgGapMs = max(last.offMs / (pts.size - 1), 1000L)
        val avgSpeed = pts.drop(1).map { it.speed.toDouble() }.average()

        val d = deltaLL(last.lat, last.lon, first.lat, first.lon)
        val dist = hypot(d.first, d.second)
        val hop = max(avgSpeed * avgGapMs / 1000.0, 0.5)
        val n = max(1, Math.round(dist / hop).toInt())          // steps needed to get back to the first point
        val brg = bearingOf(d.first, d.second)
        val sp = ((dist / n) / (avgGapMs / 1000.0)).toFloat()

        val out = pts.toMutableList()
        out[out.size - 1] = last.copy(bearing = brg)
        for (j in 1 until n) {
            val f = j.toDouble() / n
            val ele = if (last.ele != null && first.ele != null) last.ele + (first.ele - last.ele) * f else last.ele
            out.add(
                Pt(
                    Math.round((last.lat + (first.lat - last.lat) * f) * 1e7) / 1e7,
                    Math.round((last.lon + (first.lon - last.lon) * f) * 1e7) / 1e7,
                    ele, last.offMs + j * avgGapMs, sp, brg
                )
            )
        }
        return Pair(out, last.offMs + n * avgGapMs)
    }

    private fun bearingOf(north: Double, east: Double): Float =
        ((Math.toDegrees(atan2(east, north)) + 360.0) % 360.0).toFloat()

    /** North and east distance in meters from point 1 to point 2 (WGS84 meters per degree). */
    private fun deltaLL(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val p = Math.toRadians((lat1 + lat2) / 2)
        val mlat = 111132.954 - 559.822 * cos(2 * p) + 1.175 * cos(4 * p)
        val mlon = 111412.84 * cos(p) - 93.5 * cos(3 * p) + 0.118 * cos(5 * p)
        return Pair((lat2 - lat1) * mlat, (lon2 - lon1) * mlon)
    }
}
