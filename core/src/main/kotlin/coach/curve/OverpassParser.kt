package coach.curve

import org.json.JSONObject

/** Reads an Overpass API JSON answer (ways with tags plus their nodes). */
object OverpassParser {
    class Result(val nodes: Map<Long, LatLon>, val ways: List<Way>)

    fun parse(json: String): Result {
        val elements = JSONObject(json).getJSONArray("elements")
        val nodes = HashMap<Long, LatLon>()
        val ways = ArrayList<Way>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            when (e.getString("type")) {
                "node" -> nodes[e.getLong("id")] = LatLon(e.getDouble("lat"), e.getDouble("lon"))
                "way" -> {
                    val tags = e.optJSONObject("tags") ?: continue
                    val highway = tags.optString("highway", "")
                    if (highway !in RoadNetwork.RIDEABLE) continue
                    val arr = e.getJSONArray("nodes")
                    val ids = List(arr.length()) { arr.getLong(it) }
                    ways += Way(
                        id = e.getLong("id"),
                        nodeIds = ids,
                        highway = highway,
                        name = tags.optString("name").ifEmpty { null },
                        ref = tags.optString("ref").ifEmpty { null },
                        oneway = RoadNetwork.parseOneway(tags.optString("oneway").ifEmpty { null }, highway),
                    )
                }
            }
        }
        return Result(nodes, ways)
    }

    /** Overpass QL for all rideable roads in a box. */
    fun query(south: Double, west: Double, north: Double, east: Double): String {
        val types = RoadNetwork.RIDEABLE.joinToString("|")
        return "[out:json][timeout:25];" +
            "way[\"highway\"~\"^($types)$\"]($south,$west,$north,$east);" +
            "out body;>;out skel qt;"
    }
}
