package com.backendcam.backendcam.service.hls;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Component;

/**
 * Configures FFmpeg frame grabber for RTSP stream input
 */
@Component
class FFmpegGrabberConfig {

    /**
     * Configure grabber with all necessary options for RTSP streaming
     * 
     * @param grabber The FFmpegFrameGrabber to configure
     * @param rtspUrl The RTSP URL to connect to
     * @throws Exception if configuration fails
     */
    public void configureGrabber(FFmpegFrameGrabber grabber, String rtspUrl) throws Exception {
        grabber.setFormat("rtsp");
        grabber.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.COLOR);

        // Thread configuration
        grabber.setOption("threads", "1");
        grabber.setOption("thread_count", "0");
        // TODO: REDUNDANT - Line below duplicates thread setting above
        // grabber.setVideoOption("threads", "1");

        // Connection and timeout settings
        grabber.setOption("analyzeduration", "5000000");
        grabber.setOption("probesize", "5000000");
        grabber.setOption("max_delay", "1000000");
        grabber.setOption("reorder_queue_size", "8192");
        grabber.setOption("max_interleave_delta", "2000000");

        // Error handling - first setting
        grabber.setOption("err_detect", "ignore_err");
        grabber.setOption("ec", "favor_inter+guess_mvs+deblock");

        // Frame skipping options
        grabber.setOption("skip_frame", "noref");
        grabber.setOption("skip_loop_filter", "noref");
        grabber.setOption("skip_idct", "noref");

        // Flags configuration
        grabber.setOption("fflags", "+discardcorrupt+nobuffer+genpts+igndts+ignidx");
        grabber.setOption("flags", "low_delay");
        grabber.setOption("flags2", "+ignorecrop+showall");

        // RTSP specific settings
        grabber.setOption("rtsp_transport", "tcp");
        grabber.setOption("rtsp_flags", "prefer_tcp");
        grabber.setOption("stimeout", "60000000");
        grabber.setOption("timeout", "60000000");
        grabber.setOption("buffer_size", "8192000");
        grabber.setOption("allowed_media_types", "video");
        grabber.setOption("max_error_rate", "1.0");
        grabber.setOption("rw_timeout", "60000000");
        grabber.setOption("use_wallclock_as_timestamps", "1");
        grabber.setOption("strict", "-2");

        // TODO: REDUNDANT - This overwrites the err_detect setting from line above
        // grabber.setOption("err_detect", "compliant");

        grabber.start();
    }
}
