package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.error.SoundlyError
import com.ideacode.soundly_sdk.api.result.SoundlyResult

/** A maintenance operation never overlaps active audio work or admits new work. */
internal class StorageOperationGate {
    private var active = 0
    private var maintenance = false
    @Synchronized fun beginWork(): Boolean {
        if (maintenance) return false
        active++
        return true
    }
    @Synchronized fun endWork() { check(active > 0); active-- }
    @Synchronized private fun beginMaintenance(): Boolean {
        if (maintenance || active != 0) return false
        maintenance = true
        return true
    }
    @Synchronized private fun endMaintenance() { maintenance = false }
    suspend fun <T> work(block: suspend () -> SoundlyResult<T>): SoundlyResult<T> {
        if (!beginWork()) return SoundlyResult.Failure(SoundlyError.InvalidState("storageMaintenance"))
        return try { block() } finally { endWork() }
    }
    suspend fun maintenance(block: suspend () -> SoundlyResult<Unit>): SoundlyResult<Unit> {
        if (!beginMaintenance()) return SoundlyResult.Failure(SoundlyError.InvalidState("audioOperationInProgress"))
        return try { block() } finally { endMaintenance() }
    }
}
