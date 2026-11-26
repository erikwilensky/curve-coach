package coach.curve

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

enum class Side { LEFT, RIGHT }

/** One bend on the road ahead. Distances are measured along the road from the rider. */
data class Curve(
    val side: Side,
    val startM: Double,
    val apexM: Double,
    val endM: Double,
    /** Tightest radius in the bend, metres. */
    val radiusM: Double,
    /** How far the road turns through the bend, degrees (always positive). */
    val turnDeg: Double,
    /** Position of the tightest point, for recognising the same bend on later fixes. */
    val apex: Xy,
)

/**
 * Finds bends in a polyline. The line is resampled every [stepM] metres,
 * heading change is measured over a sliding window of [windowM] metres,
 * and stretches tighter than [maxRadiusM] that turn at least [minTurnDeg] become curves.
 *
 * The window smooths out roughly mapped roads: a bend drawn as one sharp
 * corner still comes out as a sensible radius instead of zero.
 */
class CurveDetector(
    private val stepM: Double = 5.0,
    private val windowM: Double = 30.0,
    private val maxRadiusM: Double = 250.0,
    private val minTurnDeg: Double = 20.0,
    private val minRadiusM: Double = 12.0,
    private val mergeGapM: Double = 15.0,
) {
    fun detect(line: List<Xy>): List<Curve> {
        val pts = resample(line)
        if (pts.size < 4) return emptyList()
        // Unwrapped heading of each step (math angle, counter-clockwise from east).
        val h = DoubleArray(pts.size - 1)
        for (i in h.indices) {
            val d = pts[i + 1] - pts[i]
            h[i] = atan2(d.y, d.x)
            if (i > 0) h[i] = h[i - 1] + wrapPi(h[i] - h[i - 1])
        }
        val half = max(1, (windowM / stepM / 2).toInt())
        // Signed curvature at each step, 1/m. Positive = turning left.
        val k = DoubleArray(h.size)
        for (i in h.indices) {
            val a = (i - half).coerceAtLeast(0)
            val b = (i + half).coerceAtMost(h.size - 1)
            if (b == a) continue
            k[i] = (h[b] - h[a]) / ((b - a) * stepM)
        }
        val kMin = 1.0 / maxRadiusM

        // Collect runs of same-sign curvature above the threshold.
        data class Run(var a: Int, var b: Int, val sign: Int)
        val runs = ArrayList<Run>()
        var i = 0
        while (i < k.size) {
            if (abs(k[i]) < kMin) { i++; continue }
            val sign = if (k[i] > 0) 1 else -1
            var j = i
            while (j + 1 < k.size && abs(k[j + 1]) >= kMin && (k[j + 1] > 0) == (sign > 0)) j++
            val last = runs.lastOrNull()
            if (last != null && last.sign == sign && (i - last.b) * stepM <= mergeGapM) last.b = j
            else runs += Run(i, j, sign)
            i = j + 1
        }

        val out = ArrayList<Curve>()
        for (r in runs) {
            val turn = abs(h[(r.b + 1).coerceAtMost(h.size - 1)] - h[r.a])
            val turnDeg = turn * 180 / PI
            if (turnDeg < minTurnDeg) continue
            var apexIdx = r.a
            for (t in r.a..r.b) if (abs(k[t]) > abs(k[apexIdx])) apexIdx = t
            // The sliding window already smooths single badly placed nodes,
            // so the tightest windowed value is used as the bend's radius.
            val radius = max(minRadiusM, 1.0 / abs(k[apexIdx]))
            out += Curve(
                side = if (r.sign > 0) Side.LEFT else Side.RIGHT,
                startM = r.a * stepM,
                apexM = apexIdx * stepM,
                endM = (r.b + 1) * stepM,
                radiusM = radius,
                turnDeg = turnDeg,
                apex = pts[apexIdx],
            )
        }
        return out
    }

    private fun resample(line: List<Xy>): List<Xy> {
        if (line.size < 2) return line
        val out = ArrayList<Xy>()
        out += line[0]
        var carry = 0.0
        for (s in 0 until line.size - 1) {
            val a = line[s]; val b = line[s + 1]
            val len = (b - a).length()
            if (len < 1e-9) continue
            var t = stepM - carry
            while (t <= len) {
                out += a + (b - a) * (t / len)
                t += stepM
            }
            carry = len - (t - stepM)
        }
        return out
    }
}
