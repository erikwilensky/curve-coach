package coach.curve

/** What the engine knows after a GPS fix. */
data class RideState(
    val onRoad: Boolean,
    val roadName: String?,
    /** Upcoming bends, nearest first. */
    val curves: List<Curve>,
    val callout: Callout?,
    val advisor: SpeedAdvisor,
) {
    /** One line for the screen, e.g. "Left 45 in 230 m". */
    fun summary(): String {
        if (!onRoad) return "Looking for the road…"
        val c = curves.firstOrNull() ?: return "No bends ahead" + (roadName?.let { " on $it" } ?: "")
        val side = if (c.side == Side.LEFT) "Left" else "Right"
        return "$side ${advisor.entryKmh(c.radiusM)} in ${c.startM.toInt()} m (radius ${c.radiusM.toInt()} m)"
    }
}

/**
 * Ties the pieces together: match the fix to a road, trace the road ahead,
 * find the bends, and decide whether to speak.
 */
class RideEngine(var advisor: SpeedAdvisor, var callAll: Boolean = false) {
    private var network: RoadNetwork? = null
    private var paths: PathAhead? = null
    private val detector = CurveDetector()
    private var announcer = Announcer(advisor, callAll = callAll)

    fun setNetwork(net: RoadNetwork) {
        network = net
        paths = PathAhead(net)
    }

    fun hasNetwork() = network != null

    fun setSettings(advisor: SpeedAdvisor, callAll: Boolean) {
        this.advisor = advisor
        this.callAll = callAll
        announcer = Announcer(advisor, callAll = callAll)
    }

    /**
     * [speedMps] and [bearingDeg] come from the GPS. Below walking pace the
     * GPS heading is noise, so nothing is matched until the bike is moving.
     */
    fun onFix(pos: LatLon, speedMps: Double, bearingDeg: Double?): RideState {
        val net = network
        val pa = paths
        if (net == null || pa == null || bearingDeg == null || speedMps < 2.5) {
            return RideState(false, null, emptyList(), null, advisor)
        }
        val p = net.projection.toXy(pos)
        val m = pa.match(p, bearingDeg) ?: return RideState(false, null, emptyList(), null, advisor)
        val trace = pa.trace(m, PathAhead.lookAheadM(speedMps))
        val curves = detector.detect(trace)
        val callout = announcer.update(curves, speedMps)
        val way = net.ways[m.wayIndex]
        return RideState(true, way.ref ?: way.name, curves, callout, advisor)
    }
}
