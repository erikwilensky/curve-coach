package coach.curve

/** One OpenStreetMap way that is a road. */
data class Way(
    val id: Long,
    val nodeIds: List<Long>,
    val highway: String,
    val name: String? = null,
    val ref: String? = null,
    /** 1 = one way along node order, -1 = one way against it, 0 = both ways. */
    val oneway: Int = 0,
)

/**
 * The roads around the rider, with node positions on a local plane.
 * Ways can come from several downloaded map cells; duplicates are merged by id.
 */
class RoadNetwork(
    val projection: LocalProjection,
    nodes: Map<Long, LatLon>,
    ways: Collection<Way>,
) {
    val nodeXy: Map<Long, Xy>
    val ways: List<Way>
    /** node id -> list of (way index, position of that node in the way) */
    val nodeWays: Map<Long, List<Pair<Int, Int>>>

    init {
        nodeXy = nodes.mapValues { projection.toXy(it.value) }
        this.ways = ways.filter { w -> w.nodeIds.size >= 2 && w.nodeIds.all { it in nodeXy } }
        val nw = HashMap<Long, MutableList<Pair<Int, Int>>>()
        this.ways.forEachIndexed { wi, w ->
            w.nodeIds.forEachIndexed { pos, n -> nw.getOrPut(n) { ArrayList(2) }.add(wi to pos) }
        }
        nodeWays = nw
    }

    fun xy(nodeId: Long): Xy = nodeXy.getValue(nodeId)

    companion object {
        /** Road types worth following. Service roads and tracks are left out so car parks don't steal the match. */
        val RIDEABLE = setOf(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link", "living_street",
        )

        fun parseOneway(tag: String?, highway: String): Int = when (tag) {
            "yes", "true", "1" -> 1
            "-1", "reverse" -> -1
            "no", "false", "0" -> 0
            else -> if (highway == "motorway") 1 else 0
        }
    }
}
