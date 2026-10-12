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
