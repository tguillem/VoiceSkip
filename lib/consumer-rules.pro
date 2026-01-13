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

# Keep all native methods across the library
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep the Companion object's native methods
-keep class com.voiceskip.whispercpp.whisper.WhisperContext$Companion {
    public <methods>;
    native <methods>;
}
