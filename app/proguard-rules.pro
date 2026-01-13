# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep WhisperContext and its JNI callback methods
-keep class com.voiceskip.whispercpp.whisper.WhisperContext {
    public <methods>;
    native <methods>;
    long mInstance;
    void onProgress(int);
    void onLoaded(java.lang.String);
    void onNewSegment(java.lang.String, long, long, java.lang.String);
    void onStreamComplete();
    void onError(java.lang.String);
    int readAudio(float[], int);
}

# Keep AudioProvider interface and all implementations
-keep interface com.voiceskip.whispercpp.whisper.AudioProvider {
    int readAudio(float[], int);
}
-keep class * implements com.voiceskip.whispercpp.whisper.AudioProvider {
    int readAudio(float[], int);
}
-keep class com.voiceskip.media.FileAudioProvider {
    int readAudio(float[], int);
}
-keep class com.voiceskip.media.LiveAudioProvider {
    int readAudio(float[], int);
}

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}
