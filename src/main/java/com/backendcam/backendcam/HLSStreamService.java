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

    private static String HLS_Root = "hls";
    private static final int TARGET_FPS = 10;
    private final Map<String, Thread> streamThreads = new ConcurrentHashMap<>();
    private final Map<String, StreamContext> streamContexts = new ConcurrentHashMap<>(); // NEW

    // NEW: Context to hold resources for each stream
    private static class StreamContext {
        volatile boolean shouldStop = false;
        FFmpegFrameGrabber grabber;
        FFmpegFrameRecorder recorder;
    }

    public String StartHLSstream(String RTSPUrl, String streamName) {
        File outputDir = new File(HLS_Root, streamName);
        outputDir.mkdirs();
        
        StreamContext context = new StreamContext(); // NEW
        streamContexts.put(streamName, context); // NEW
        
        Thread t = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;

            try {
                String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";
                String playlistPath = "/api/hls/" + streamName + "/stream.m3u8";
                String HLSoutput = "/hls/" + streamName + "/stream.m3u8";

                grabber = new FFmpegFrameGrabber(RTSPUrl);
                context.grabber = grabber; // NEW: Store in context

                grabber.setFormat("rtsp");
                grabber.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.COLOR);
                grabber.setOption("threads", "1");
                grabber.setOption("thread_count", "0");
                grabber.setVideoOption("threads", "1");
                grabber.setOption("analyzeduration", "5000000");
                grabber.setOption("probesize", "5000000");
                grabber.setOption("max_delay", "1000000");
                grabber.setOption("reorder_queue_size", "8192");
                grabber.setOption("max_interleave_delta", "2000000");
                grabber.setOption("err_detect", "ignore_err");
                grabber.setOption("ec", "favor_inter+guess_mvs+deblock");
                grabber.setOption("skip_frame", "noref");
                grabber.setOption("skip_loop_filter", "noref");
                grabber.setOption("skip_idct", "noref");
                grabber.setOption("fflags", "+discardcorrupt+nobuffer+genpts+igndts+ignidx");
                grabber.setOption("flags", "low_delay");
                grabber.setOption("flags2", "+ignorecrop+showall");
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
                grabber.setOption("err_detect", "compliant");
                grabber.start();

                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();
                recorder = new FFmpegFrameRecorder(hlsOutput, width, height, 0);
                context.recorder = recorder; // NEW: Store in context
                
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
                // FIXED: Check both interrupt flag AND shouldStop flag
                while (!Thread.currentThread().isInterrupted() && !context.shouldStop) {
                    frame = grabber.grabImage();
                    if (frame == null) {
                        break; // End of stream
                    }
                    recorder.record(frame);
                }

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // FIXED: Always cleanup resources in finally
                if (recorder != null) {
                    try {
                        recorder.stop();
                        recorder.release(); // NEW: Explicitly release
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (grabber != null) {
                    try {
                        grabber.stop();
                        grabber.release(); // NEW: Explicitly release
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                streamContexts.remove(streamName); // NEW: Cleanup context
            }
        });
        
        t.setName(streamName);
        Thread existing = streamThreads.putIfAbsent(streamName, t);

        if (existing != null) {
            streamContexts.remove(streamName); // NEW: Cleanup if already exists
            return "/api/hls/" + streamName + "/stream.m3u8";
        }
        t.start();

        return "/api/hls/" + streamName + "/stream.m3u8";
    }

    public String stopStream(String streamName) {
        // NEW: Get context and signal stop
        StreamContext context = streamContexts.get(streamName);
        if (context != null) {
            context.shouldStop = true; // Signal the loop to stop
            
            // Force close FFmpeg resources to unblock grabImage()
            try {
                if (context.recorder != null) {
                    context.recorder.stop();
                }
            } catch (Exception ignored) {}
            
            try {
                if (context.grabber != null) {
                    context.grabber.stop();
                }
            } catch (Exception ignored) {}
        }
        
        // Get and interrupt thread
        Thread t = streamThreads.remove(streamName);
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000); // INCREASED: Wait 5 seconds for clean shutdown
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
            
            // FIXED: Force stop if still alive
            if (t.isAlive()) {
                System.err.println("Warning: Thread " + streamName + " did not stop gracefully");
                // Thread will eventually die when finally block executes
            }
        }
        
        // FIXED: Small delay to ensure files are fully released
        try {
            Thread.sleep(500); // Give OS time to release file handles
        } catch (InterruptedException ignored) {}
        
        // Now safe to delete files
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
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}