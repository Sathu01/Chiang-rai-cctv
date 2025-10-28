package com.backendcam.backendcam;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * HLSStreamService with Thread Pool
 *
 * Improved version that uses ExecutorService for managing multiple concurrent streams
 * - Supports up to 100 concurrent RTSP to HLS conversions
 * - One thread per stream for optimal parallelization
 * - Proper resource management and cleanup
 */
@Service
public class HLSStreamService {

    private static final Logger logger = Logger.getLogger(HLSStreamService.class.getName());
    private static final String HLS_ROOT = "./hls";
    
    // Thread pool configuration
    private static final int MAX_STREAMS = 100;
    private static final int CORE_POOL_SIZE = 20; // Keep 20 threads always alive
    private static final int KEEP_ALIVE_TIME = 60; // seconds
    
    // Thread pool for managing streams
    private final ExecutorService streamExecutor;
    
    // Track active streams and their futures
    private final ConcurrentHashMap<String, String> streamLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> streamTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamResources> streamResources = new ConcurrentHashMap<>();

    public HLSStreamService() {
        // Create thread pool with capacity for 100 streams
        this.streamExecutor = new ThreadPoolExecutor(
            CORE_POOL_SIZE,
            MAX_STREAMS,
            KEEP_ALIVE_TIME,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            new ThreadFactory() {
                private int counter = 0;
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "HLS-Stream-" + counter++);
                    t.setDaemon(false); // Don't make daemon so streams complete properly
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // If pool is full, run in caller thread
        );
        
        logger.info("HLS Stream Service initialized with thread pool (max: " + MAX_STREAMS + " streams)");
    }

    /**
     * Start HLS stream from RTSP with thread pool management
     */
    public String startHLSStream(String rtspUrl, String streamName) {
        // Check if stream already exists
        if (streamLinks.containsKey(streamName)) {
            logger.info("Stream already exists: " + streamName);
            return streamLinks.get(streamName);
        }

        // Check if we've reached maximum capacity
        if (streamTasks.size() >= MAX_STREAMS) {
            throw new RuntimeException("Maximum stream capacity reached (" + MAX_STREAMS + " streams)");
        }

        try {
            logger.info("Starting HLS stream: " + rtspUrl + " as " + streamName);

            // Create output directory
            File outputDir = new File(HLS_ROOT + "/" + streamName);
            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                logger.info("Created folder: " + outputDir.getAbsolutePath() + " - Success: " + created);
            }

            String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";
            String playlistPath = "/hls/" + streamName + "/stream.m3u8";
            
            // Store the playlist path immediately
            streamLinks.put(streamName, playlistPath);

            // Submit stream task to thread pool
            Future<?> future = streamExecutor.submit(() -> {
                FFmpegFrameGrabber grabber = null;
                FFmpegFrameRecorder recorder = null;
                
                try {
                    // Step 1: Connect to RTSP
                    grabber = tryStartRtspWithFallback(rtspUrl);
                    logger.info("Grabber started for " + streamName + ". Resolution: "
                            + grabber.getImageWidth() + "x" + grabber.getImageHeight());

                    // Step 2: Warm-up to determine resolution
                    int warmupMax = 5;
                    int warmCount = 0;
                    Frame firstVideoFrame = null;
                    while (warmCount < warmupMax) {
                        Frame f = grabber.grab();
                        if (f == null) break;
                        if (f.image != null) {
                            firstVideoFrame = f;
                            break;
                        }
                        warmCount++;
                    }

                    int width = grabber.getImageWidth();
                    int height = grabber.getImageHeight();
                    if ((width <= 0 || height <= 0) && firstVideoFrame != null) {
                        width = firstVideoFrame.imageWidth;
                        height = firstVideoFrame.imageHeight;
                    }
                    if (width <= 0 || height <= 0) {
                        throw new RuntimeException("Could not determine video resolution from RTSP stream");
                    }

                    // Step 3: Setup recorder
                    recorder = setupRecorder(grabber, hlsOutput, outputDir, width, height);
                    recorder.start();
                    logger.info("Recorder started for " + streamName + " at " + hlsOutput + 
                               " with size " + width + "x" + height);

                    // Store resources for cleanup
                    streamResources.put(streamName, new StreamResources(grabber, recorder));

                    // Step 4: Stream frames
                    streamFrames(grabber, recorder, streamName);

                } catch (Exception e) {
                    logger.severe("Error in stream " + streamName + ": " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    // Cleanup resources
                    cleanupStream(streamName, grabber, recorder);
                }
            });

            // Store the future for tracking
            streamTasks.put(streamName, future);

            return playlistPath;

        } catch (Exception e) {
            // Cleanup on failure
            streamLinks.remove(streamName);
            logger.severe("Failed to start HLS stream: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to start HLS stream", e);
        }
    }

