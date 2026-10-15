# The app finds the YouTube store by class name (StoreSources.YOUTUBE_CLASS) and reads the Kotlin object's INSTANCE.
-keep class com.blaze.assistant.mediaapps.youtube.YouTubeSource { public static final ** INSTANCE; *; }
