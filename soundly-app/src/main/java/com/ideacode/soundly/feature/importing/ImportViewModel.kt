package com.ideacode.soundly.feature.importing

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ideacode.soundly.common.error.AppError
import com.ideacode.soundly.common.error.AppResult
import com.ideacode.soundly.data.sdk.EditingGateway
import com.ideacode.soundly_sdk.api.model.importer.ImportOperationStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

internal sealed interface ImportState {
    data object Select : ImportState
    data object Working : ImportState
    data class Completed(val operationId: String, val projectId: String, val variantId: String) : ImportState
    data class Interrupted(val error: AppError? = null) : ImportState
    data class Uncertain(val error: AppError) : ImportState
    data object JournalFailure : ImportState
}

internal class ImportViewModel(
    private val saved: SavedStateHandle,
    private val journal: ImportJournal,
    private val gateway: EditingGateway
) : ViewModel() {
    private val mutableState = MutableStateFlow<ImportState>(ImportState.Working)
    val state = mutableState.asStateFlow()
    val pickerLaunched: Boolean get() = saved.get<Boolean>("picker_launched") == true
    fun markPickerLaunched() { saved["picker_launched"] = true }

    init {
        val pending = journal.read()
        if (pending == null) mutableState.value = ImportState.Select else inspect(pending)
    }

    fun start(uri: Uri) {
        if (mutableState.value != ImportState.Select) return
        val id = UUID.randomUUID().toString()
        if (!journal.write(id)) {
            val existing = journal.read()
            if (existing == null) mutableState.value = ImportState.JournalFailure
            else { mutableState.value = ImportState.Working; inspect(existing) }
            return
        }
        val completion = InFlightImports.register(id)
        mutableState.value = ImportState.Working
        viewModelScope.launch {
            try {
                when (val result = gateway.importAudio(id, uri)) {
                    is AppResult.Success -> mutableState.value = ImportState.Completed(id, result.value.project.id, result.value.project.original.id)
                    is AppResult.Failure -> inspect(id, result.error)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { inspect(id, AppError("import_failed", true)) }
            finally { InFlightImports.complete(id, completion) }
        }
    }

    fun checkAgain() { journal.read()?.let { mutableState.value = ImportState.Working; inspect(it) } }

    fun chooseAgain(): Boolean {
        if (mutableState.value !is ImportState.Interrupted && mutableState.value != ImportState.JournalFailure) return false
        val id = journal.read()
        if (id != null && !journal.clear(id)) { mutableState.value = ImportState.JournalFailure; return false }
        mutableState.value = ImportState.Select
        return true
    }

    fun acknowledgeSuccess(id: String): Boolean = journal.clear(id)

    private fun inspect(id: String, cause: AppError? = null) {
        viewModelScope.launch {
            try {
                InFlightImports.await(id)
                when (val result = gateway.inspectImport(id)) {
                    is AppResult.Failure -> mutableState.value = ImportState.Uncertain(result.error)
                    is AppResult.Success -> mutableState.value = when (val status = result.value) {
                        is ImportOperationStatus.Completed -> ImportState.Completed(id, status.result.project.id, status.result.project.original.id)
                        ImportOperationStatus.NotStarted, ImportOperationStatus.Interrupted -> ImportState.Interrupted(cause)
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = ImportState.Uncertain(AppError("import_failed", true)) }
        }
    }
}

/** Prevents another restored page from treating a not-yet-reserved in-process operation as interrupted. */
private object InFlightImports {
    private val operations = mutableMapOf<String, CompletableDeferred<Unit>>()
    fun register(id: String): CompletableDeferred<Unit> = synchronized(operations) {
        CompletableDeferred<Unit>().also { operations[id] = it }
    }
    fun complete(id: String, completion: CompletableDeferred<Unit>) = synchronized(operations) {
        if (operations[id] === completion) operations.remove(id)
        completion.complete(Unit)
    }
    suspend fun await(id: String) { synchronized(operations) { operations[id] }?.await() }
}
