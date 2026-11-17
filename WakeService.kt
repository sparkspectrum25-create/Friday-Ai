package com.friday.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import java.io.File

/** Listens for "Hey Friday" using Porcupine. Needs: PICOVOICE_ACCESS_KEY at build time and assets/hey_friday.ppn. */
class WakeService : Service() {
    private var porcupine: PorcupineManager? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake", "Friday listening", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, "wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Friday is listening")
            .setContentText("Say \"Hey Friday\"")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        startPorcupine()
    }

    private fun startPorcupine() {
        try {
            val ppn = File(filesDir, "hey_friday.ppn")
            assets.open("hey_friday.ppn").use { i -> ppn.outputStream().use { o -> i.copyTo(o) } }
            porcupine = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_KEY)
                .setKeywordPath(ppn.absolutePath)
                .setSensitivity(0.7f)
                .build(applicationContext, PorcupineManagerCallback { onWake() })
            porcupine?.start()
        } catch (e: Exception) {
            Log.e("WakeService", "Porcupine failed: ${e.message}")
            stopSelf()
        }
    }

    private fun onWake() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("wake", true)
        )
    }

    override fun onDestroy() {
        try { porcupine?.stop(); porcupine?.delete() } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
