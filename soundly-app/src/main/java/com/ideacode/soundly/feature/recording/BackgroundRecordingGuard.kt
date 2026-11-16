package com.ideacode.soundly.feature.recording

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.RecordingGateway
import com.ideacode.soundly_sdk.api.model.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pauses sessions that opted out of background capture when the whole app goes away. */
internal class BackgroundRecordingGuard(private val recording: RecordingGateway) : Application.ActivityLifecycleCallbacks {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedActivities = 0
    private val mutableFailure = MutableStateFlow<AppError?>(null)
    val failure = mutableFailure.asStateFlow()
    private val background = Runnable {
        if (startedActivities == 0) scope.launch {
            try {
                val result = pauseIfBackgroundDisabled(recording)
                if (result is AppResult.Failure) mutableFailure.value = result.error
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableFailure.value = AppError("recording_failed", true) }
        }
    }
    fun clearFailure() { mutableFailure.value = null }
    override fun onActivityStarted(activity: Activity) { startedActivities++; handler.removeCallbacks(background) }
    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !activity.isChangingConfigurations) {
            handler.removeCallbacks(background); handler.postDelayed(background, 700)
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal suspend fun pauseIfBackgroundDisabled(gateway: RecordingGateway): AppResult<Unit> {
    val current = gateway.state.value as? RecordingState.Recording ?: return AppResult.Success(Unit)
    if (gateway.currentConfig.value?.enableBackgroundNotification != false)
        return AppResult.Success(Unit)
    val result = gateway.pauseSession(current.sessionId)
    val stillRecording = gateway.state.value as? RecordingState.Recording
    return if (result is AppResult.Failure && stillRecording?.sessionId != current.sessionId)
        AppResult.Success(Unit) else result
}
