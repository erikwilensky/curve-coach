package coach.curve

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurveDetectorTest {
    /** Straight, then an arc of [radius] turning [deg] to the given side, then straight. */
    private fun road(radius: Double, deg: Double, left: Boolean, nodeSpacing: Double = 10.0): List<Xy> {
        val pts = ArrayList<Xy>()
        for (i in 0..20) pts += Xy(0.0, i * 10.0) // 200 m north
        val start = pts.last()
        val sign = if (left) 1.0 else -1.0
        val centre = Xy(start.x - sign * radius, start.y)
        val total = deg * PI / 180
        val n = maxOf(1, (radius * total / nodeSpacing).toInt())
        for (i in 1..n) {
            val a = total * i / n
            pts += Xy(centre.x + sign * radius * cos(a), centre.y + radius * sin(a))
        }
        val end = pts.last()
        val h = PI / 2 + sign * total
        for (i in 1..20) pts += Xy(end.x + cos(h) * 10 * i, end.y + sin(h) * 10 * i)
        return pts
    }

    @Test fun straightRoadHasNoCurves() {
        val pts = List(100) { Xy(0.0, it * 10.0) }
        assertTrue(CurveDetector().detect(pts).isEmpty())
    }

    @Test fun findsRadiusOfARightBend() {
        val curves = CurveDetector().detect(road(60.0, 90.0, left = false))
        assertEquals(1, curves.size)
        val c = curves[0]
        assertEquals(Side.RIGHT, c.side)
        assertTrue(abs(c.radiusM - 60) < 8, "radius ${c.radiusM}")
        assertTrue(abs(c.turnDeg - 90) < 10, "turn ${c.turnDeg}")
        assertTrue(abs(c.startM - 200) < 25, "start ${c.startM}")
    }

    @Test fun findsLeftHairpin() {
        val curves = CurveDetector().detect(road(18.0, 170.0, left = true, nodeSpacing = 5.0))
        assertEquals(1, curves.size)
        assertEquals(Side.LEFT, curves[0].side)
        assertTrue(curves[0].radiusM < 30, "radius ${curves[0].radiusM}")
    }

    @Test fun roughlyMappedCornerGivesSensibleRadius() {
        // A 90 degree bend drawn as a single sharp corner.
        val pts = List(21) { Xy(0.0, it * 10.0) } + List(20) { Xy((it + 1) * 10.0, 200.0) }
        val c = CurveDetector().detect(pts).single()
        assertEquals(Side.RIGHT, c.side)
        assertTrue(c.radiusM in 12.0..40.0, "radius ${c.radiusM}")
    }

    @Test fun gentleSweeperIsIgnored() {
        assertTrue(CurveDetector().detect(road(800.0, 30.0, left = true)).isEmpty())
    }

    @Test fun speedFormula() {
        val a = SpeedAdvisor(0.30)
        assertEquals(30, a.entryKmh(30.0)) // 33.7 rounds down
        assertEquals(40, a.entryKmh(50.0)) // 43.6
        assertEquals(60, a.entryKmh(100.0)) // 61.6
    }
}