    /**
     * Setup FFmpegFrameRecorder with optimal settings
     */
    private FFmpegFrameRecorder setupRecorder(FFmpegFrameGrabber grabber, String hlsOutput, 
                                             File outputDir, int width, int height) throws Exception {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
            hlsOutput,
            width,
            height,
            Math.max(0, grabber.getAudioChannels())
        );

        // Video settings
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("hls");
        int fps = Math.max(15, grabber.getFrameRate() > 0 ? (int) Math.round(grabber.getFrameRate()) : 25);
        recorder.setFrameRate(fps);
        recorder.setGopSize(2 * fps);

        // Audio settings
        if (grabber.getAudioChannels() > 0) {
            recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
            recorder.setSampleRate(grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 8000);
            recorder.setAudioBitrate(128000);
        }

        // HLS options
        recorder.setOption("hls_time", "2");
        recorder.setOption("hls_list_size", "3");
        recorder.setOption("hls_flags", "delete_segments+independent_segments+program_date_time");
        recorder.setOption("hls_segment_type", "mpegts");
        recorder.setOption("hls_allow_cache", "0");
        String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/seg%05d.ts";
        recorder.setOption("hls_segment_filename", segPath);
        recorder.setOption("reset_timestamps", "1");

        // Encoding optimization
        recorder.setOption("preset", "ultrafast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("crf", "23");
        recorder.setOption("maxrate", "2M");
        recorder.setOption("bufsize", "4M");
        recorder.setOption("fflags", "+genpts+igndts");
        recorder.setOption("avoid_negative_ts", "make_zero");
        recorder.setOption("fps_mode", "cfr");

        return recorder;
    }

    /**
     * Stream frames from grabber to recorder
     */
    private void streamFrames(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder, String streamName) throws Exception {
        Frame frame;
        int count = 0;
        while ((frame = grabber.grab()) != null) {
            recorder.record(frame);
            count++;
            if (count % 100 == 0) {
                logger.info("Stream " + streamName + " processed " + count + " frames");
            }
        }
        logger.info("Stream " + streamName + " ended normally after " + count + " frames");
    }

    /**
     * Stop a specific HLS stream
     */
    public void stopHLSStream(String streamName) {
        logger.info("Stopping stream: " + streamName);
        
        // Cancel the task if it's still running
        Future<?> future = streamTasks.remove(streamName);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        // Remove from tracking
        streamLinks.remove(streamName);
        
        // Cleanup resources
        StreamResources resources = streamResources.remove(streamName);
        if (resources != null) {
            cleanupStream(streamName, resources.grabber, resources.recorder);
        }

        // Delete output files
        deleteStreamFiles(streamName);
    }

    /**
     * Cleanup stream resources
     */
    private void cleanupStream(String streamName, FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception e) {
            logger.warning("Error stopping recorder for " + streamName + ": " + e.getMessage());
        }

        try {
            if (grabber != null) {
                grabber.stop();
                grabber.release();
            }
        } catch (Exception e) {
            logger.warning("Error stopping grabber for " + streamName + ": " + e.getMessage());
        }

