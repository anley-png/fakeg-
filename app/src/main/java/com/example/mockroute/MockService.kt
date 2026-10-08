package com.example.mockroute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import java.io.File

class MockService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopAll()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        startInForeground()

        val accMin = intent?.getDoubleExtra("accMin", 16.0) ?: 16.0
        val accMax = intent?.getDoubleExtra("accMax", 22.0) ?: 22.0
        val interval = intent?.getDoubleExtra("interval", 5.0) ?: 5.0
        val hbMin = intent?.getDoubleExtra("hbMin", 1.0) ?: 1.0
        val hbMax = intent?.getDoubleExtra("hbMax", 5.0) ?: 5.0
        val loop = intent?.getBooleanExtra("loop", true) ?: true

        val f = File(filesDir, "route.gpx")
        val pts = if (f.exists()) GpxRoute.parse(f.readText(), interval) else emptyList()
        if (pts.size < 2) {
            toast("Choose a GPX file first")
            stopAll()
            return START_NOT_STICKY
        }
        running = true
        worker = Thread { play(pts, accMin, accMax, minOf(hbMin, hbMax), maxOf(hbMin, hbMax), loop) }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun play(pts: List<Pt>, accMin: Double, accMax: Double, hbMin: Double, hbMax: Double, loop: Boolean) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provs = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        try {
            for (p in provs) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
                lm.addTestProvider(p, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
                lm.setTestProviderEnabled(p, true)
            }
        } catch (e: SecurityException) {
            toast("Select Mock Route as the mock location app in Developer options")
            running = false
            stopAll()
            return
        }

        // loop: add a return leg from the last point back to the first, so there is no jump
        val (all, periodMs) = if (loop) GpxRoute.closeLoop(pts) else Pair(pts, 0L)
        val acc = AccuracyWalk(accMin, accMax)

        val t0 = SystemClock.elapsedRealtime()
        var cycle = 0L
        try {
            do {
                val base = t0 + cycle * periodMs
                for ((i, pt) in all.withIndex()) {
                    if (!running) break
                    sleepUntil(base + pt.offMs)
                    if (!running) break
                    showCounter(i, pts.size, cycle, loop)
                    send(lm, provs, pt, acc)

                    // between two route points, repeat the location at random intervals
                    val nextAt: Long? = when {
                        i + 1 < all.size -> base + all[i + 1].offMs
                        loop -> base + periodMs
                        else -> null
                    }
                    repeatUntil(nextAt, lm, provs, pt, acc, hbMin, hbMax)
                }
                cycle++
            } while (running && loop)
            if (running) toast("Route finished")
        } catch (_: InterruptedException) {
        } finally {
            for (p in provs) {
                try { lm.removeTestProvider(p) } catch (_: Exception) {}
            }
            running = false
            stopAll()
        }
    }

    private fun repeatUntil(
        nextAt: Long?, lm: LocationManager, provs: List<String>, pt: Pt,
        acc: AccuracyWalk, hbMin: Double, hbMax: Double
    ) {
        if (nextAt == null || hbMax <= 0.0) return
        while (running) {
            val waitMs = ((hbMin + Math.random() * (hbMax - hbMin)) * 1000).toLong().coerceAtLeast(200L)
            if (SystemClock.elapsedRealtime() + waitMs >= nextAt - 50) return
            Thread.sleep(waitMs)
            if (!running) return
            send(lm, provs, pt, acc)
        }
    }

    private fun sleepUntil(target: Long) {
        val d = target - SystemClock.elapsedRealtime()
        if (d > 0) Thread.sleep(d)
    }

    private fun send(lm: LocationManager, provs: List<String>, pt: Pt, accWalk: AccuracyWalk) {
        val acc = accWalk.next()
        for (p in provs) {
            val l = Location(p)
            l.latitude = pt.lat
            l.longitude = pt.lon
            if (pt.ele != null) l.altitude = pt.ele
            l.speed = pt.speed
            l.bearing = pt.bearing
            l.accuracy = acc
            l.time = System.currentTimeMillis()
            l.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            l.verticalAccuracyMeters = acc
            l.speedAccuracyMetersPerSecond = 0.5f
            l.bearingAccuracyDegrees = 5f
            try { lm.setTestProviderLocation(p, l) } catch (_: Exception) {}
        }
    }

    // ---------- hop counter (shown in the notification only) ----------
    private fun showCounter(index: Int, routeSize: Int, cycle: Long, loop: Boolean) {
        val text = if (index < routeSize) {
            "Hop ${index + 1} / $routeSize" + (if (loop) "   (round ${cycle + 1})" else "")
        } else {
            "Walking back to hop 1   (${index - routeSize + 1})"
        }
        handler.post {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, "mock")
            .setContentTitle("Mock Route is running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOnlyAlertOnce(true)
            .build()

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("mock", "Mock route", NotificationManager.IMPORTANCE_LOW)
        )
        val n = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }

    private fun stopAll() {
        running = false
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toast(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }
}
