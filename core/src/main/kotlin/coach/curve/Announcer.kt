package coach.curve

import kotlin.math.max
import kotlin.math.roundToInt

/** A spoken message plus the bend it is about. */
data class Callout(val text: String, val curve: Curve, val advisedKmh: Int)

/**
 * Decides what to say, and when. Each bend is called once, a few seconds
 * before it starts; a bend that follows straight after is chained on
 * ("Left 40, then right 35"). Bends the rider is already slow enough for
 * are skipped unless [callAll] is on.
 */
class Announcer(
    private val advisor: SpeedAdvisor,
    /** Seconds of warning before the bend starts. */
    private val leadSeconds: Double = 7.0,
    private val minLeadM: Double = 120.0,
    private val callAll: Boolean = false,
) {
    /** Apexes already handled, as map positions. Kept short; old ones fall off. */
    private val called = ArrayDeque<Xy>()

    /**
     * [curves] are measured from the rider on the current trace. Their apex
     * positions are map positions, so a bend seen again on the next fix is recognised.
     */
    fun update(curves: List<Curve>, speedMps: Double): Callout? {
        val speedKmh = speedMps * 3.6
        val trigger = max(minLeadM, speedMps * leadSeconds)
        for ((idx, c) in curves.withIndex()) {
            if (c.startM > trigger) break
            if (wasCalled(c.apex)) continue
            val advised = advisor.entryKmh(c.radiusM)
            remember(c.apex)
            // Already in the bend: usually the tail of one we called, seen again from inside.
            // Too late to help anyway, so stay quiet.
            if (c.startM < 25) continue
            if (!callAll && advised >= speedKmh + 5) continue
            if (advised >= advisor.maxKmh) continue
            val sb = StringBuilder(describe(c, advised))
            if (c.startM > 60) sb.append(", ").append(distanceWords(c.startM))
            val next = curves.getOrNull(idx + 1)
            if (next != null && next.startM - c.endM < 50) {
                val nAdv = advisor.entryKmh(next.radiusM)
                if (nAdv < advisor.maxKmh) {
                    sb.append(", then ").append(describe(next, nAdv))
                    remember(next.apex)
                }
            }
            return Callout(sb.toString(), c, advised)
        }
        return null
    }

    private fun describe(c: Curve, kmh: Int): String {
        val side = if (c.side == Side.LEFT) "left" else "right"
        val kind = when {
            c.turnDeg >= 140 && c.radiusM < 30 -> "hairpin $side"
            c.turnDeg >= 100 -> "long $side"
            else -> side
        }
        return "${kind.replaceFirstChar { it.uppercase() }} $kmh"
    }

    private fun distanceWords(m: Double): String {
        val r = ((m / 50.0).roundToInt() * 50).coerceAtLeast(50)
        return "in $r"
    }

    private fun wasCalled(p: Xy) = called.any { (it - p).length() < 40.0 }

    private fun remember(p: Xy) {
        called.addLast(p)
        while (called.size > 200) called.removeFirst()
    }
}
