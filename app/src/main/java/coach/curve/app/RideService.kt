package coach.curve.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import coach.curve.LatLon
import coach.curve.RideEngine
import coach.curve.RideState
import java.io.File

/**
 * Runs while riding: reads GPS once a second, finds the bends ahead and speaks them.
 * Other parts of the app (the phone screen now, an Android Auto screen later)
 * can follow along through [listener].
 */
class RideService : Service(), LocationListener {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var engine: RideEngine
    private lateinit var maps: MapCache
    private lateinit var voice: Voice
    private lateinit var settings: Settings
    private var lastSummary = ""
    private var announcedReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        engine = RideEngine(settings.advisor(), settings.callAll)
        voice = Voice(this)
        thread = HandlerThread("ride").apply { start() }
        handler = Handler(thread.looper)
        maps = MapCache(
            File(filesDir, "cells"),
            onNetwork = { net ->
                handler.post {
                    engine.setNetwork(net)
                    if (!announcedReady) { announcedReady = true; voice.speak("Curve coach ready") }
                }
            },
            onStatus = { publishStatus(it) },
        )
        instance = this
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SAVE_AREA -> {
                lastFix?.let { f -> maps.saveArea(f) { } } ?: publishStatus("Waiting for GPS before saving")
                return START_STICKY
            }
            ACTION_SETTINGS -> {
                handler.post { engine.setSettings(settings.advisor(), settings.callAll) }
                return START_STICKY
            }
        }
        startInForeground("Starting GPS…")
        val lm = getSystemService(LocationManager::class.java)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.looper)
        running = true
        publishStatus("Waiting for GPS…")
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val p = LatLon(loc.latitude, loc.longitude)
        lastFix = p
        maps.ensure(p)
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null
        val state = engine.onFix(p, speed, bearing)
        state.callout?.let { voice.speak(it.text) }
        if (!engine.hasNetwork()) return
        val summary = state.summary()
        if (summary != lastSummary) {
            lastSummary = summary
            updateNotification(summary)
        }
        Handler(Looper.getMainLooper()).post { listener?.invoke(state, speed) }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { publishStatus("GPS is switched off") }

    private fun publishStatus(s: String) {
        Handler(Looper.getMainLooper()).post { statusListener?.invoke(s) }
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Riding", NotificationManager.IMPORTANCE_LOW))
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Curve Coach")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        maps.shutdown()
        voice.shutdown()
        thread.quitSafely()
        running = false
        instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "ride"
        const val ACTION_SAVE_AREA = "coach.curve.SAVE_AREA"
        const val ACTION_SETTINGS = "coach.curve.SETTINGS"
        @Volatile var running = false
        @Volatile var lastFix: LatLon? = null
        var instance: RideService? = null
        /** Called on the main thread after every GPS fix. */
        var listener: ((RideState, Double) -> Unit)? = null
        var statusListener: ((String) -> Unit)? = null
    }
}
