package sh.belfry.app.rec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.belfry.app.MainActivity
import sh.belfry.app.R
import sh.belfry.core.audio.PauseBy
import sh.belfry.core.audio.RecPhase

/**
 * Keeps a recording going with the screen off: a foreground service of type `microphone` with a
 * notification (Pause / Resume, Stop). It is thin: [Recorder] holds the microphone and the rules;
 * this shows its state and passes the notification's buttons on. Started by [Recorder] while the
 * app is in front (Android does not let a microphone service start from the background), stopped
 * by it when the recording stops. Without the notification permission (Android 13+) the service
 * runs all the same; the notification is then only in the system's list of running apps.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> Recorder.pause(PauseBy.USER)
            ACTION_RESUME -> Recorder.resume(PauseBy.USER)
            ACTION_STOP -> Recorder.stop()
        }
        val phase = Recorder.state.value.meta?.phase
        if (phase == null || phase == RecPhase.STOPPED) {
            // nothing to keep alive (also: the system restarted us without a recording)
            stopForegroundNow()
            stopSelf()
            return START_NOT_STICKY
        }
        show(phase)
        if (watching == null) watching = scope.launch {
            Recorder.state.map { it.meta?.phase }.distinctUntilChanged().collect { p ->
                if (p == null || p == RecPhase.STOPPED) { stopForegroundNow(); stopSelf() } else show(p)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundNow() {
        if (foreground) try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (e: Exception) { }
        foreground = false
    }

    private fun show(phase: RecPhase) {
        val n = notification(phase)
        try {
            if (!foreground) {
                val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
                ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
                foreground = true
            } else {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            // Android 12+: not allowed from the background; Android 14+: without the microphone
            // permission. The recording goes on while the app is open, and says so.
            Recorder.noBackground()
        }
    }

    private fun action(action: String): PendingIntent =
        PendingIntent.getService(this, action.hashCode(), Intent(this, RecordingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(phase: RecPhase): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val recording = phase == RecPhase.RECORDING
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_belfry)
            .setContentTitle(if (recording) "Recording" else "Recording paused")
            .setContentText(if (recording) "Saved on this device as it records." else "Tap Resume to go on.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
        if (recording) {
            b.setUsesChronometer(true).setWhen(Recorder.chronometerBase()).setShowWhen(true)
            b.addAction(0, "Pause", action(ACTION_PAUSE))
        } else {
            b.setShowWhen(false)
            b.addAction(0, "Resume", action(ACTION_RESUME))
        }
        b.addAction(0, "Stop", action(ACTION_STOP))
        return b.build()
    }

    companion object {
        const val ACTION_PAUSE = "sh.belfry.app.rec.PAUSE"
        const val ACTION_RESUME = "sh.belfry.app.rec.RESUME"
        const val ACTION_STOP = "sh.belfry.app.rec.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7201
    }
}
