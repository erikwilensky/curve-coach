package coach.curve

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Downloads real Thai mountain roads from OpenStreetMap and "rides" them at a
 * steady speed, writing every callout to build/live-roads/<road>.txt.
 * Needs the internet: run with  gradle :core:test -PliveRoads
 */
class LiveRoadsTest {
    data class Road(val name: String, val ref: String, val start: LatLon, val bbox: DoubleArray)

    private val roads = listOf(
        // Mae Malai to Pai, the "762 curves" road.
        Road("route-1095-pai", "1095", LatLon(19.1225, 98.8540), doubleArrayOf(19.10, 98.40, 19.40, 98.87)),
        // Doi Inthanon summit road.
        Road("route-1009-inthanon", "1009", LatLon(18.5290, 98.6050), doubleArrayOf(18.50, 98.45, 18.60, 98.62)),
        // Highway 12 through Khao Yai foothills is too straight; use 2090 Khao Yai park road.
        Road("route-2090-khaoyai", "2090", LatLon(14.5650, 101.3700), doubleArrayOf(14.38, 101.30, 14.58, 101.42)),
    )

    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private fun fetch(q: String): String {
        var last: Exception? = null
        for (s in servers) {
            try {
                val c = URL(s).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 20000; c.readTimeout = 120000
                c.setRequestProperty("User-Agent", "curve-coach-test/0.1")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (c.responseCode == 200) return c.inputStream.bufferedReader().readText()
                last = RuntimeException("$s -> ${c.responseCode}")
            } catch (e: Exception) { last = e }
        }
        throw last!!
    }

    @Test fun rideRealRoads() {
        val outDir = File("build/live-roads").apply { mkdirs() }
        var totalCalls = 0
        for (road in roads) {
            val b = road.bbox
            // Only the numbered road itself, to keep the download small.
            val q = "[out:json][timeout:60];way[\"highway\"][\"ref\"=\"${road.ref}\"](${b[0]},${b[1]},${b[2]},${b[3]});out body;>;out skel qt;"
            val parsed = try {
                OverpassParser.parse(fetch(q))
            } catch (e: Exception) {
                println("${road.name}: download failed: $e")
                continue
            }
            val net = RoadNetwork(LocalProjection(road.start), parsed.nodes, parsed.ways)
            val pa = PathAhead(net)
            // Find the road's node nearest the start and its heading.
            val refWays = net.ways.withIndex().filter { it.value.ref == road.ref }
            check(refWays.isNotEmpty()) { "no ways with ref ${road.ref}" }
            val startXy = Xy(0.0, 0.0)
            var bestD = Double.MAX_VALUE; var bestW = 0; var bestS = 0
            for ((wi, w) in refWays) for (s in 0 until w.nodeIds.size - 1) {
                val d = (net.xy(w.nodeIds[s]) - startXy).length()
                if (d < bestD) { bestD = d; bestW = wi; bestS = s }
            }
            val w = net.ways[bestW]
            val a = net.xy(w.nodeIds[bestS]); val c = net.xy(w.nodeIds[bestS + 1])
            val dir = if (w.oneway == -1) -1 else 1
            val m = Match(bestW, bestS, dir, if (dir == 1) a else c, 0.0)
            val route = pa.trace(m, 40_000.0)

            val sb = StringBuilder()
            sb.appendLine("${road.name}: ${parsed.ways.size} ways, route traced ${"%.1f".format(length(route) / 1000)} km, start offset ${bestD.toInt()} m")
            for (g in listOf(SpeedAdvisor.NORMAL)) {
                val engine = RideEngine(SpeedAdvisor(g))
                engine.setNetwork(net)
                val speed = 60 / 3.6
                var along = 0.0
                var calls = 0
                var lost = 0
                val total = length(route) - 600
                while (along < total) {
                    val (p, brg) = pointAt(route, along)
                    val st = engine.onFix(net.projection.toLatLon(p), speed, brg)
                    if (!st.onRoad) lost++
                    st.callout?.let {
                        calls++
                        sb.appendLine("km ${"%6.2f".format(along / 1000)}  ${it.text}   (r=${it.curve.radiusM.toInt()} m, turn ${it.curve.turnDeg.toInt()}°, starts in ${it.curve.startM.toInt()} m)")
                    }
                    along += speed
                }
                sb.appendLine("lateral ${g}g at 60 km/h: $calls callouts over ${"%.1f".format(total / 1000)} km, lost the road on $lost fixes")
                totalCalls += calls
            }
            File(outDir, "${road.name}.txt").writeText(sb.toString())
            println(sb)
        }
        assertTrue(totalCalls > 20, "expected plenty of callouts on mountain roads, got $totalCalls")
    }

    private fun length(l: List<Xy>) = l.zipWithNext { a, b -> (b - a).length() }.sum()

    private fun pointAt(l: List<Xy>, d: Double): Pair<Xy, Double> {
        var acc = 0.0
        for (i in 0 until l.size - 1) {
            val seg = (l[i + 1] - l[i]).length()
            if (acc + seg >= d && seg > 0) {
                val t = (d - acc) / seg
                return (l[i] + (l[i + 1] - l[i]) * t) to bearingDeg(l[i + 1] - l[i])
            }
            acc += seg
        }
        return l.last() to bearingDeg(l.last() - l[l.size - 2])
    }
}
