package coach.curve

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** A point in metres on a flat local plane. x = east, y = north. */
data class Xy(val x: Double, val y: Double) {
    operator fun minus(o: Xy) = Xy(x - o.x, y - o.y)
    operator fun plus(o: Xy) = Xy(x + o.x, y + o.y)
    operator fun times(s: Double) = Xy(x * s, y * s)
    fun dot(o: Xy) = x * o.x + y * o.y
    fun length() = sqrt(x * x + y * y)
}

const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Equirectangular projection around a reference point. Accurate to well under
 * 1% within ~20 km of the reference, which is all the look-ahead ever needs.
 */
class LocalProjection(val origin: LatLon) {
    private val kx = EARTH_RADIUS_M * cos(Math.toRadians(origin.lat)) * PI / 180.0
    private val ky = EARTH_RADIUS_M * PI / 180.0

    fun toXy(p: LatLon) = Xy((p.lon - origin.lon) * kx, (p.lat - origin.lat) * ky)
    fun toLatLon(p: Xy) = LatLon(origin.lat + p.y / ky, origin.lon + p.x / kx)
}

/** Compass bearing in degrees (0 = north, 90 = east) of a vector on the local plane. */
fun bearingDeg(v: Xy): Double {
    val d = Math.toDegrees(atan2(v.x, v.y))
    return if (d < 0) d + 360.0 else d
}

/** Smallest absolute difference between two bearings, 0..180. */
fun bearingDiff(a: Double, b: Double): Double {
    var d = (a - b) % 360.0
    if (d < 0) d += 360.0
    return if (d > 180.0) 360.0 - d else d
}

/** Wraps an angle in radians into (-PI, PI]. */
fun wrapPi(a: Double): Double {
    var x = a
    while (x <= -PI) x += 2 * PI
    while (x > PI) x -= 2 * PI
    return x
}
