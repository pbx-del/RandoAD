package com.example.montee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

private data class Pt(val t: Long, val h: Double)

class ClimbService : Service(), SensorEventListener {

    private val windowsMin = listOf(5, 10, 20, 30, 60)
    private val sampleMs = 5_000L
    private val gpsGain = 0.005 // correction lente de la dérive météo

    private lateinit var sm: SensorManager
    private lateinit var fused: FusedLocationProviderClient
    private val hist = ArrayList<Pt>()
    private var lastSample = 0L
    private var pSmooth: Float? = null
    private var hBaroPrev: Double? = null
    private var hFused: Double? = null

    // Cumuls (réinitialisés à chaque démarrage du service)
    private val hystM = 2.0        // seuil anti-bruit pour D+/D- (m)
    private val stepMinM = 5f      // pas minimal entre 2 points pour la distance (m)
    private var refAlt: Double? = null
    private var dPlus = 0.0
    private var dMinus = 0.0
    private var distance = 0.0
    private var lastLoc: Location? = null
    private var alarmArmed = true

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            addDistance(l)
            if (!l.hasAltitude()) return
            if (l.hasVerticalAccuracy() && l.verticalAccuracyMeters > 20f) return
            val hGps = gpsAlt(l)
            val f = hFused
            if (f == null) {
                if (hBaroPrev != null) hFused = hGps // 1er calage dès que le baromètre répond
            } else {
                hFused = f + gpsGain * (hGps - f)
            }
        }
    }

    private fun addDistance(l: Location) {
        if (l.hasAccuracy() && l.accuracy > 20f) return
        val last = lastLoc
        if (last == null) { lastLoc = l; return }
        val d = last.distanceTo(l)
        if (d < stepMinM) return // on garde l'ancre : le bruit à l'arrêt ne s'accumule pas
        val dt = (l.time - last.time) / 1000.0
        if (dt > 0 && d / dt <= 20.0) distance += d // ignore les sauts GPS aberrants
        lastLoc = l
    }

    private fun gpsAlt(l: Location): Double =
        if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else l.altitude

    override fun onCreate() {
        super.onCreate()
        ClimbState.running.value = true
        ClimbState.stats.value = Stats(status = "Attente GPS…")

        val ch = NotificationChannel("climb", "Ascension", NotificationManager.IMPORTANCE_LOW)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(ch)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val ach = NotificationChannel("alarm", "Alarme montée", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
        }
        nm.createNotificationChannel(ach)
        ServiceCompat.startForeground(
            this, 1, notif("Démarrage…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        sm = getSystemService(SensorManager::class.java)
        val baro = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (baro == null) {
            ClimbState.stats.value = Stats(status = "Pas de baromètre sur ce téléphone")
            stopSelf()
            return
        }
        sm.registerListener(this, baro, SensorManager.SENSOR_DELAY_NORMAL)

        fused = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
        try {
            fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            ClimbState.stats.value = Stats(status = "Permission localisation refusée")
            stopSelf()
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val raw = e.values[0]
        val p = pSmooth?.let { it + 0.1f * (raw - it) } ?: raw
        pSmooth = p
        val hb = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p).toDouble()
        val prev = hBaroPrev
        hBaroPrev = hb
        val f = hFused ?: return
        val cur = if (prev != null) f + (hb - prev) else f
        hFused = cur

        val now = System.currentTimeMillis()
        if (now - lastSample >= sampleMs) {
            lastSample = now
            hist.add(Pt(now, cur))
            publish(now, cur)
        }
    }

    private fun publish(now: Long, h: Double) {
        val ref = refAlt
        if (ref == null) {
            refAlt = h
        } else if (h - ref >= hystM) {
            dPlus += h - ref; refAlt = h
        } else if (ref - h >= hystM) {
            dMinus += ref - h; refAlt = h
        }
        val rows = ArrayList<Row>()
        for (m in windowsMin) {
            val target = now - m * 60_000L
            val first = hist.first()
            if (first.t > target + 15_000L) {
                rows.add(Row("$m min", null, null))
            } else {
                val ref = hist.firstOrNull { it.t >= target } ?: first
                rows.add(rowOf("$m min", ref, now, h))
            }
        }
        val first = hist.first()
        rows.add(if (now - first.t < 60_000L) Row("Global", null, null) else rowOf("Global", first, now, h))

        val r5 = rows[0].rate
        val thr = ClimbState.threshold.value
        var over = false
        if (thr > 0 && r5 != null) {
            if (r5 > thr) {
                over = true
                if (alarmArmed) { alarmArmed = false; ring(r5, thr) }
            } else if (r5 < thr * 0.9) {
                alarmArmed = true // ré-armée quand la vitesse retombe sous 90 % du seuil
            }
        }
        ClimbState.stats.value = Stats("En cours", h, rows, dPlus, dMinus, distance, over)
        notifyText(
            "5 min ${fmt(r5)} · D+ %.0f · D− %.0f · %.0f m".format(dPlus, dMinus, distance)
        )
    }

    private fun ring(rate: Double, thr: Int) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "alarm")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Montée trop rapide")
            .setContentText("%+.0f m/h sur 5 min (seuil %d)".format(rate, thr))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000L)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // sonnerie répétée jusqu'à fermeture / 30 s
        getSystemService(NotificationManager::class.java).notify(2, n)
    }

    private fun rowOf(label: String, ref: Pt, now: Long, h: Double): Row {
        val dtH = (now - ref.t) / 3_600_000.0
        val d = h - ref.h
        return Row(label, if (dtH > 0) d / dtH else null, d)
    }

    private fun fmt(v: Double?) = if (v == null) "—" else "%+.0f m/h".format(v)

    private fun notif(t: String): Notification =
        NotificationCompat.Builder(this, "climb")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Vitesse ascensionnelle")
            .setContentText(t)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun notifyText(t: String) =
        getSystemService(NotificationManager::class.java).notify(1, notif(t))

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_NOT_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::sm.isInitialized) sm.unregisterListener(this)
        if (::fused.isInitialized) fused.removeLocationUpdates(locCb)
        ClimbState.running.value = false
        super.onDestroy()
    }
}
