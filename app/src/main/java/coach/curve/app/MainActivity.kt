package coach.curve.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import coach.curve.SpeedAdvisor

/** One plain screen: start and stop, riding style, and what the app sees ahead. */
class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var next: TextView
    private lateinit var status: TextView
    private lateinit var startStop: Button
    private lateinit var save: Button
    private var voice: Voice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.BLACK)
        }
        fun text(size: Float, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad / 2, 0, pad / 2)
        }
        col.addView(text(28f, true).apply { text = "Curve Coach" })
        next = text(34f, true).apply { text = "—"; gravity = Gravity.CENTER; minLines = 2 }
        col.addView(next)
        status = text(15f).apply { setTextColor(Color.LTGRAY) }
        col.addView(status)

        startStop = Button(this).apply { textSize = 22f; setOnClickListener { toggle() } }
        col.addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        col.addView(text(18f, true).apply { text = "Riding style" })
        val styles = listOf(
            "Relaxed (about 14° lean)" to SpeedAdvisor.RELAXED,
            "Normal (about 17° lean)" to SpeedAdvisor.NORMAL,
            "Sporty (about 22° lean)" to SpeedAdvisor.SPORTY,
        )
        val group = RadioGroup(this)
        styles.forEachIndexed { i, (label, g) ->
            group.addView(RadioButton(this).apply {
                id = 100 + i; text = label; textSize = 17f; setTextColor(Color.WHITE)
                isChecked = kotlin.math.abs(settings.lateralG - g) < 0.001
            })
        }
        group.setOnCheckedChangeListener { _, id ->
            settings.lateralG = styles[id - 100].second
            pushSettings()
        }
        col.addView(group)

        col.addView(CheckBox(this).apply {
            text = "Call every bend, even when I'm slow enough"; textSize = 16f; setTextColor(Color.WHITE)
            isChecked = settings.callAll
            setOnCheckedChangeListener { _, b -> settings.callAll = b; pushSettings() }
        })

        col.addView(Button(this).apply {
            text = "Test voice"
            setOnClickListener {
                val v = voice ?: Voice(this@MainActivity).also { voice = it }
                v.speak("Left 45, in 200, then right 35")
            }
        })
        save = Button(this).apply {
            text = "Save roads within 15 km for no-signal riding"
            setOnClickListener {
                startService(Intent(this@MainActivity, RideService::class.java).setAction(RideService.ACTION_SAVE_AREA))
            }
        }
        col.addView(save)
        col.addView(text(13f).apply {
            setTextColor(Color.GRAY)
            text = "Advice only. Road data comes from OpenStreetMap and can be wrong or out of date. " +
                "Always ride to what you can see."
        })
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onResume() {
        super.onResume()
        RideService.listener = { state, speed ->
            next.text = state.summary()
            status.text = "Speed ${(speed * 3.6).toInt()} km/h" + (state.roadName?.let { " on $it" } ?: "")
        }
        RideService.statusListener = { status.text = it }
        refresh()
    }

    override fun onPause() {
        RideService.listener = null
        RideService.statusListener = null
        super.onPause()
    }

    override fun onDestroy() {
        voice?.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        startStop.text = if (RideService.running) "Stop" else "Start ride"
        save.isEnabled = RideService.running
        if (!RideService.running) next.text = "—"
    }

    private fun pushSettings() {
        if (RideService.running) {
            startService(Intent(this, RideService::class.java).setAction(RideService.ACTION_SETTINGS))
        }
    }

    private fun toggle() {
        if (RideService.running) {
            stopService(Intent(this, RideService::class.java))
            RideService.running = false
            refresh()
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        start()
    }

    private fun start() {
        startForegroundService(Intent(this, RideService::class.java))
        RideService.running = true
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) start()
        else status.text = "Curve Coach needs location to see the road ahead."
    }
}
