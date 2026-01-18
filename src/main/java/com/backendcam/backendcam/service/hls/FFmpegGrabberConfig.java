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
     * Configure grabber with all necessary options for RTSP streaming
     * 
     * @param grabber The FFmpegFrameGrabber to configure
     * @throws Exception if configuration fails
     */
    public void configureGrabber(FFmpegFrameGrabber grabber) throws Exception {
        grabber.setFormat("rtsp");
        grabber.setImageMode(ImageMode.COLOR);

        // Connection and timeout settings
        grabber.setOption("analyzeduration", "0");
        grabber.setOption("probesize", "32");
        grabber.setOption("max_delay", "0");
        grabber.setOption("reorder_queue_size", "0");

        // Flags configuration
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
}
