package com.ideacode.soundly_sdk.recording

import android.content.Context

/** Injectable boundary around the Android foreground-service lifecycle. */
internal interface RecordingForegroundController {
    fun start()
    fun refresh()
    fun stop()
}

internal class AndroidRecordingForegroundController(
    private val context: Context
) : RecordingForegroundController {
    override fun start() = RecordingForegroundService.start(context)
    override fun refresh() = RecordingForegroundService.refresh(context)
    override fun stop() = RecordingForegroundService.stop(context)
}