        streamResources.remove(streamName);
        streamLinks.remove(streamName);
        streamTasks.remove(streamName);
    }

    /**
     * Delete HLS output files for a stream
     */
    private void deleteStreamFiles(String streamName) {
        File dir = new File(HLS_ROOT + "/" + streamName);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.delete()) {
                        logger.warning("Failed to delete file: " + f.getAbsolutePath());
                    }
                }
            }
            if (!dir.delete()) {
                logger.warning("Failed to delete directory: " + dir.getAbsolutePath());
            }
        }
    }

    /**
     * Get list of active streams
     */
    public List<String> getActiveStreams() {
        return new ArrayList<>(streamLinks.keySet());
    }

    /**
     * Get number of active streams
     */
    public int getActiveStreamCount() {
        return streamLinks.size();
    }

    /**
     * Shutdown hook - cleanup all streams when service stops
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down HLS Stream Service...");
        
        // Stop all active streams
        for (String streamName : new ArrayList<>(streamLinks.keySet())) {
            stopHLSStream(streamName);
        }

        // Shutdown thread pool
        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                streamExecutor.shutdownNow();
                if (!streamExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warning("Thread pool did not terminate");
                }
            }
        } catch (InterruptedException e) {
            streamExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("HLS Stream Service shut down complete");
    }

    /**
     * Connect to RTSP with fallback variants
     */
    private FFmpegFrameGrabber tryStartRtspWithFallback(String rtspUrl) throws Exception {
        FFmpegLogCallback.set();

        List<String> candidates = new ArrayList<>();
        candidates.add(rtspUrl);

        try {
            URI u = new URI(rtspUrl);
            String userInfo = u.getUserInfo();
            String base = u.getScheme() + "://" + (userInfo != null ? userInfo + "@" : "") + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
            String path = u.getPath() != null ? u.getPath() : "/";

            if (path.matches("/.+/.*")) {
                candidates.add(base + "/Streaming/Channels/101");
                candidates.add(base + "/Streaming/Channels/102");
                candidates.add(base + "/cam/realmonitor?channel=1&subtype=0");
                candidates.add(base + "/live");
                candidates.add(base + "/live.sdp");
                candidates.add(base + "/h264");
                candidates.add(base + "/ch1-s1");
                candidates.add(base + "/unicast/c1/s0/live");
                candidates.add(base + "/avstream/channel=1/stream=0.sdp");
            }

            candidates.add(base + path + (path.endsWith("/") ? "" : "/") + "trackID=0");
            candidates.add(base + path + (path.endsWith("/") ? "" : "/") + "trackID=1");

        } catch (Exception ignore) {
        }

        Exception last = null;
        for (String cand : candidates) {
            for (String transport : new String[] { "tcp", "udp", "http" }) {
                logger.info("Trying RTSP URL: " + cand + " via " + transport);
                FFmpegFrameGrabber g = new FFmpegFrameGrabber(cand);
                g.setFormat("rtsp");
                g.setOption("rtsp_transport", transport);
                if ("tcp".equals(transport))
                    g.setOption("rtsp_flags", "prefer_tcp");
                g.setOption("stimeout", "20000000");
                g.setOption("rw_timeout", "20000000");
                g.setOption("analyzeduration", "1000000");
                g.setOption("probesize", "1000000");
                g.setOption("fflags", "+nobuffer+genpts+igndts");
                g.setOption("max_delay", "0");
                g.setOption("reorder_queue_size", "0");
                g.setOption("use_wallclock_as_timestamps", "1");
                g.setOption("allowed_media_types", "video");
                g.setOption("user_agent", "LibVLC/3.0.18 (LIVE555 Streaming Media v2021.06.08)");
                g.setOption("loglevel", "info");
                if ("udp".equals(transport)) {
                    g.setOption("fflags", "+nobuffer+genpts+igndts");
                    g.setOption("max_delay", "0");
                    g.setOption("flags", "low_delay");
                    g.setOption("probesize", "1000000");
                    g.setOption("analyzeduration", "1000000");
                }

                try {
                    g.start();
                    logger.info("Connected to RTSP: " + cand + ", size=" + g.getImageWidth() + "x" + g.getImageHeight());
                    return g;
                } catch (Exception e) {
                    last = e;
                    logger.warning("Failed URL/transport: " + cand + " (" + transport + ") - " + e.getMessage());
                    try {
                        g.release();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        if (last != null)
            throw last;
        throw new RuntimeException("RTSP connection failed for all candidates");
    }

    /**
     * Helper class to store stream resources
     */
    private static class StreamResources {
        final FFmpegFrameGrabber grabber;
        final FFmpegFrameRecorder recorder;

        StreamResources(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
            this.grabber = grabber;
            this.recorder = recorder;
        }
    }
}