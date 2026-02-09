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

    private static final int TARGET_FPS = 25;
    private static final int HLS_TIME = 1;
    private static final int VIDEO_BITRATE = 2_000_000; // 2 Mbps for better quality

    /**
     * Configure recorder with all necessary options for HLS streaming
     * 
     * @param recorder  The FFmpegFrameRecorder to configure
     * @param outputDir The output directory for HLS files
     * @throws Exception if configuration fails
     */
    public void configureRecorder(FFmpegFrameRecorder recorder, File outputDir) throws Exception {
        // Normalize path to forward slashes for FFmpeg compatibility
        String normalizedPath = outputDir.getAbsolutePath().replace('\\', '/');
        
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("hls");
        
        // Video quality settings
        recorder.setVideoBitrate(VIDEO_BITRATE);
        recorder.setVideoQuality(23); // CRF value (lower = better quality, 18-28 typical)
        
        // timing & keyframes
        recorder.setFrameRate(TARGET_FPS);
        recorder.setGopSize(TARGET_FPS * HLS_TIME);
        recorder.setOption("keyint_min", String.valueOf(TARGET_FPS * HLS_TIME));
        recorder.setOption("sc_threshold", "0");
        recorder.setOption("x264-params", "no-scenecut=1:force-cfr=1");
        recorder.setOption(
            "force_key_frames",
            "expr:gte(t,n_forced*" + HLS_TIME + ")"
        );
        
        // HLS specific settings
        recorder.setOption("hls_time", String.valueOf(HLS_TIME)); //segment duration
        recorder.setOption("hls_list_size", "5"); //number of segments to keep
        recorder.setOption("hls_delete_threshold", "2"); //segment to keep before delete_segments
        recorder.setOption("hls_allow_cache", "0"); //disable cache
        recorder.setOption("hls_segment_type", "mpegts"); //segment format (mpegts = legacy, compatible) / (fmp4 = modern, ll-hls)
        recorder.setOption("hls_flags", "delete_segments+omit_endlist+temp_file+program_date_time+independent_segments+append_list");
        recorder.setOption("hls_start_number_source", "datetime"); // Use timestamp for segment numbers to avoid conflicts

        // Segment filename pattern - use normalized path
        String segPath = normalizedPath + "/s%d.ts";
        recorder.setOption("hls_segment_filename", segPath);

        // Thread configuration
        // recorder.setOption("threads", "1"); //Comment if production have multiple core/thread

        // Encoder performance / latency (use 'fast' for better quality than 'veryfast')
        recorder.setOption("preset", "fast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("bf", "0");
        recorder.setOption("refs", "2"); // More reference frames for better quality
        recorder.setOption("vsync", "cfr");

        // start
        recorder.start();
    }
}