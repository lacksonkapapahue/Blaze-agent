# Vosk wake word runs through JNA, which finds classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Services, receivers and the widget are referenced from the manifest (kept by default).
-keep class com.blaze.assistant.** extends android.app.Service { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.blaze.assistant.agent.MindReviewWorker { <init>(...); }
# v0.23.3: the Design view's JS bridge (BlazeDesign.gl / error / ready / fail) is called by name from the page.
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class com.blaze.assistant.ui.DesignBridge { *; }
# v0.26.3 blaze.db: the bundled SQLite's native code throws androidx.sqlite.SQLiteException by class name.
-keep class androidx.sqlite.SQLiteException { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }
