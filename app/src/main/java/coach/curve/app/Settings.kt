package coach.curve.app

import android.content.Context
import coach.curve.SpeedAdvisor

/** Rider choices, stored on the phone. */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Sideways grip used for advice, in g. */
    var lateralG: Double
        get() = p.getFloat("lateralG", SpeedAdvisor.NORMAL.toFloat()).toDouble()
        set(v) = p.edit().putFloat("lateralG", v.toFloat()).apply()

    /** Call every bend, not only those you are too fast for. */
    var callAll: Boolean
        get() = p.getBoolean("callAll", false)
        set(v) = p.edit().putBoolean("callAll", v).apply()

    fun advisor() = SpeedAdvisor(lateralG)
}
