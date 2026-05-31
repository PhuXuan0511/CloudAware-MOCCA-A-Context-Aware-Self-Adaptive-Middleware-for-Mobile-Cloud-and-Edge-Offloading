package com.thesis.middleware.context

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.thesis.middleware.MiddlewareApp
import com.thesis.middleware.R

/**
 * Long-lived foreground service that hosts the lifecycle of the two background
 * components that need to outlive any single Activity:
 *
 *  - [ContextManager.start] — drives the periodic context-collection loop.
 *  - `MapeLoop.start`       — drives the context-drift detector and the
 *                             task-channel consumer.
 *
 * Pinned to the foreground with a persistent notification because Android
 * kills background services aggressively, and we need the MAPE loop to keep
 * polling even when the user navigates away from [com.thesis.middleware.MainActivity].
 *
 * Sticky restart on process death is intentional — if the OS reclaims us we
 * want to come back automatically so the middleware stays available to any
 * app holding a binding.
 */
class ContextService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val app = application as MiddlewareApp
        app.contextManager.start()
        app.mapeLoop.start()
        Log.i(TAG, "ContextService started — ContextManager + MapeLoop running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // No per-start work; the loops are started in onCreate. Sticky restart
        // means the OS will re-deliver a null intent if we get killed.
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as MiddlewareApp
        app.mapeLoop.stop()
        app.contextManager.stop()
        Log.i(TAG, "ContextService destroyed — loops stopped")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.context_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.context_service_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.context_service_notification_title))
            .setContentText(getString(R.string.context_service_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ContextService"
        private const val CHANNEL_ID = "mocca.context.channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ContextService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ContextService::class.java))
        }
    }
}
