package com.backendcam.backendcam.service.hls;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;

/**
 * Context to hold resources and state for each active stream
 */
class StreamContext {
    volatile boolean shouldStop = false;
    FFmpegFrameGrabber grabber;
    FFmpegFrameRecorder recorder;
}
