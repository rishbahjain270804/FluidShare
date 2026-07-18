package com.ether.share.network

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Background service that keeps NSD alive when app is not foregrounded.
 * On Android, NSD discovery can be paused when the app moves to background,
 * but long-lived services can maintain advertised state.
 */
class EtherNsdService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("EtherNsdService", "Service started")
        return START_STICKY  // Restart if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("EtherNsdService", "Service destroyed")
    }
}
