package coach.curve.app

import android.util.Log
import coach.curve.LatLon
import coach.curve.LocalProjection
import coach.curve.OverpassParser
import coach.curve.RoadNetwork
import coach.curve.Way
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.floor

/**
 * Downloads OpenStreetMap roads in square cells of [CELL] degrees (about 4.4 km)
 * and keeps them on the phone, so roads already ridden or saved work without signal.
 * Cells around the rider are loaded as they move; [onNetwork] gets a fresh road network
 * whenever new cells arrive.
 */
class MapCache(
    private val dir: File,
    private val onNetwork: (RoadNetwork) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    data class Cell(val x: Int, val y: Int)

    private val loaded = HashMap<Cell, OverpassParser.Result>()
    private val pending = HashSet<Cell>()
    private val failedAt = HashMap<Cell, Long>()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var lastCentre: Cell? = null
    /** One flat-map origin per ride, so bend positions stay comparable when the network is rebuilt. */
    private var origin: LatLon? = null

    init { dir.mkdirs() }

    private fun cellOf(p: LatLon) = Cell(floor(p.lon / CELL).toInt(), floor(p.lat / CELL).toInt())

    /** Call on every GPS fix. Cheap when nothing needs loading. */
    @Synchronized
    fun ensure(p: LatLon, ring: Int = 1) {
        val c = cellOf(p)
        val wanted = ArrayList<Cell>()
        for (dx in -ring..ring) for (dy in -ring..ring) wanted += Cell(c.x + dx, c.y + dy)
        val missing = wanted.filter { it !in loaded && it !in pending && !recentlyFailed(it) }
        if (missing.isEmpty() && c == lastCentre) return
        lastCentre = c
        for (m in missing) {
            pending += m
            io.execute { load(m) }
        }
        io.execute { rebuild(p) } // runs after the queued loads, since the executor keeps order
    }

    /** Downloads a wider area for riding without signal: about 30 km across. */
    fun saveArea(p: LatLon, onDone: (Int) -> Unit) {
        val c = cellOf(p)
        val cells = ArrayList<Cell>()
        for (dx in -3..3) for (dy in -3..3) cells += Cell(c.x + dx, c.y + dy)
        io.execute {
            var ok = 0
            cells.forEachIndexed { i, cell ->
                onStatus("Saving area ${i + 1}/${cells.size}…")
                if (fileFor(cell).exists() || download(cell)) ok++
            }
            onDone(ok)
            onStatus("Saved $ok of ${cells.size} map squares")
        }
    }

    private fun recentlyFailed(c: Cell) = (failedAt[c] ?: 0L) > System.currentTimeMillis() - 60_000

    private fun fileFor(c: Cell) = File(dir, "${c.x}_${c.y}.json")

    private fun load(c: Cell) {
        try {
            val f = fileFor(c)
            val stale = f.exists() && System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS
            if (!f.exists() || stale) {
                if (!download(c) && !f.exists()) {
                    synchronized(this) { failedAt[c] = System.currentTimeMillis() }
                    return
                }
            }
            val r = OverpassParser.parse(f.readText())
            synchronized(this) { loaded[c] = r }
        } catch (e: Exception) {
            Log.w(TAG, "cell $c failed", e)
            synchronized(this) { failedAt[c] = System.currentTimeMillis() }
        } finally {
            synchronized(this) { pending -= c }
        }
    }

    private fun download(c: Cell): Boolean {
        val s = c.y * CELL; val w = c.x * CELL
        val q = OverpassParser.query(s, w, s + CELL, w + CELL)
        for (server in SERVERS) {
            try {
                onStatus("Downloading roads…")
                val con = URL(server).openConnection() as HttpURLConnection
                con.requestMethod = "POST"; con.doOutput = true
                con.connectTimeout = 15_000; con.readTimeout = 60_000
                con.setRequestProperty("User-Agent", "CurveCoach/0.1 (personal motorcycle app)")
                con.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
                if (con.responseCode != 200) continue
                val body = con.inputStream.bufferedReader().readText()
                val tmp = File(dir, "${c.x}_${c.y}.tmp")
                tmp.writeText(body)
                tmp.renameTo(fileFor(c))
                return true
            } catch (e: Exception) {
                Log.w(TAG, "download from $server failed", e)
            }
        }
        onStatus("No signal for map download")
        return false
    }

    private fun rebuild(p: LatLon) {
        val c = cellOf(p)
        val parts: List<OverpassParser.Result>
        synchronized(this) {
            // Forget cells far behind to keep memory small.
            loaded.keys.filter { kotlin.math.abs(it.x - c.x) > 2 || kotlin.math.abs(it.y - c.y) > 2 }
                .forEach { loaded.remove(it) }
            parts = loaded.values.toList()
        }
        if (parts.isEmpty()) return
        val nodes = HashMap<Long, LatLon>()
        val ways = LinkedHashMap<Long, Way>()
        for (r in parts) { nodes.putAll(r.nodes); r.ways.forEach { ways[it.id] = it } }
        val o = origin ?: p.also { origin = it }
        onNetwork(RoadNetwork(LocalProjection(o), nodes, ways.values))
        onStatus("Roads loaded: ${ways.size}")
    }

    fun shutdown() = io.shutdownNow()

    companion object {
        const val TAG = "MapCache"
        const val CELL = 0.04
        const val MAX_AGE_MS = 60L * 24 * 3600 * 1000 // refresh after 60 days
        val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
