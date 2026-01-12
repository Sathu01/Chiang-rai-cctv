package com.backendcam.backendcam.service.hls;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Configures FFmpeg frame recorder for HLS output
 */
@Component
class FFmpegRecorderConfig {

    /**
     * Configure recorder with all necessary options for HLS streaming
     * 
     * @param recorder  The FFmpegFrameRecorder to configure
     * @param outputDir The output directory for HLS files
     * @param width     Video width
     * @param height    Video height
     * @param targetFps Target output FPS (e.g., 10)
     * @throws Exception if configuration fails
     */
    public void configureRecorder(FFmpegFrameRecorder recorder, File outputDir, int width, int height, int targetFps)
            throws Exception {
        
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("hls");
        recorder.setFrameRate(targetFps);           // Set output FPS
        recorder.setGopSize(targetFps * 2);         // GOP = 2 seconds worth of frames

        // ✅ HLS CONFIGURATION - Clean and working
        recorder.setOption("hls_time", "4");              // 4 second segments
        recorder.setOption("hls_list_size", "3");         // Keep last 3 segments in playlist
        recorder.setOption("hls_flags", "delete_segments"); // Auto-delete old segments
        recorder.setOption("hls_segment_type", "mpegts"); // MPEG-TS format
        recorder.setOption("hls_allow_cache", "0");       // No caching for live stream

        // Segment filename pattern
        String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/s%d.ts";
        recorder.setOption("hls_segment_filename", segPath);

        // Thread configuration
        recorder.setOption("threads", "1");
        recorder.setVideoOption("threads", "1");

        // Encoding settings for low latency
        recorder.setOption("preset", "ultrafast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("crf", "26");
        recorder.setOption("maxrate", "800k");
        recorder.setOption("bufsize", "1200k");
        recorder.setOption("sc_threshold", "0");
        recorder.setOption("g", String.valueOf(targetFps * 2));
        recorder.setOption("keyint_min", String.valueOf(targetFps));
        recorder.setOption("refs", "1");
        recorder.setOption("bf", "0");
        recorder.setOption("me_method", "dia");
        recorder.setOption("subq", "0");
        recorder.setOption("trellis", "0");
        recorder.setOption("cabac", "0");
        recorder.setOption("fast-pskip", "1");
        recorder.setOption("flags", "+low_delay");
        recorder.setOption("fflags", "+genpts");
        recorder.setOption("fps_mode", "cfr");

        recorder.start();
    }
    
    /**
     * Legacy method for backward compatibility (if needed)
     */
    public void configureRecorder(FFmpegFrameRecorder recorder, File outputDir, int width, int height)
            throws Exception {
        configureRecorder(recorder, outputDir, width, height, 10); // Default to 10 FPS
    }

    public int getTargetFps() {
        return 10;
    }
}