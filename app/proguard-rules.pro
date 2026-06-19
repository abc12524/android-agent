# Android Agent ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.androidagent.data.model.** { *; }
-keep class com.androidagent.data.api.DeepSeekClient.** { *; }
