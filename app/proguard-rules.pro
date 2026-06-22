# simJ ProGuard/R8 Rules

# ========== JNI ==========
-keepclasseswithmembernames class * {
    native <methods>;
}

# ========== Telephony API (Reflection) ==========
-keep class android.telephony.** { *; }
-keep class com.sansim.app.esim.TelephonyApduInterface { *; }
-keep class com.sansim.app.esim.TelephonyApduInterface$Companion { *; }

# ========== eSIM Core ==========
-keep class com.sansim.app.esim.** { *; }

# ========== Data Models ==========
-keep class com.sansim.app.data.model.** { *; }

# ========== Compose ==========
-dontwarn androidx.compose.**

# ========== Kotlin Coroutines ==========
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ========== CameraX / ML Kit ==========
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ========== OkHttp ==========
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.** { *; }

# ========== JSON ==========
-keep class org.json.** { *; }

# ========== Remove Log.d/Log.v in release ==========
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
