package com.backendcam.backendcam;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

@Service
public class HLSStreamService {

    private static final Logger logger = Logger.getLogger(HLSStreamService.class.getName());
    private static final String HLS_ROOT = "./hls";
    
    // Thread pool - optimized for 100 streams
    private static final int MAX_STREAMS = 100;
    private static final int CORE_POOL_SIZE = 100;
    private static final int KEEP_ALIVE_TIME = 300;
    
    private final ExecutorService streamExecutor;
    
    private final ConcurrentHashMap<String, String> streamLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> streamTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamResources> streamResources = new ConcurrentHashMap<>();

    public HLSStreamService() {
        this.streamExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_STREAMS,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(20),
            new ThreadFactory() {
                private final java.util.concurrent.atomic.AtomicInteger counter = 
                    new java.util.concurrent.atomic.AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "HLS-" + counter.getAndIncrement());
                    t.setDaemon(false);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        ((ThreadPoolExecutor) streamExecutor).prestartAllCoreThreads();
        
        logger.info("HLS Service: core=" + CORE_POOL_SIZE + ", max=" + MAX_STREAMS);
    }

    public String startHLSStream(String rtspUrl, String streamName) {
        if (streamLinks.containsKey(streamName)) {
            logger.info("Exists: " + streamName);
            return streamLinks.get(streamName);
        }

        if (streamTasks.size() >= MAX_STREAMS) {
            throw new RuntimeException("Max capacity: " + MAX_STREAMS);
        }

        String playlistPath = "/hls/" + streamName + "/stream.m3u8";
        streamLinks.put(streamName, playlistPath);

        try {
            logger.info("Starting: " + streamName);

            Future<?> future = streamExecutor.submit(() -> {
                FFmpegFrameGrabber grabber = null;
                FFmpegFrameRecorder recorder = null;
                
                try {
                    File outputDir = new File(HLS_ROOT, streamName);
                    if (!outputDir.exists()) {
                        outputDir.mkdirs();
                    }

                    String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";
                    
                    grabber = connectRtsp(rtspUrl);
                    logger.info("Connected: " + streamName);

                    // Get resolution - no warmup frames
                    int width = grabber.getImageWidth();
                    int height = grabber.getImageHeight();
                    
                    if (width <= 0 || height <= 0) {
                        throw new RuntimeException("Invalid resolution");
                    }

                    recorder = setupRecorder(grabber, hlsOutput, outputDir, width, height);
                    recorder.start();
                    logger.info("Recording: " + streamName + " " + width + "x" + height);

                    streamResources.put(streamName, new StreamResources(grabber, recorder));

                    streamFrames(grabber, recorder, streamName);

                } catch (Exception e) {
                    logger.severe("Error: " + streamName + " - " + e.getMessage());
                    streamLinks.remove(streamName);
                } finally {
                    cleanupStream(streamName, grabber, recorder);
                }
            });

            streamTasks.put(streamName, future);
            return playlistPath;

        } catch (Exception e) {
            streamLinks.remove(streamName);
            logger.severe("Failed: " + streamName);
            throw new RuntimeException("Failed to start", e);
        }
    }

    private FFmpegFrameRecorder setupRecorder(FFmpegFrameGrabber grabber, 
                                             String hlsOutput, 
                                             File outputDir, 
                                             int width, 
                                             int height) throws Exception {
        
        // CRITICAL: Scale down aggressively - 480p max for 100 streams
        int targetWidth = width;
        int targetHeight = height;
        
        // Scale to 480p max (was 720p)
        if (height > 480) {
            double aspectRatio = (double) width / height;
            targetHeight = 480;
            targetWidth = (int) (targetHeight * aspectRatio);
            // Ensure even dimensions
            targetWidth = (targetWidth / 2) * 2;
            targetHeight = (targetHeight / 2) * 2;
            logger.info("Scale: " + width + "x" + height + " → " + targetWidth + "x" + targetHeight);
        }
        
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
            hlsOutput, targetWidth, targetHeight, 0
        );

        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("hls");
        
        // CRITICAL: Reduce FPS to 8 (was 10)
        int fps = 8;
        recorder.setFrameRate(fps);
        recorder.setGopSize(fps * 2);

        // HLS settings
        recorder.setOption("hls_time", "3"); // 3s segments (was 2s)
        recorder.setOption("hls_list_size", "2"); // Only 2 segments (was 3)
        recorder.setOption("hls_flags", "delete_segments");
        recorder.setOption("hls_segment_type", "mpegts");
        recorder.setOption("hls_allow_cache", "0");
        
        String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/s%d.ts";
        recorder.setOption("hls_segment_filename", segPath);

        // CRITICAL: Single-threaded encoding - absolute minimum CPU
        recorder.setOption("threads", "1");
        recorder.setOption("thread_type", "slice");
        
        // EXTREME performance mode - sacrifice quality for CPU
        recorder.setOption("preset", "ultrafast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("crf", "35"); // Very low quality (was 30)
        recorder.setOption("maxrate", "400k"); // Very low bitrate (was 600k)
        recorder.setOption("bufsize", "800k");
        
        // Disable ALL expensive encoding features
        recorder.setOption("sc_threshold", "0"); // No scene detection
        recorder.setOption("me_method", "dia"); // Fastest motion estimation
        recorder.setOption("subq", "0"); // No subpixel refinement
        recorder.setOption("trellis", "0"); // No trellis quantization
        recorder.setOption("aq-mode", "0"); // No adaptive quantization
        recorder.setOption("refs", "1"); // Single reference frame
        recorder.setOption("bf", "0"); // No B-frames
        recorder.setOption("g", String.valueOf(fps * 2)); // Keyframe interval
        recorder.setOption("keyint_min", String.valueOf(fps)); // Min keyframe interval
        
        // Fast flags
        recorder.setOption("fast-pskip", "1");
        recorder.setOption("no-dct-decimate", "1");
        recorder.setOption("cabac", "0"); // Disable CABAC (faster)
        recorder.setOption("partitions", "none");
        
        recorder.setOption("fflags", "+genpts");
        recorder.setOption("fps_mode", "cfr");

        return recorder;
    }

