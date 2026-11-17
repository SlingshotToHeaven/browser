# Minimalist proguard rules for maximum APK compactness
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep AndroidX WebKit interfaces
-keep class androidx.webkit.** { *; }

# Keep Jsoup
-keep class org.jsoup.** { *; }
