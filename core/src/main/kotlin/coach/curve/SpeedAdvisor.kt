package coach.curve

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Turns a bend radius into an entry speed with v = sqrt(g * r * a),
 * where a is the sideways grip the rider is willing to use, in g.
 * 0.30 g is roughly 17 degrees of lean, 0.45 g roughly 24 degrees.
 */
class SpeedAdvisor(val lateralG: Double = 0.30, val maxKmh: Int = 110) {
    fun entryKmh(radiusM: Double): Int {
        val v = sqrt(9.81 * radiusM * lateralG) * 3.6
        val rounded = (floor(v / 5.0) * 5).toInt()
        return rounded.coerceIn(10, maxKmh)
    }

    companion object {
        val RELAXED = 0.25
        val NORMAL = 0.30
        val SPORTY = 0.40
    }
}
