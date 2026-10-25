package com.ideacode.soundly_sdk.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ideacode.soundly_sdk.R
import com.ideacode.soundly_sdk.api.Soundly
import com.ideacode.soundly_sdk.api.capability.RecordingManager
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import com.ideacode.soundly_sdk.internal.SoundlyDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps an active microphone recording in the foreground when the host app is
 * backgrounded. The host app remains responsible for requesting RECORD_AUDIO
 * and, on Android 13+, POST_NOTIFICATIONS before recording starts.
 */
internal class RecordingForegroundService : Service() {

    private val scope = CoroutineScope(
        SupervisorJob() + SoundlyDispatchers.Default.computation
    )
    private var stateJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recorder = runCatching { Soundly.sdk().recorder }.getOrNull()
        if (recorder == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildNotification(recorder.state.value))
        observe(recorder)
        when (intent?.action) {
            ACTION_PAUSE -> scope.launch { recorder.pause() }
            ACTION_RESUME -> scope.launch { recorder.resume() }
            ACTION_STOP -> scope.launch {
                recorder.stopAndSave()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun observe(recorder: RecordingManager) {
        if (stateJob != null) return
        stateJob = scope.launch {
            recorder.state.collectLatest { state ->
                when (state) {
                    is RecordingState.Recording,
                    is RecordingState.Paused,
                    is RecordingState.Interrupted,
                    is RecordingState.Finalizing -> notify(buildNotification(state))

                    else -> stopSelf()
                }
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: RecordingState): Notification {
        val paused = state is RecordingState.Paused || state is RecordingState.Interrupted
        val durationMs = when (state) {
            is RecordingState.Recording -> state.durationMs
            is RecordingState.Paused -> state.durationMs
            is RecordingState.Interrupted -> state.durationMs
            else -> 0L
        }
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (paused) R.string.soundly_recording_resume else
            R.string.soundly_recording_pause
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else
            android.R.drawable.ic_media_pause

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.soundly_recording_notification_title))
            .setContentText(formatDuration(durationMs))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(
                toggleIcon,
                getString(toggleLabel),
                serviceAction(toggleAction, if (paused) 2 else 1)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.soundly_recording_stop),
                serviceAction(ACTION_STOP, 3)
            )
            .build()
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RecordingForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.soundly_recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.soundly_recording_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val CHANNEL_ID = "soundly_recording"
        private const val NOTIFICATION_ID = 0x534F
        private const val ACTION_START = "com.ideacode.soundly.action.RECORDING_START"
        private const val ACTION_REFRESH = "com.ideacode.soundly.action.RECORDING_REFRESH"
        private const val ACTION_PAUSE = "com.ideacode.soundly.action.RECORDING_PAUSE"
        private const val ACTION_RESUME = "com.ideacode.soundly.action.RECORDING_RESUME"
        private const val ACTION_STOP = "com.ideacode.soundly.action.RECORDING_STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingForegroundService::class.java)
                    .setAction(ACTION_START)
            )
        }

        fun refresh(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RecordingForegroundService::class.java)
                        .setAction(ACTION_REFRESH)
                )
            }
        }

        fun stop(context: Context) {
            val serviceIntent = Intent(context, RecordingForegroundService::class.java)
            context.stopService(serviceIntent)
        }
    }
}
