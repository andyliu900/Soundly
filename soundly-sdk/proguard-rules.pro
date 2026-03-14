
########################################
# 核心算法彻底混淆
########################################

# 允许重命名类
-allowaccessmodification

# 删除所有日志
-assumenosideeffects class android.util.Log {
    *;
}

# 混淆类名
-repackageclasses ''

# 移除调试信息
-dontusemixedcaseclassnames
-renamesourcefileattribute SourceFile
-keepattributes LineNumberTable

########################################
# 不暴露内部DSP实现
########################################

# 不保留 DSP 实现类名
# 默认情况下 R8 会重命名

########################################
# 保留 SDK 调用接口（如果有跨模块反射）
########################################

# 如果 SDK 通过接口调用，需要保留接口
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keepclassmembers class * {
    @androidx.room.* <fields>;
}
-dontwarn androidx.room.RoomDatabase_Impl

-keep class com.ideacode.soundly_sdk.api.** { *; }
-keep class com.ideacode.soundly_sdk.player.** { *; }
-keep class com.ideacode.soundly_sdk.history.** { *; }
-keep class com.ideacode.soundly_sdk.util.** { *; }
-keep class com.ideacode.soundly_sdk.processor.** { *; }
-keep class com.ideacode.soundly_sdk.core.audio.processor.state.** { *; }
-keep class kotlin.Metadata { *; }