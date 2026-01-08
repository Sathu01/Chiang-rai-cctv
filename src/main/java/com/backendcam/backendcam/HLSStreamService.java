package com.backendcam.backendcam;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.stereotype.Service;

@Service
public class HLSStreamService {

    private static String HLS_Root = "/hls";
    private static final int TARGET_FPS = 25;
    private final Map<String, Thread> streamThreads = new ConcurrentHashMap<>();

    public String StartHLSstream(String RTSPUrl, String streamName) {
        File outputDir = new File(HLS_Root, streamName);
        outputDir.mkdirs();
        Thread t = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;

            try {

                String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";
                String playlistPath = "/api/hls/" + streamName + "/stream.m3u8";
                String HLSoutput = "/hls/" + streamName + "/stream.m3u8";

                grabber = new FFmpegFrameGrabber(RTSPUrl);

                grabber.setFormat("rtsp");
                grabber.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.COLOR);
                // Threading
                grabber.setOption("threads", "1");
                grabber.setOption("thread_count", "0");
                grabber.setVideoOption("threads", "1");

                // ✅ CRITICAL: Larger buffers for sustained 25fps reading
                grabber.setOption("analyzeduration", "5000000");    // 5s
                grabber.setOption("probesize", "5000000");          // 5MB
                grabber.setOption("max_delay", "1000000");          // 1s

                // ✅ CRITICAL: Large reorder queue for 25fps with packet loss
                grabber.setOption("reorder_queue_size", "8192");    // 8K packets
                grabber.setOption("max_interleave_delta", "2000000"); // 2s gaps tolerated

                // Error handling - Maximum tolerance
                grabber.setOption("err_detect", "ignore_err");
                grabber.setOption("ec", "favor_inter+guess_mvs+deblock");

                // Frame skipping at decoder level (lightweight)
                grabber.setOption("skip_frame", "noref");
                grabber.setOption("skip_loop_filter", "noref");
                grabber.setOption("skip_idct", "noref");

                // Timestamp handling
                grabber.setOption("fflags", "+discardcorrupt+nobuffer+genpts+igndts+ignidx");
                grabber.setOption("flags", "low_delay");
                grabber.setOption("flags2", "+ignorecrop+showall");

                // RTSP settings
                grabber.setOption("rtsp_transport", "tcp");
                grabber.setOption("rtsp_flags", "prefer_tcp");
                grabber.setOption("stimeout", "60000000");          // 60s - patient with slow networks
                grabber.setOption("timeout", "60000000");

                // ✅ CRITICAL: Large buffer for 25fps sustained reading
                // 25fps × 50KB/frame × 6.4 buffers = ~8MB needed
                grabber.setOption("buffer_size", "8192000");        // 8MB
                grabber.setOption("allowed_media_types", "video");

                // Error tolerance
                grabber.setOption("max_error_rate", "1.0");         // 100% tolerance
                grabber.setOption("rw_timeout", "60000000");        // 60s
                grabber.setOption("use_wallclock_as_timestamps", "1");

                // H.264/HEVC specific
                grabber.setOption("strict", "-2");
                grabber.setOption("err_detect", "compliant");
                grabber.start();

                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                recorder = new FFmpegFrameRecorder(hlsOutput, width, height, 0);
                recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                recorder.setFormat("hls");

                recorder.setFrameRate(TARGET_FPS);
                recorder.setGopSize(TARGET_FPS * 2);

                recorder.setOption("hls_time", "4");
                recorder.setOption("hls_list_size", "3");
                recorder.setOption("hls_flags", "delete_segments");
                recorder.setOption("hls_segment_type", "mpegts");
                recorder.setOption("hls_allow_cache", "0");

                String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/s%d.ts";
                recorder.setOption("hls_segment_filename", segPath);

                recorder.setOption("threads", "1");
                recorder.setVideoOption("threads", "1");

                recorder.setOption("preset", "ultrafast");
                recorder.setOption("tune", "zerolatency");
                recorder.setOption("crf", "26");
                recorder.setOption("maxrate", "800k");
                recorder.setOption("bufsize", "1200k");

                recorder.setOption("sc_threshold", "0");
                recorder.setOption("g", String.valueOf(TARGET_FPS * 2));
                recorder.setOption("keyint_min", String.valueOf(TARGET_FPS));
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
                Frame frame;

                while ((frame = grabber.grabImage()) != null) {
                    recorder.record(frame);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    recorder.stop();
                } catch (Exception ignored) {
                }
                try {
                    grabber.stop();
                } catch (Exception ignored) {
                }
            }
        }
        );
        t.setName(streamName);
        Thread existing = streamThreads.putIfAbsent(streamName, t);

        if (existing != null) {

            return "/api/hls/" + streamName + "/stream.m3u8";
        }
        t.start();

        return "/api/hls/" + streamName + "/stream.m3u8";
    }

    public String stopStream(String streamName) {
        Thread t = streamThreads.remove(streamName);
        if (t != null) {
            t.interrupt(); // ask thread to stop
            try {
            t.join(3000);
        } catch (InterruptedException ignored) {}

        }
        // delete files AFTER stopping
        deleteStreamDirectory(streamName);
        return "Stream stopped and files deleted.";
    }

    private void deleteStreamDirectory(String streamName) {
        Path streamDir = Paths.get(HLS_Root, streamName);

        if (!Files.exists(streamDir)) {
            return;
        }

        try {
            Files.walk(streamDir)
                    .sorted(Comparator.reverseOrder()) // delete files first
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
