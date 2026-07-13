# Protect JNI-called methods in RaopServer from any future minification.
-keep class io.carmo.airplay.receiver.RaopServer {
    void onRecvVideoData(java.nio.ByteBuffer, int, long, int, long, long);
    void onRecvAudioData(java.nio.ByteBuffer, int, long, long);
    void onSetAudioVolume(float);
    void onAudioFlush();
    void onStreamStopped();
    int getVideoWidth();
    int getVideoHeight();
}
-keep class io.carmo.airplay.receiver.model.NativeMemory {
    void free(long);
}
