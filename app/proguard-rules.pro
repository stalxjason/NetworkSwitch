# NetworkSwitch ProGuard Rules

# ========== Android 基础 ==========
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ========== AppCompat / Material ==========
-dontwarn com.google.android.material.**

# ========== Kotlin Coroutines ==========
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ========== Shizuku SDK（反射调用其 API，保留入口） ==========
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# ========== App 组件（Manifest 引用的类不能混淆） ==========
-keep public class io.github.stalxjason.networkswitch.MainActivity { *; }
-keep public class io.github.stalxjason.networkswitch.NetworkWidgetProvider { *; }
-keep class io.github.stalxjason.networkswitch.NetworkWidgetProvider$* { *; }

# ========== 枚举（fromTelephonyType 依赖枚举实例） ==========
-keepclassmembers enum io.github.stalxjason.networkswitch.NetworkMode {
    **[] values();
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] entries();
    public static **[] entries();
    <fields>;
}

# ========== ViewBinding ==========
-keep class * implements androidx.viewbinding.ViewBinding {
    public static ** bind(android.view.View);
    public static ** inflate(android.view.LayoutInflater);
}
