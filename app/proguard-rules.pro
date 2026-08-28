# ProGuard / R8 aggressive optimization rules for Pdocxles
-allowaccessmodification
-repackageclasses ''

# Strip assertions and unnecessary logging
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
    public static void checkNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# Keep XML parsers, data models and viewholders
-keepclassmembers class com.pdocxles.app.model.** { *; }
-keepclassmembers class com.pdocxles.app.engine.** { *; }
-keepclassmembers class * extends androidx.recyclerview.widget.RecyclerView$ViewHolder {
    public <init>(...);
}
