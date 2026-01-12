package com.backendcam.backendcam.service.hls;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;

/**
 * Context to hold resources and state for each active stream
 * Thread-safe for concurrent access from stream thread and control thread
 */
class StreamContext {
    /**
     * Flag to signal the stream processing thread to stop gracefully.
     * Volatile ensures visibility across threads.
     */
    volatile boolean shouldStop = false;

    /**
     * The FFmpeg frame grabber reading from RTSP source.
     * Access is synchronized through thread lifecycle.
     */
    volatile FFmpegFrameGrabber grabber;

    /**
     * The FFmpeg frame recorder writing to HLS output.
     * Access is synchronized through thread lifecycle.
     */
    volatile FFmpegFrameRecorder recorder;
}
