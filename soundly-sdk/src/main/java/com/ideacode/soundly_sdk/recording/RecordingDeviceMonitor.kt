package com.ideacode.soundly_sdk.recording

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioRecordingCallback
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper

internal interface RecordingDeviceMonitor {
    fun start(
        activeDeviceId: () -> Int?,
        onInputDisconnected: () -> Unit,
        onMicrophoneUnavailable: () -> Unit
    )
    fun stop()
}

internal class AndroidRecordingDeviceMonitor(
    context: Context
) : RecordingDeviceMonitor {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceCallback: AudioDeviceCallback? = null
    private var recordingCallback: AudioRecordingCallback? = null
    private var inputDeviceIdsAtStart = emptySet<Int>()
    @Volatile
    private var monitorGeneration = 0L
    @Volatile
    private var interruptionReported = false

    override fun start(
        activeDeviceId: () -> Int?,
        onInputDisconnected: () -> Unit,
        onMicrophoneUnavailable: () -> Unit
    ) {
        stop()
        val generation = monitorGeneration
        inputDeviceIdsAtStart = audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(AudioDeviceInfo::isSource)
            .mapTo(mutableSetOf(), AudioDeviceInfo::getId)
        val newDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val removedInputIds = removedDevices
                    .filter(AudioDeviceInfo::isSource)
                    .mapTo(mutableSetOf(), AudioDeviceInfo::getId)
                if (removedInputIds.isEmpty()) return
                val routeId = activeDeviceId()
                val activeInputWasRemoved = if (routeId != null) {
                    routeId in removedInputIds
                } else {
                    // MediaRecorder exposes its routed device only on API 28+. On Android
                    // 8/8.1, interrupt conservatively when an input that existed when the
                    // recording started disappears rather than silently recording no input.
                    removedInputIds.any(inputDeviceIdsAtStart::contains)
                }
                if (activeInputWasRemoved) {
                    reportOnce(generation, onInputDisconnected)
                }
            }
        }
        val newRecordingCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : AudioRecordingCallback() {
                override fun onRecordingConfigChanged(
                    configs: MutableList<AudioRecordingConfiguration>
                ) {
                    val ownRecordingWasSilenced = configs.any { config ->
                        // Configurations owned by other apps are anonymized for ordinary
                        // applications. Our recorder explicitly uses MIC, so this filters the
                        // callback to Soundly's visible recording configuration.
                        config.clientAudioSource == MediaRecorder.AudioSource.MIC &&
                            config.isClientSilenced
                    }
                    if (ownRecordingWasSilenced) {
                        reportOnce(generation, onMicrophoneUnavailable)
                    }
                }
            }
        } else {
            null
        }
        deviceCallback = newDeviceCallback
        recordingCallback = newRecordingCallback
        audioManager.registerAudioDeviceCallback(newDeviceCallback, mainHandler)
        newRecordingCallback?.let { callback ->
            audioManager.registerAudioRecordingCallback(callback, mainHandler)
        }
    }

    override fun stop() {
        monitorGeneration += 1L
        deviceCallback?.let { callback ->
            runCatching { audioManager.unregisterAudioDeviceCallback(callback) }
        }
        recordingCallback?.let { callback ->
            runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        }
        deviceCallback = null
        recordingCallback = null
        inputDeviceIdsAtStart = emptySet()
        interruptionReported = false
    }

    private fun reportOnce(generation: Long, callback: () -> Unit) {
        if (generation != monitorGeneration || interruptionReported) return
        interruptionReported = true
        callback()
    }
}
