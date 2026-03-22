# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Verto/WebSocket models
-keep class com.telcobright.sipphone.verto.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
