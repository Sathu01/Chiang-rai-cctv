package com.backendcam.backendcam.service.hls;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FrameGrabber.ImageMode;
import org.springframework.stereotype.Component;

/**
 * Configures FFmpeg frame grabber for RTSP stream input
 */
@Component
 public class FFmpegGrabberConfig {

    /**
     * Configure grabber for LIVE STREAMING (low latency, optimized for HLS output)
     * Use this for real-time streaming where latency matters more than frame quality
     * 
     * @param grabber The FFmpegFrameGrabber to configure
     * @throws Exception if configuration fails
     */
    public void configureGrabber(FFmpegFrameGrabber grabber) throws Exception {
        grabber.setFormat("rtsp");
        grabber.setImageMode(ImageMode.COLOR);

        // Low latency settings for live streaming
        grabber.setOption("analyzeduration", "0");
        grabber.setOption("probesize", "32");
        grabber.setOption("max_delay", "0");
        grabber.setOption("reorder_queue_size", "0");

        // Flags configuration for minimum latency
        grabber.setOption("fflags", "+nobuffer+discardcorrupt+igndts+genpts");
        grabber.setOption("flags", "low_delay");

        // RTSP specific settings
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");

        grabber.setOption("stimeout", "5000000"); //socket timeout 5 secs
        grabber.setOption("rw_timeout", "5000000"); //read write timeout 5 secs

        grabber.setOption("allowed_media_types", "video");
        grabber.setOption("use_wallclock_as_timestamps", "1");

        grabber.start();
    }

    /**
     * Configure grabber for MOTION DETECTION (proper frame quality for image saving)
     * Use this when you need to save/upload frames with correct colors
     * Slightly higher latency but frames decode properly
     * 
     * @param grabber The FFmpegFrameGrabber to configure
     * @throws Exception if configuration fails
     */
    public void configureGrabberForMotionDetection(FFmpegFrameGrabber grabber) throws Exception {
        grabber.setFormat("rtsp");
        grabber.setImageMode(ImageMode.COLOR);
        
        // Set pixel format to ensure proper color conversion
        grabber.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGR24);

        // Sufficient values for proper codec detection and frame quality
        grabber.setOption("analyzeduration", "2000000");  // 2 seconds to analyze stream format
        grabber.setOption("probesize", "5000000");        // 5MB to detect codec parameters
        grabber.setOption("max_delay", "500000");         // 500ms max delay
        
        // Request keyframe at start for proper H.264 decoding
        grabber.setOption("skip_frame", "nokey");         // Don't skip keyframes
        grabber.setOption("skip_loop_filter", "all");     // Skip loop filter for speed

        // Flags - keep frames intact for proper decoding
        grabber.setOption("fflags", "+genpts");
        grabber.setOption("flags", "low_delay");

        // RTSP specific settings
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");

        grabber.setOption("stimeout", "5000000"); //socket timeout 5 secs
        grabber.setOption("rw_timeout", "5000000"); //read write timeout 5 secs

        grabber.setOption("allowed_media_types", "video");
        grabber.setOption("use_wallclock_as_timestamps", "1");

        grabber.start();
    }
}
