package coach.curve

import kotlin.math.abs
import kotlin.math.max

/** Where the rider is on the road network. */
data class Match(
    val wayIndex: Int,
    /** Index of the segment start node within the way. Segment runs from seg to seg+1. */
    val seg: Int,
    /** +1 if riding in node order, -1 if against. */
    val dir: Int,
    /** The rider's position snapped onto the segment. */
    val snapped: Xy,
    val distanceM: Double,
)

/**
 * Finds which road the rider is on and traces the most likely road ahead.
 * Without a planned route we assume the rider stays on the same road
 * (same way, else same ref or name, else the straightest continuation).
 */
class PathAhead(private val net: RoadNetwork) {

    /** Grid index of segments for fast nearest-segment lookups. */
    private val cell = 100.0
    private val grid = HashMap<Long, MutableList<Pair<Int, Int>>>()

    init {
        net.ways.forEachIndexed { wi, w ->
            for (s in 0 until w.nodeIds.size - 1) {
                val a = net.xy(w.nodeIds[s]); val b = net.xy(w.nodeIds[s + 1])
                val x0 = Math.floorDiv(minOf(a.x, b.x).toLong(), cell.toLong())
                val x1 = Math.floorDiv(maxOf(a.x, b.x).toLong(), cell.toLong())
                val y0 = Math.floorDiv(minOf(a.y, b.y).toLong(), cell.toLong())
                val y1 = Math.floorDiv(maxOf(a.y, b.y).toLong(), cell.toLong())
                for (gx in x0..x1) for (gy in y0..y1) grid.getOrPut(key(gx, gy)) { ArrayList() }.add(wi to s)
            }
        }
    }

    private fun key(gx: Long, gy: Long) = (gx shl 32) xor (gy and 0xffffffffL)

    /**
     * Matches a GPS fix to a road. [headingDeg] is the direction of travel.
     * Returns null when no road is within [maxDistM] heading the right way.
     */
    fun match(p: Xy, headingDeg: Double, maxDistM: Double = 35.0): Match? {
        val gx = Math.floorDiv(p.x.toLong(), cell.toLong())
        val gy = Math.floorDiv(p.y.toLong(), cell.toLong())
        var best: Match? = null
        var bestScore = Double.MAX_VALUE
        val seen = HashSet<Long>()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val list = grid[key(gx + dx, gy + dy)] ?: continue
            for ((wi, s) in list) {
                if (!seen.add((wi.toLong() shl 20) or s.toLong())) continue
                val w = net.ways[wi]
                val a = net.xy(w.nodeIds[s]); val b = net.xy(w.nodeIds[s + 1])
                val ab = b - a
                val len2 = ab.dot(ab)
                if (len2 < 1e-6) continue
                val t = ((p - a).dot(ab) / len2).coerceIn(0.0, 1.0)
                val snapped = a + ab * t
                val d = (p - snapped).length()
                if (d > maxDistM) continue
                val segBearing = bearingDeg(ab)
                val fwdDiff = bearingDiff(segBearing, headingDeg)
                val dir = if (fwdDiff <= 90) 1 else -1
                if (w.oneway != 0 && dir != w.oneway) continue
                val angDiff = if (dir == 1) fwdDiff else 180 - fwdDiff
                if (angDiff > 50) continue
                // One degree of heading mismatch costs about as much as half a metre of distance.
                val score = d + angDiff * 0.5
                if (score < bestScore) {
                    bestScore = score
                    best = Match(wi, s, dir, snapped, d)
                }
            }
        }
        return best
    }

    /** One stretch of the traced path. */
    data class Step(val nodeId: Long, val wayIndex: Int)

    /**
     * Traces the road ahead from a match for about [lengthM] metres.
     * Returns points on the local plane, starting at the snapped position.
     */
    fun trace(m: Match, lengthM: Double): List<Xy> {
        val pts = ArrayList<Xy>()
        pts += m.snapped
        var wi = m.wayIndex
        var w = net.ways[wi]
        // index of the next node we are heading to
        var pos = if (m.dir == 1) m.seg + 1 else m.seg
        var dir = m.dir
        var total = 0.0
        var prevNode: Long? = if (m.dir == 1) w.nodeIds[m.seg] else w.nodeIds[m.seg + 1]
        val visited = HashSet<Long>()
        while (total < lengthM) {
            val nodeId = w.nodeIds[pos]
            val p = net.xy(nodeId)
            total += (p - pts.last()).length()
            pts += p
            if (!visited.add(nodeId)) break // loop guard
            val nextPos = pos + dir
            val sameWayContinues = nextPos in w.nodeIds.indices
            val incoming = bearingDeg(p - net.xy(prevNode ?: nodeId))
            val choice = chooseNext(nodeId, wi, pos, dir, incoming, w, sameWayContinues) ?: break
            prevNode = nodeId
            wi = choice.first; pos = choice.second; dir = choice.third
            w = net.ways[wi]
        }
        return pts
    }

    /** Picks the next (way, node position, direction) at a node. */
    private fun chooseNext(
        nodeId: Long, curWay: Int, curPos: Int, curDir: Int, incomingBearing: Double,
        cur: Way, sameWayContinues: Boolean,
    ): Triple<Int, Int, Int>? {
        if (sameWayContinues) return Triple(curWay, curPos + curDir, curDir)
        data class Cand(val wi: Int, val pos: Int, val dir: Int, val turn: Double, val rank: Int)
        val cands = ArrayList<Cand>()
        for ((wi, pos) in net.nodeWays[nodeId] ?: emptyList()) {
            val w = net.ways[wi]
            for (d in intArrayOf(1, -1)) {
                if (wi == curWay && d == -curDir) continue
                if (w.oneway != 0 && d != w.oneway) continue
                val np = pos + d
                if (np !in w.nodeIds.indices) continue
                val turn = bearingDiff(incomingBearing, bearingDeg(net.xy(w.nodeIds[np]) - net.xy(nodeId)))
                if (turn > 150) continue
                val rank = when {
                    cur.ref != null && w.ref == cur.ref -> 0
                    cur.name != null && w.name == cur.name -> 0
                    w.highway == cur.highway -> 1
                    else -> 2
                }
                cands += Cand(wi, np, d, turn, rank)
            }
        }
        val best = cands.minWithOrNull(compareBy<Cand> { it.rank }.thenBy { it.turn }) ?: return null
        return Triple(best.wi, best.pos, best.dir)
    }

    companion object {
        /** How far ahead to look: at least 400 m, or 15 seconds at the current speed. */
        fun lookAheadM(speedMps: Double) = max(400.0, speedMps * 15.0) + 200.0

        @Suppress("unused")
        private fun unused() = abs(0)
    }
}
