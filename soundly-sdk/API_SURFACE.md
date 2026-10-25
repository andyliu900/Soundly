# Soundly SDK 2 source API

This file defines the supported host-app boundary after foundation hardening.
Anything not listed here is an implementation detail and may change without a
host migration.

## Dependency and platform contract

```kotlin
dependencies {
    implementation("com.ideacode:soundly-sdk:2.0.0")
}
```

- Android API 26 or newer.
- Compile/target SDK 35 is used by the release candidate.
- Kotlin coroutines are a transitive API dependency because capabilities expose
  `Flow` and `StateFlow`.
- The AAR contributes microphone, foreground-service and notification
  permissions. The host still owns runtime permission requests and the user
  explanation shown before requesting them.
- All project audio and metadata stay on device unless the host explicitly
  invokes export or sharing.

## Entry points

- `Soundly.initialize(context, config): SoundlyResult<Unit>` initializes once.
- `Soundly.sdkResult(): SoundlyResult<SoundlySdk>` is the safe accessor when
  initialization is not guaranteed.
- `Soundly.sdk(): SoundlySdk` is a convenience accessor for code that has
  already handled initialization. It throws `SoundlySdkException` carrying the
  typed `NotInitialized` error when that contract is violated.
- `Soundly.isInitialized()` reports initialization state.

Initialize once from `Application.onCreate` with an application context:

```kotlin
when (val result = Soundly.initialize(this, SoundlyConfig(enableDebugLogging = false))) {
    is SoundlyResult.Success -> Unit
    is SoundlyResult.Failure -> reportSdkInitialization(result.error.code)
}
```

`SdkContainer`, concrete Android implementations, Room types and `core.*`
types are not source API. `Soundly.container()` has been removed.

## Stable capabilities

| `SoundlySdk` property | Contract |
|---|---|
| `processor` | `AudioProcessingService` |
| `projects` | `AudioProjectRepository` |
| `importer` | `AudioImporter` |
| `metadata` | `AudioMetadataReader` |
| `recorder` | `RecordingManager` — recording lifecycle, live meter/waveform, observable and editable markers, interruption/recovery, persisted static waveform |
| `playback` | `AudioPlaybackController` |
| `enhancer` | `AudioEnhancer` |
| `trimmer` | `AudioTrimmer` |
| `exporter` | `AudioExporter` |
| `storage` | `StorageManager` |
| `settings` | `SoundlySettingsRepository` |

`AudioProjectRepository.getVariants(projectId)` returns the original version
first, followed by derived versions in newest-first order. Versions retain
their `sourceVariantId`, preset and creation time so a host can implement
original/enhanced A/B selection without inventing its own lineage model.

`ExportRequest.quality` supports `AUTOMATIC`, exact-byte
`PRESERVE_SOURCE`, and `PCM_16_BIT` WAV output. `ExportResult.quality` always
reports the concrete strategy actually used and never returns `AUTOMATIC`.

The removed legacy `player` and `history` properties are replaced by
`playback` and `projects` respectively.

## Recording lifecycle

The host requests `RECORD_AUDIO` before calling the SDK. On Android 13 and
newer it should also request `POST_NOTIFICATIONS` before enabling background
recording. A normal lifecycle is:

```kotlin
val recorder = Soundly.sdk().recorder
recorder.prepareUsingDefaults(title = "Interview")
recorder.start()
recorder.addMarker("Question 1")
recorder.pause()
recorder.resume()
val result = recorder.stopAndSave()
```

Observe `state`, `meter`, `waveform` and `markers` while the recording screen
is active. `pause()` freezes duration, byte count and waveform output. A
completed recording persists a static waveform. `discard()` removes temporary
recording state. Interrupted or process-abandoned sessions are exposed through
`getRecoverableRecordings()` and recovered with `recover(sessionId)`.

The host must display `RecordingState.Interrupted.reason` rather than guessing
from platform exceptions. Supported typed reasons are audio-focus loss, phone
call, input-device disconnect, unavailable microphone and insufficient storage.

## Enhancement tasks and versions

Enhancement is an asynchronous task with stable task, project and output
variant IDs:

```kotlin
val enhancer = Soundly.sdk().enhancer
val task = enhancer.enhance(EnhancementRequest(projectId, sourceVariantId, preset))
// Collect enhancer.observe(taskId), then await the terminal result.
```

