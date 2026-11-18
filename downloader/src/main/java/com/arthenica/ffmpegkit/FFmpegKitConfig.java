package com.arthenica.ffmpegkit;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct JNI bridge to libffmpegkit.so for FFmpeg operations on local stream inputs.
 */
public class FFmpegKitConfig {
    private static final String TAG = "FFmpegKit";
    private static final AtomicLong SESSION_ID_COUNTER = new AtomicLong(1);
    private static boolean isNativeLoaded = false;

    public interface LogCallback {
        void onLog(long sessionId, int level, String message);
    }

    public interface StatisticsCallback {
        void onStatistics(long sessionId, int videoFrameNumber, float fps, float quality, long size, double time, double bitrate, double speed);
    }

    private static final ConcurrentHashMap<Long, LogCallback> logCallbacks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, StatisticsCallback> statsCallbacks = new ConcurrentHashMap<>();

    static {
        try {
            System.loadLibrary("avutil");
            System.loadLibrary("swresample");
            System.loadLibrary("swscale");
            System.loadLibrary("avcodec");
            System.loadLibrary("avformat");
            System.loadLibrary("avfilter");
            System.loadLibrary("avdevice");
            System.loadLibrary("ffmpegkit");
            isNativeLoaded = true;
            Log.i(TAG, "FFmpegKit native libraries loaded successfully");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load FFmpegKit native libraries: " + t.getClass().getSimpleName());
        }
    }

    public static boolean isLoaded() {
        return isNativeLoaded;
    }

    public static long newSessionId() {
        return SESSION_ID_COUNTER.getAndIncrement();
    }

    public static void registerSession(long sessionId, LogCallback logCb, StatisticsCallback statsCb) {
        if (logCb != null) logCallbacks.put(sessionId, logCb);
        if (statsCb != null) statsCallbacks.put(sessionId, statsCb);
    }

    public static void unregisterSession(long sessionId) {
        logCallbacks.remove(sessionId);
        statsCallbacks.remove(sessionId);
    }

    public static native int nativeFFmpegExecute(long sessionId, String[] arguments);
    public static native int nativeFFmpegCancel(long sessionId);
    public static native void ignoreNativeSignal(int signal);
    public static native int messagesInTransmit(long sessionId);
    public static native String registerNewNativeFFmpegPipe(String pipeName);
    public static native int setNativeEnvironmentVariable(String variableName, String variableValue);

    // Callbacks invoked by libffmpegkit.so JNI
    private static void log(long sessionId, int level, byte[] message) {
        LogCallback cb = logCallbacks.get(sessionId);
        if (cb != null && message != null && message.length > 0) {
            try {
                cb.onLog(sessionId, level, new String(message, StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }

    private static void statistics(long sessionId, int videoFrameNumber, float fps, float quality, long size, double time, double bitrate, double speed) {
        StatisticsCallback cb = statsCallbacks.get(sessionId);
        if (cb != null) {
            try {
                cb.onStatistics(sessionId, videoFrameNumber, fps, quality, size, time, bitrate, speed);
            } catch (Exception ignored) {}
        }
    }

    private static int safOpen(int id) {
        return -1;
    }

    private static int safClose(int id) {
        return 0;
    }
}
