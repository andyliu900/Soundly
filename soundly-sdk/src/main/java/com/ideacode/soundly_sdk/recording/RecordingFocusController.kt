package com.ideacode.soundly_sdk.recording

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.ideacode.soundly_sdk.api.model.recording.RecordingInterruptionReason

internal interface RecordingFocusController {
    fun request(onInterrupted: (RecordingInterruptionReason) -> Unit): Boolean
    fun abandon()
}

internal class AndroidRecordingFocusController(
    context: Context
) : RecordingFocusController {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null

    override fun request(
        onInterrupted: (RecordingInterruptionReason) -> Unit
    ): Boolean {
        abandon()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    onInterrupted(currentInterruptionReason())
                }
            }
            .build()
        request = focusRequest
        return audioManager.requestAudioFocus(focusRequest) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        request?.let(audioManager::abandonAudioFocusRequest)
        request = null
    }

    private fun currentInterruptionReason(): RecordingInterruptionReason =
        if (audioManager.mode == AudioManager.MODE_IN_CALL ||
            audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
        ) {
            RecordingInterruptionReason.PHONE_CALL
        } else {
            RecordingInterruptionReason.AUDIO_FOCUS_LOST
        }
}