The running stages are `PREPARING`, `LOADING`, `ANALYZING`, `RECOMMENDING`,
`PROCESSING`, `WRITING` and `SAVING`. They terminate in `COMPLETED`, `CANCELLED`
or `FAILED`. Progress is monotonic. `cancel(taskId)` waits for temporary-file
cleanup. `retry(taskId)` is valid only for a retryable failed/cancelled task and
creates a new task and variant; it never overwrites the previous attempt.

Use `projects.getVariants(projectId)` to drive original/enhanced A/B playback.
Call `playback.switchSourceKeepingPosition(...)` when the user changes the
selected version so the listening position is preserved. Only one SDK player
is active per `SoundlySdk` instance.

## Trim, export and share

`trimmer.previewTrim(...)` validates a non-destructive head/tail range and maps
markers to the retained timeline without writing a file. `applyTrim(...)`
creates a derived variant and leaves the source unchanged.

Export supports these truthful quality policies:

- `PRESERVE_SOURCE`: exact byte copy and matching output format only.
- `PCM_16_BIT`: decoded sample rate/channel count written as PCM16 WAV.
- `AUTOMATIC`: exact copy for a matching format, otherwise PCM16 for WAV.

The SDK rejects unsupported transcodes instead of changing only the file
extension. Destinations are Music/Soundly, Download/Soundly, or a URI obtained
from `createDocumentIntent(...)`. `prepareShare(...)` creates a read-only
content URI and `ACTION_SEND` intent; the host launches the chooser.

## Storage, settings and lifecycle

`storage.snapshot()` separates original, variant, cache, recovery and auxiliary
bytes. `clearCache()` does not remove projects. `deleteAllLocalData()` is a
destructive privacy action and must be protected by host confirmation.

Settings are persisted and observable through `settings.observe()`. They cover
the default scene, recording quality/format, export format, naming strategy and
background recording preference. `recorder.prepareUsingDefaults()` resolves a
fresh settings snapshot for each new session.

There is no global SDK shutdown operation in 2.0. Finish or discard an active
recording and call `playback.release()` when playback is no longer needed. The
SDK owns its database and internal coroutine scopes for the application
process lifetime.

## Result and error contract

All capability operations that can fail return `SoundlyResult`. UI code should
branch on the concrete `SoundlyError` type or `error.code`, then provide its own
localized copy. `SoundlyError.toString()` is safe and never includes the
underlying platform exception text. Platform causes are retained internally for
diagnostics only.

Important stable codes include:

- `sdk_not_initialized`, `sdk_initialization_failed`
- `permission_denied`, `microphone_unavailable`, `recording_interrupted`
- `insufficient_storage`, `file_not_found`, `file_operation_failed`
- `unsupported_format`, `invalid_audio_file`, `decode_failed`
- `processing_cancelled`, `processing_failed`, `playback_failed`
- `import_failed`, `recording_failed`, `trim_failed`, `export_failed`,
  `share_failed`
- `database_failed`, `settings_persistence_failed`, `invalid_request`,
  `invalid_state`, `project_not_found`, `variant_in_use`,
  `destination_conflict`, `unknown`

## Threading contract

SDK-owned blocking work uses one internal `SoundlyDispatchers` dependency:
`io` for file/database/media I/O and `computation` for DSP and monitoring.
Concrete implementations receive it from `SdkContainer`; tests can replace it
without changing public API.

Suspend functions may be called from the main thread; SDK-owned blocking I/O
and DSP are dispatched internally. Non-suspending playback controls are safe
facade calls but their observable outcome is always `playback.state`.

## Migration from 1.x

- Replace `Soundly.init(context)` with `Soundly.initialize(context)` and handle
  `SoundlyResult`.
- Replace direct `SdkContainer` access with `Soundly.sdkResult()` or
  `Soundly.sdk()`.
- Replace legacy `history` with `projects`, and legacy `player` with `playback`.
- Treat every fallible operation as `SoundlyResult`; do not display exception
  messages as UI copy.
- Enhancement now returns a task first and a project/variant result at its
  terminal state. Integrations that require the old suspending behavior can use
  the compatibility overload documented on `AudioEnhancer`.
- Database schema 2 and 3 migrate to schema 4. Schema 1 was never a supported
  SDK 2 upgrade source and uses the declared destructive fallback.
