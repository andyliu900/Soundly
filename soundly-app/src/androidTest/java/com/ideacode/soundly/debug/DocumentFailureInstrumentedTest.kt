package com.ideacode.soundly.debug

import android.provider.DocumentsContract
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ideacode.soundly.app.SoundlyApplication
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.feature.importing.ImportJournal
import com.ideacode.soundly.feature.importing.ImportState
import com.ideacode.soundly.feature.importing.ImportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentFailureInstrumentedTest {
    @Test fun deniedOrOfflineProviderDoesNotCreateProjectAndCanChooseAgain() {
        val app = ApplicationProvider.getApplicationContext<SoundlyApplication>()
        assertTrue(app.appContainer.initializeSdk() is AppResult.Success)
        val journal = ImportJournal(app)
        assertNull(journal.read())
        val uri = DocumentsContract.buildDocumentUri("${app.packageName}.i6.documents", "i6-audio")
        val before = runBlocking { app.appContainer.projectGateway.observeProjects().first() }.map { it.id }.toSet()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            listOf("denied", "offline").forEach { mode ->
                app.contentResolver.call(uri, "i6_set_mode", mode, null)
                val model = ImportViewModel(SavedStateHandle(), journal, app.appContainer.editingGateway)
                instrumentation.runOnMainSync { model.start(uri) }
                val deadline = System.currentTimeMillis() + 20000
                while (model.state.value is ImportState.Working && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertTrue("$mode should prompt a new selection: ${model.state.value}", model.state.value is ImportState.Interrupted)
                assertEquals(before, runBlocking { app.appContainer.projectGateway.observeProjects().first() }.map { it.id }.toSet())
                instrumentation.runOnMainSync { assertTrue(model.chooseAgain()) }
                assertNull(journal.read())
            }
        } finally {
            app.contentResolver.call(uri, "i6_set_mode", "normal", null)
            journal.read()?.let(journal::clear)
        }
    }
}
