package coach.curve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideEngineTest {
    private val origin = LatLon(18.8, 98.9)
    private val proj = LocalProjection(origin)

    /** Two ways: 1 km north, then a right bend (r = 40 m) into an eastbound road with the same ref. */
    private fun network(): RoadNetwork {
        val nodes = HashMap<Long, LatLon>()
        var id = 1L
        val a = ArrayList<Long>()
        for (i in 0..100) { nodes[id] = proj.toLatLon(Xy(0.0, i * 10.0)); a += id++ }
        val b = ArrayList<Long>()
        b += a.last()
        val r = 40.0
        for (i in 1..8) {
            val ang = Math.PI / 2 * i / 8
            nodes[id] = proj.toLatLon(Xy(r - r * Math.cos(ang), 1000 + r * Math.sin(ang))); b += id++
        }
        val last = proj.toXy(nodes.getValue(b.last()))
        for (i in 1..50) { nodes[id] = proj.toLatLon(Xy(last.x + i * 10.0, last.y)); b += id++ }
        // A side road branching left at the junction, to make sure the tracer keeps to route 1095.
        val c = arrayListOf(a.last())
        for (i in 1..30) { nodes[id] = proj.toLatLon(Xy(-i * 10.0, 1000.0)); c += id++ }
        return RoadNetwork(
            proj, nodes,
            listOf(
                Way(1, a, "primary", ref = "1095"),
                Way(2, b, "primary", ref = "1095"),
                Way(3, c, "residential"),
            ),
        )
    }

    @Test fun callsTheBendOnceAtTheRightTime() {
        val engine = RideEngine(SpeedAdvisor(0.30))
        engine.setNetwork(network())
        val speed = 70 / 3.6
        val calls = ArrayList<Pair<Double, Callout>>()
        var y = 0.0
        while (y < 1000) {
            val s = engine.onFix(proj.toLatLon(Xy(3.0, y)), speed, 1.0)
            assertTrue(s.onRoad)
            s.callout?.let { calls += y to it }
            y += speed // one fix per second
        }
        assertEquals(1, calls.size, calls.toString())
        val (where, call) = calls[0]
        assertTrue(call.text.startsWith("Right 35"), call.text)
        assertTrue(1000 - where in 100.0..200.0, "called at $where")
    }

    @Test fun quietInsideABend() {
        val a = Announcer(SpeedAdvisor())
        val c = Curve(Side.LEFT, startM = 0.0, apexM = 20.0, endM = 60.0, radiusM = 40.0, turnDeg = 60.0, apex = Xy(0.0, 0.0))
        assertNull(a.update(listOf(c), 20.0))
    }

    @Test fun nothingWhenStoppedOrOffRoad() {
        val engine = RideEngine(SpeedAdvisor())
        engine.setNetwork(network())
        assertNull(engine.onFix(proj.toLatLon(Xy(0.0, 100.0)), 0.5, 0.0).callout)
        assertTrue(!engine.onFix(proj.toLatLon(Xy(500.0, 100.0)), 20.0, 0.0).onRoad)
    }

    @Test fun parsesOverpass() {
        val json = """{"elements":[
          {"type":"way","id":5,"nodes":[1,2],"tags":{"highway":"primary","ref":"108","oneway":"yes"}},
          {"type":"way","id":6,"nodes":[1,2],"tags":{"highway":"footway"}},
          {"type":"node","id":1,"lat":18.0,"lon":98.0},{"type":"node","id":2,"lat":18.001,"lon":98.0}]}"""
        val r = OverpassParser.parse(json)
        assertEquals(1, r.ways.size)
        assertEquals(1, r.ways[0].oneway)
        assertNotNull(r.nodes[2])
    }
}
