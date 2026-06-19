# ONNX Runtime — keep all ORT classes (minification would break JNI bindings)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Room — keep generated DAO implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# DataStore
-keep class androidx.datastore.** { *; }
