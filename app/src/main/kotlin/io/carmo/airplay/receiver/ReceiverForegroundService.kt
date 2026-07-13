package io.carmo.airplay.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder

class ReceiverForegroundService : Service() {

    inner class ReceiverBinder : Binder() {
        val runtime: ReceiverRuntime get() = this@ReceiverForegroundService.runtime
    }

    private val binder = ReceiverBinder()
    private lateinit var networkMonitor: NetworkMonitor
    lateinit var runtime: ReceiverRuntime
        private set
    private val stateListener: (ReceiverState) -> Unit = { state ->
        updateNotification(state)
    }

    override fun onCreate() {
        super.onCreate()
        runtime = ReceiverRuntime(this)
        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = {
                if (runtime.state != ReceiverState.STOPPED) {
                    runtime.refreshDiscovery()
                }
            },
            onNetworkLost = {
                // DNS-SD will become unreachable; refresh happens when the network returns.
            }
        )
        networkMonitor.start()
        startAsForeground()
        runtime.addStateListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (runtime.state == ReceiverState.STOPPED) {
            val videoSize = ReceiverPreferences.selectedVideoSize(this)
            runtime.start(videoSize.width, videoSize.height, loadAudioVolume())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::networkMonitor.isInitialized) {
            networkMonitor.stop()
        }
        if (::runtime.isInitialized) {
            runtime.removeStateListener(stateListener)
            runtime.stop()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    private fun startAsForeground() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(notificationTextFor(runtime.state)))
    }

    private fun updateNotification(state: ReceiverState) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(notificationTextFor(state)))
    }

    private fun notificationTextFor(state: ReceiverState): String {
        return when (state) {
            ReceiverState.IDLE_ADVERTISING -> getString(R.string.notification_waiting)
            ReceiverState.AUDIO_ACTIVE -> getString(R.string.notification_audio_active)
            ReceiverState.VIDEO_ACTIVE -> getString(R.string.notification_video_active)
            ReceiverState.ERROR_RECOVERABLE -> getString(R.string.notification_error)
            else -> getString(R.string.notification_receiver_active)
        }
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_receiver),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun loadAudioVolume(): Float {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return 1.0f
        }
        return (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume)
            .coerceIn(0.0f, 1.0f)
    }

    companion object {
        private const val CHANNEL_ID = "receiver_active"
        private const val NOTIFICATION_ID = 1001
    }
}
