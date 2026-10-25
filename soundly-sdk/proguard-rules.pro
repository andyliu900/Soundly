
# Apply the same stable boundary while shrinking the published release AAR.
-include consumer-rules.pro

# Internal implementation and DSP classes remain eligible for shrinking and
# renaming. Optimization and whole-library repackaging are deferred to the host
# application because they can change the bytecode API of a published AAR.

# Remove Android platform logging from the minified artifact.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# Keep line numbers for diagnosable host crash reports without exposing source
# file names.
-renamesourcefileattribute SourceFile
-keepattributes LineNumberTable
-dontwarn androidx.room.RoomDatabase_Impl
