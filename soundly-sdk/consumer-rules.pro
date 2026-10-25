# Stable SDK 2 source surface referenced by consuming applications. Keep this
# list explicit so legacy internal models are not promoted into the host API.
-keep class com.ideacode.soundly_sdk.api.Soundly { public *; }
-keep interface com.ideacode.soundly_sdk.api.SoundlySdk { public *; }
-keep class com.ideacode.soundly_sdk.api.SoundlyConfig { public *; }
-keep @interface com.ideacode.soundly_sdk.api.ExperimentalSoundlyApi
-keep class com.ideacode.soundly_sdk.api.capability.** { public *; }
-keep class com.ideacode.soundly_sdk.api.error.SoundlyError { public *; }
-keep class com.ideacode.soundly_sdk.api.error.SoundlyError$* { public *; }
-keep class com.ideacode.soundly_sdk.api.exception.SoundlySdkException { public *; }
-keep interface com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef { public *; }
-keep class com.ideacode.soundly_sdk.api.model.audio.AudioSourceRef$* { public *; }
-keep class com.ideacode.soundly_sdk.api.model.audio.dsp.PresetOption { public *; }
-keep class com.ideacode.soundly_sdk.api.model.audio.processor.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.AudioProject { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.AudioMarker { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.AudioSourceType { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.RecordingScene { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.ProjectStatus { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.VariantType { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.AudioFormat { public *; }
-keep class com.ideacode.soundly_sdk.api.model.domain.AudioVariant { public *; }
-keep class com.ideacode.soundly_sdk.api.model.enhancement.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.export.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.importer.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.playback.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.recording.** { public *; }
-keep class com.ideacode.soundly_sdk.api.model.settings.** { public *; }
-keep interface com.ideacode.soundly_sdk.api.result.SoundlyResult { public *; }
-keep class com.ideacode.soundly_sdk.api.result.SoundlyResult$* { public *; }
-keep class com.ideacode.soundly_sdk.api.result.SoundlyResultKt { public *; }
-keep interface com.ideacode.soundly_sdk.processor.AudioProcessingService { public *; }

# Manifest and generated Room entry points are resolved by name.
-keep class com.ideacode.soundly_sdk.recording.RecordingForegroundService { *; }
-keep class com.ideacode.soundly_sdk.history.data.db.AppDatabase { *; }
-keep class com.ideacode.soundly_sdk.history.data.db.AppDatabase_Impl { *; }

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn androidx.room.RoomDatabase_Impl
