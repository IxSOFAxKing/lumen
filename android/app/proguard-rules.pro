# Lumen TV
-keep class app.lumen.tv.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