    private void streamFrames(FFmpegFrameGrabber grabber, 
                             FFmpegFrameRecorder recorder, 
                             String streamName) throws Exception {
        Frame frame;
        int count = 0;
        int skipped = 0;
        long lastTime = System.currentTimeMillis();
        
        // Target 8 FPS = 125ms per frame
        final long TARGET_INTERVAL = 125;
        
        while ((frame = grabber.grab()) != null) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;
            
            // Skip frames to maintain target FPS
            if (elapsed < TARGET_INTERVAL - 10) {
                skipped++;
                continue;
            }
            
            // Only encode video frames
            if (frame.image != null) {
                try {
                    recorder.record(frame);
                    count++;
                    lastTime = now;
                } catch (Exception e) {
                    logger.warning(streamName + ": encode error");
                    break;
                }
            }
            
            // Log less frequently - every 1000 frames
            if (count % 1000 == 0 && count > 0) {
                logger.info(streamName + ": " + count + " (skip:" + skipped + ")");
            }
        }
        logger.info(streamName + ": ended - " + count + " frames");
    }

    public void stopHLSStream(String streamName) {
        logger.info("Stopping: " + streamName);
        
        Future<?> future = streamTasks.remove(streamName);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        streamLinks.remove(streamName);
        
        StreamResources resources = streamResources.remove(streamName);
        if (resources != null) {
            cleanupStream(streamName, resources.grabber, resources.recorder);
        }

        deleteStreamFiles(streamName);
    }

    private void cleanupStream(String streamName, 
                              FFmpegFrameGrabber grabber, 
                              FFmpegFrameRecorder recorder) {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception e) {
            // Ignore
        }

        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
            }
        } catch (Exception e) {
            // Ignore
        }

        streamResources.remove(streamName);
        streamLinks.remove(streamName);
        streamTasks.remove(streamName);
    }

    private void deleteStreamFiles(String streamName) {
        File dir = new File(HLS_ROOT, streamName);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    public List<String> getActiveStreams() {
        return new ArrayList<>(streamLinks.keySet());
    }

    public int getActiveStreamCount() {
        return streamLinks.size();
    }

    public boolean isStreamReady(String streamName) {
        return streamResources.containsKey(streamName);
    }

    public String getStreamStatus(String streamName) {
        if (!streamLinks.containsKey(streamName)) return "NOT_FOUND";
        if (streamResources.containsKey(streamName)) return "RUNNING";
        Future<?> future = streamTasks.get(streamName);
        if (future != null && !future.isDone()) return "STARTING";
        return "STOPPED";
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down...");
        
        for (String streamName : new ArrayList<>(streamLinks.keySet())) {
            stopHLSStream(streamName);
        }

        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                streamExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("Shutdown complete");
    }

    private FFmpegFrameGrabber connectRtsp(String rtspUrl) throws Exception {
        FFmpegLogCallback.set();

        List<String> candidates = new ArrayList<>();
        candidates.add(rtspUrl);

        try {
            URI u = new URI(rtspUrl);
            String userInfo = u.getUserInfo();
            String base = u.getScheme() + "://" + 
                         (userInfo != null ? userInfo + "@" : "") + 
                         u.getHost() + 
                         (u.getPort() > 0 ? ":" + u.getPort() : "");
            String path = u.getPath() != null ? u.getPath() : "/";

            if (path.matches("/.+/.*")) {
                candidates.add(base + "/Streaming/Channels/101");
                candidates.add(base + "/live");
            }
        } catch (Exception ignore) {
        }

        Exception last = null;
        for (String cand : candidates) {
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(cand);
            g.setFormat("rtsp");
            g.setOption("rtsp_transport", "tcp");
            g.setOption("rtsp_flags", "prefer_tcp");
            
            // CRITICAL: Single-threaded decoding - minimum CPU
            g.setOption("threads", "1");
            g.setOption("thread_type", "slice");
            
            // Fast connection
            g.setOption("stimeout", "10000000"); // 10s
            g.setOption("timeout", "10000000");
            
            // Minimal analysis
            g.setOption("analyzeduration", "500000"); // 0.5s
            g.setOption("probesize", "500000"); // 500KB
            
            // Performance flags
            g.setOption("fflags", "nobuffer+fastseek");
            g.setOption("flags", "low_delay");
            g.setOption("max_delay", "0");
            g.setOption("allowed_media_types", "video");
            g.setOption("skip_loop_filter", "48"); // Skip loop filter
            g.setOption("skip_frame", "0"); // Don't skip frames on decode
            g.setOption("loglevel", "error");

            try {
                g.start();
                logger.info("Connected: " + cand);
                return g;
            } catch (Exception e) {
                last = e;
                try {
                    g.release();
                } catch (Exception ignore) {
                }
            }
        }
        
        throw last != null ? last : new RuntimeException("RTSP failed");
    }

    private static class StreamResources {
        final FFmpegFrameGrabber grabber;
        final FFmpegFrameRecorder recorder;

        StreamResources(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
            this.grabber = grabber;
            this.recorder = recorder;
        }
    }
}