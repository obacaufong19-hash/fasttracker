# FastTracker ProGuard Rules
-keep class com.fasttracker.** { *; }
-keep class androidx.webkit.** { *; }
-keepattributes JavascriptInterface
-keep public class * extends android.webkit.WebViewClient
