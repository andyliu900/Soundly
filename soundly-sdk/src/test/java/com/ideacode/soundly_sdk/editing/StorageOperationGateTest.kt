package com.ideacode.soundly_sdk.editing

import com.ideacode.soundly_sdk.api.result.SoundlyResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test

class StorageOperationGateTest {
    @Test fun `maintenance and audio work exclude each other even on cancellation`() = runBlocking {
        val gate = StorageOperationGate()
        val release = CompletableDeferred<Unit>()
        val work = async(start = CoroutineStart.UNDISPATCHED) { gate.work { release.await(); SoundlyResult.Success(Unit) } }
        assertTrue(gate.maintenance { SoundlyResult.Success(Unit) } is SoundlyResult.Failure)
        work.cancel(); work.join()
        val maintenanceRelease = CompletableDeferred<Unit>()
        val maintenance = async(start = CoroutineStart.UNDISPATCHED) { gate.maintenance { maintenanceRelease.await(); SoundlyResult.Success(Unit) } }
        assertTrue(gate.work { SoundlyResult.Success(Unit) } is SoundlyResult.Failure)
        maintenanceRelease.complete(Unit); maintenance.await()
        assertTrue(gate.work { SoundlyResult.Success(Unit) } is SoundlyResult.Success)
    }
}
