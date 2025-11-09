package com.backendcam.backendcam;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;

@Service
public class HLSStreamService {

    private static final Logger logger = Logger.getLogger(HLSStreamService.class.getName());

    // Configuration
    private static final String HLS_ROOT = "./hls";
    private static final String LOG_ROOT = "./logs";
    private static final int MAX_STREAMS = 100;
    private static final int WORKER_THREADS = 50;
    private static final long STARTUP_DELAY_MS = 800;
    private static final int TARGET_FPS = 10;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long RECONNECT_DELAY_MS = 5000;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1 minute (was 30 seconds)
    private static final long MEMORY_CHECK_INTERVAL_MS = 60000; // 1 minute
    private static final long CSV_LOG_INTERVAL_MS = 180000; // 3 minutes
    private static final long STREAM_TIMEOUT_MS = 300000; // 5 minutes

    // Thread pools
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService startupScheduler;
    private final ScheduledExecutorService healthCheckScheduler;
    private final ScheduledExecutorService memoryMonitor;
    private final ScheduledExecutorService csvLogger;

    // Startup control
    private final Semaphore startupSemaphore = new Semaphore(1, true);
    private final AtomicInteger startupQueue = new AtomicInteger(0);
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    // Stream tracking
    private final ConcurrentHashMap<String, String> streamLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Future<?>> streamTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamResources> streamResources = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StreamStats> streamStats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> streamStopFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> streamRtspUrls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastFrameTimes = new ConcurrentHashMap<>();

    // System monitoring
    private final OperatingSystemMXBean osBean;
    private PrintWriter csvWriter;

    public HLSStreamService() {
        this.streamExecutor = new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "HLS-Worker-" + counter.getAndIncrement());
                        t.setDaemon(false);
                        t.setPriority(Thread.NORM_PRIORITY);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        this.startupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HLS-Startup-Controller");
            t.setDaemon(false);
            return t;
        });

        this.healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HLS-HealthCheck");
            t.setDaemon(true);
            return t;
        });

        this.memoryMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HLS-MemoryMonitor");
            t.setDaemon(true);
            return t;
        });

        this.csvLogger = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HLS-CSVLogger");
            t.setDaemon(true);
            return t;
        });

        ((ThreadPoolExecutor) streamExecutor).prestartAllCoreThreads();

        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);

        this.osBean = ManagementFactory.getOperatingSystemMXBean();

        initializeCSVLogger();
        startHealthCheck();
        startMemoryMonitor();
        startCSVLogging();

        logger.info("╔════════════════════════════════════════════╗");
        logger.info("║  HLS Service - Memory Fixed + Monitoring  ║");
        logger.info("║  Max Streams: " + MAX_STREAMS + "                          ║");
        logger.info("║  Worker Threads: " + WORKER_THREADS + "                      ║");
        logger.info("║  Target FPS: " + TARGET_FPS + "                            ║");
        logger.info("║  Health Check: Every 30s                  ║");
        logger.info("║  Memory Monitor: Every 60s                ║");
        logger.info("║  CSV Logging: Every 3min                  ║");
        logger.info("╚════════════════════════════════════════════╝");
    }

    private void initializeCSVLogger() {
        try {
            File logDir = new File(LOG_ROOT);
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File csvFile = new File(LOG_ROOT, "system_metrics_" + timestamp + ".csv");
            
            csvWriter = new PrintWriter(new FileWriter(csvFile, true));
            
            // Write CSV header
            csvWriter.println("Timestamp,ActiveStreams,WorkerThreads,ActiveThreads,QueueSize," +
                    "UsedMemoryMB,MaxMemoryMB,MemoryUsagePercent," +
                    "SystemCPULoad,ProcessCPULoad,TotalReadFrames,TotalEncodedFrames," +
                    "TotalErrors,DeadStreams");
            csvWriter.flush();
            
            logger.info("✓ CSV logger initialized: " + csvFile.getAbsolutePath());
        } catch (Exception e) {
            logger.severe("❌ Failed to initialize CSV logger: " + e.getMessage());
        }
    }

    private final ConcurrentHashMap<String, AtomicInteger> streamReconnectAttempts = new ConcurrentHashMap<>();
    private static final int MAX_HEALTH_CHECK_RECONNECTS = 3;

    private void startHealthCheck() {
        healthCheckScheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                List<String> deadStreams = new ArrayList<>();
                List<String> reconnectStreams = new ArrayList<>();

                for (Map.Entry<String, Long> entry : lastFrameTimes.entrySet()) {
                    String streamName = entry.getKey();
                    long lastFrame = entry.getValue();

                    if (now - lastFrame > STREAM_TIMEOUT_MS) {
                        AtomicInteger reconnectCount = streamReconnectAttempts.computeIfAbsent(
                            streamName, k -> new AtomicInteger(0));
                        
                        int attempts = reconnectCount.get();
                        
                        if (attempts < MAX_HEALTH_CHECK_RECONNECTS) {
                            logger.warning("⚠ Stream appears dead (no frames): " + streamName + 
                                         " - Reconnect attempt " + (attempts + 1) + "/" + MAX_HEALTH_CHECK_RECONNECTS);
                            reconnectStreams.add(streamName);
                            reconnectCount.incrementAndGet();
                        } else {
                            logger.severe("☠ Stream exhausted reconnect attempts: " + streamName + " - KILLING");
                            deadStreams.add(streamName);
                        }
                    } else {
                        // Stream is healthy, reset reconnect counter
                        streamReconnectAttempts.remove(streamName);
                    }
                }

                // Trigger reconnection for dead streams
                for (String streamName : reconnectStreams) {
                    logger.info("🔄 Health check triggering reconnect: " + streamName);
                    triggerStreamReconnect(streamName);
                }

                // Kill streams that exhausted reconnect attempts
                for (String deadStream : deadStreams) {
                    streamReconnectAttempts.remove(deadStream);
                    stopHLSStream(deadStream);
                }

                ThreadPoolExecutor pool = (ThreadPoolExecutor) streamExecutor;
                logger.info(String.format(
                    "📊 Health: Streams=%d, Workers=%d/%d (queue:%d), Reconnecting=%d, Killed=%d",
                    streamLinks.size(),
                    pool.getActiveCount(), WORKER_THREADS, pool.getQueue().size(),
                    reconnectStreams.size(),
                    deadStreams.size()
                ));

            } catch (Exception e) {
                logger.severe("❌ Health check error: " + e.getMessage());
            }
        }, HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void triggerStreamReconnect(String streamName) {
        try {
            // Cancel current stream task
            Future<?> future = streamTasks.get(streamName);
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }

            // Get RTSP URL
            String rtspUrl = streamRtspUrls.get(streamName);
            if (rtspUrl == null) {
                logger.warning("⚠ Cannot reconnect " + streamName + ": No RTSP URL found");
                return;
            }

            // Reset last frame time to prevent immediate re-trigger
            lastFrameTimes.put(streamName, System.currentTimeMillis());

            // Get or create stats (must be final for lambda)
            final StreamStats finalStats = streamStats.computeIfAbsent(streamName, StreamStats::new);

            // Submit new task
            logger.info("▶ Reconnecting stream: " + streamName);
            Future<?> newFuture = streamExecutor.submit(() -> {
                runStreamWithAutoReconnect(rtspUrl, streamName, finalStats);
            });

            streamTasks.put(streamName, newFuture);

        } catch (Exception e) {
            logger.severe("❌ Failed to trigger reconnect for " + streamName + ": " + e.getMessage());
        }
    }

    private void startMemoryMonitor() {
        memoryMonitor.scheduleAtFixedRate(() -> {
            try {
                Runtime runtime = Runtime.getRuntime();
                long totalMemory = runtime.totalMemory();
                long freeMemory = runtime.freeMemory();
                long usedMemory = totalMemory - freeMemory;
                long maxMemory = runtime.maxMemory();
                
                double usedPercent = (usedMemory * 100.0) / maxMemory;
                
                logger.info(String.format(
                    "💾 Memory: Used=%dMB/Max=%dMB (%.1f%%), Streams=%d",
                    usedMemory / (1024 * 1024),
                    maxMemory / (1024 * 1024),
                    usedPercent,
                    streamLinks.size()
                ));

                if (usedPercent > 85) {
                    logger.warning("⚠ High memory usage (" + String.format("%.1f%%", usedPercent) + "), suggesting GC");
                    System.gc();
                    
                    Thread.sleep(2000);
                    long newUsed = runtime.totalMemory() - runtime.freeMemory();
                    double newPercent = (newUsed * 100.0) / maxMemory;
                    logger.info("💾 After GC: " + String.format("%.1f%%", newPercent) + " (" + 
                               String.format("%.1f%%", usedPercent - newPercent) + " freed)");
                }

                if (usedPercent > 95) {
                    logger.severe("🔥 CRITICAL MEMORY! Stopping oldest streams...");
                    stopOldestStreams(5);
                }

            } catch (Exception e) {
                logger.severe("❌ Memory monitor error: " + e.getMessage());
            }
        }, MEMORY_CHECK_INTERVAL_MS, MEMORY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void startCSVLogging() {
        csvLogger.scheduleAtFixedRate(() -> {
            try {
                logSystemMetricsToCSV();
            } catch (Exception e) {
                logger.severe("❌ CSV logging error: " + e.getMessage());
            }
        }, CSV_LOG_INTERVAL_MS, CSV_LOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void logSystemMetricsToCSV() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();
            double memoryUsagePercent = (usedMemory * 100.0) / maxMemory;

            ThreadPoolExecutor pool = (ThreadPoolExecutor) streamExecutor;
            int activeThreads = pool.getActiveCount();
            int queueSize = pool.getQueue().size();

            // Get CPU load (if available)
            double systemCpuLoad = -1;
            double processCpuLoad = -1;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOsBean = 
                    (com.sun.management.OperatingSystemMXBean) osBean;
                systemCpuLoad = sunOsBean.getSystemCpuLoad() * 100;
                processCpuLoad = sunOsBean.getProcessCpuLoad() * 100;
            }

            // Aggregate stream stats
            long totalReadFrames = 0;
            long totalEncodedFrames = 0;
            long totalErrors = 0;
            for (StreamStats stats : streamStats.values()) {
                totalReadFrames += stats.getTotalReadFrames();
                totalEncodedFrames += stats.getTotalEncodedFrames();
                totalErrors += stats.getErrors();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            
            csvWriter.printf("%s,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d%n",
                    timestamp,
                    streamLinks.size(),
                    WORKER_THREADS,
                    activeThreads,
                    queueSize,
                    usedMemory / (1024 * 1024),
                    maxMemory / (1024 * 1024),
                    memoryUsagePercent,
                    systemCpuLoad,
                    processCpuLoad,
                    totalReadFrames,
                    totalEncodedFrames,
                    totalErrors,
                    0 // Dead streams count (would need tracking)
            );
            csvWriter.flush();

            logger.info("📝 CSV metrics logged at " + timestamp);

        } catch (Exception e) {
            logger.severe("❌ Error writing CSV metrics: " + e.getMessage());
        }
    }

    private void stopOldestStreams(int count) {
        List<Map.Entry<String, StreamStats>> sortedStreams = new ArrayList<>(streamStats.entrySet());
        sortedStreams.sort(Comparator.comparingLong(e -> e.getValue().startTime));
        
        int stopped = 0;
        for (Map.Entry<String, StreamStats> entry : sortedStreams) {
            if (stopped >= count) break;
            String streamName = entry.getKey();
            logger.warning("🛑 Emergency stop (memory): " + streamName);
            stopHLSStream(streamName);
            stopped++;
        }
    }

    public String startHLSStream(String rtspUrl, String streamName) {
        if (isShuttingDown.get()) {
            throw new RuntimeException("Service is shutting down");
        }

        if (streamLinks.containsKey(streamName)) {
            logger.info("✓ Stream already exists: " + streamName);
            return streamLinks.get(streamName);
        }

        if (streamTasks.size() >= MAX_STREAMS) {
            throw new RuntimeException("Max capacity reached: " + MAX_STREAMS);
        }

        String playlistPath = "/api/hls/" + streamName + "/stream.m3u8";

        streamLinks.put(streamName, playlistPath);
        streamRtspUrls.put(streamName, rtspUrl);
        streamStopFlags.put(streamName, new AtomicBoolean(false));
        lastFrameTimes.put(streamName, System.currentTimeMillis());

        StreamStats stats = new StreamStats(streamName);
        streamStats.put(streamName, stats);

        int queuePosition = startupQueue.incrementAndGet();
        long delay = (queuePosition - 1) * STARTUP_DELAY_MS;

        logger.info("→ Queued: " + streamName + " (position: " + queuePosition + ", delay: " + delay + "ms)");

        startupScheduler.schedule(() -> {
            try {
                startupSemaphore.acquire();

                if (isShuttingDown.get()) {
                    logger.info("⚠ Skipping startup (shutdown in progress): " + streamName);
                    cleanupStreamState(streamName);
                    return;
                }

                logger.info("▶ Starting: " + streamName);
                stats.recordStartAttempt();

                Future<?> future = streamExecutor.submit(() -> {
                    runStreamWithAutoReconnect(rtspUrl, streamName, stats);
                });

                streamTasks.put(streamName, future);

            } catch (InterruptedException e) {
                logger.severe("⚠ Startup interrupted: " + streamName);
                cleanupStreamState(streamName);
                Thread.currentThread().interrupt();
            } catch (RejectedExecutionException e) {
                logger.severe("⚠ Thread pool full: " + streamName);
                cleanupStreamState(streamName);
            } finally {
                startupSemaphore.release();
                startupQueue.decrementAndGet();
            }
        }, delay, TimeUnit.MILLISECONDS);

        return playlistPath;
    }

    private void runStreamWithAutoReconnect(String rtspUrl, String streamName, StreamStats stats) {
        int reconnectAttempt = 0;
        AtomicBoolean stopFlag = streamStopFlags.get(streamName);

        while (reconnectAttempt < MAX_RECONNECT_ATTEMPTS &&
                !isShuttingDown.get() &&
                streamLinks.containsKey(streamName) &&
                !stopFlag.get()) {

            try {
                if (reconnectAttempt > 0) {
                    logger.info("🔄 Reconnecting: " + streamName + " (attempt " + reconnectAttempt + ")");
                }

                runStream(rtspUrl, streamName, stats);

                if (stopFlag.get()) {
                    logger.info("✓ Stream stopped by user: " + streamName);
                    break;
                }

                logger.warning("⚠ Stream ended unexpectedly: " + streamName);
                reconnectAttempt++;

                if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS && !stopFlag.get()) {
                    logger.info("⏳ Reconnecting " + streamName + " in " + (RECONNECT_DELAY_MS / 1000) + "s...");
                    Thread.sleep(RECONNECT_DELAY_MS);
                }

            } catch (Exception e) {
                stats.recordError(e);
                logger.warning("✗ Stream error: " + streamName + " - " + e.getMessage());
                reconnectAttempt++;

                if (reconnectAttempt < MAX_RECONNECT_ATTEMPTS && !stopFlag.get()) {
                    try {
                        Thread.sleep(RECONNECT_DELAY_MS * reconnectAttempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            logger.severe("✗ Max reconnect attempts reached for: " + streamName);
        }

        cleanupStreamState(streamName);
    }

    private void runStream(String rtspUrl, String streamName, StreamStats stats) throws Exception {
        FFmpegFrameGrabber grabber = null;
        FFmpegFrameRecorder recorder = null;
        boolean recorderStarted = false;

        try {
            File outputDir = new File(HLS_ROOT, streamName);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";

            grabber = connectRtspWithRetry(rtspUrl, streamName);
            
            double sourceFrameRate = grabber.getFrameRate();
            if (sourceFrameRate <= 0 || sourceFrameRate > 60) {
                sourceFrameRate = 25;
            }
            
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            String codec = grabber.getVideoCodecName();

            if (width <= 0 || height <= 0) {
                throw new RuntimeException("Invalid resolution: " + width + "x" + height);
            }

            stats.setResolution(width, height);
            stats.setSourceCodec(codec);
            stats.setSourceFps(sourceFrameRate);
            logger.info("✓ RTSP connected: " + streamName + " (" + width + "x" + height + 
                       ", codec: " + codec + ", source FPS: " + sourceFrameRate + ")");

            recorder = createRecorder(hlsOutput, outputDir, width, height);

            Thread.sleep(200);

            recorder.start();
            recorderStarted = true;
            stats.setEncoder("libx264");
            logger.info("✓ Recording: " + streamName);

            StreamResources resources = new StreamResources(grabber, recorder);
            streamResources.put(streamName, resources);

            ThreadPoolExecutor executor = (ThreadPoolExecutor) streamExecutor;
            logger.info("📊 Active threads: " + executor.getActiveCount() + "/" + WORKER_THREADS +
                    " | Total system threads: " + Thread.activeCount());

            int frameSkipRatio = Math.max(1, (int) Math.round(sourceFrameRate / TARGET_FPS));
            logger.info("🎯 Frame strategy: Read ALL frames, encode every " + frameSkipRatio + 
                       "th frame (" + sourceFrameRate + " fps → " + TARGET_FPS + " fps)");

            streamFrames(grabber, recorder, streamName, stats, frameSkipRatio);

        } finally {
            safeCleanup(streamName, grabber, recorder, recorderStarted);
        }
    }

    /**
     * CRITICAL FIX: Properly close frames to prevent memory leaks
     * IMMEDIATE RECONNECT: Reconnect on null frames instead of waiting for health check
     */
    private void streamFrames(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder, 
                              String streamName, StreamStats stats, int frameSkipRatio) throws Exception {
        Frame frame = null;
        long lastLogTime = System.currentTimeMillis();
        long lastStatsUpdate = System.currentTimeMillis();

        final int MAX_CONSECUTIVE_ERRORS = 30;
        final int MAX_NULL_FRAMES = 100;  // Changed: More tolerance before reconnect
        int consecutiveErrors = 0;
        int consecutiveNullFrames = 0;
        int frameCounter = 0;

        AtomicBoolean stopFlag = streamStopFlags.get(streamName);

        while (!isShuttingDown.get() &&
                !Thread.currentThread().isInterrupted() &&
                !stopFlag.get()) {

            try {
                // Read frame (CRITICAL: must be closed!)
                frame = grabber.grabImage();

                if (frame == null) {
                    consecutiveNullFrames++;

                    if (consecutiveNullFrames >= MAX_NULL_FRAMES) {
                        // IMMEDIATE RECONNECT: Trigger reconnect instead of just logging
                        logger.warning(streamName + ": Too many null frames (" + consecutiveNullFrames + ") - Triggering immediate reconnect");
                        
                        // Throw exception to exit this stream loop
                        // The auto-reconnect wrapper will catch it and reconnect
                        throw new RuntimeException("Stream ended: Too many consecutive null frames (" + consecutiveNullFrames + ")");
                    }
                    
                    Thread.sleep(5);
                    continue;
                }

                consecutiveNullFrames = 0;
                stats.recordReadFrame();
                lastFrameTimes.put(streamName, System.currentTimeMillis());
                frameCounter++;

                boolean shouldEncode = (frameCounter % frameSkipRatio == 0);

                if (shouldEncode && frame.image != null) {
                    try {
                        recorder.record(frame);
                        stats.recordEncodedFrame();
                        consecutiveErrors = 0;

                    } catch (Exception e) {
                        stats.recordError(e);
                        consecutiveErrors++;

                        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                            logger.warning(streamName + ": Too many encode errors (" + consecutiveErrors + ") - Triggering reconnect");
                            throw new RuntimeException("Too many encode errors: " + consecutiveErrors);
                        }
                    }
                } else {
                    stats.recordSkippedFrame();
                }

                // CRITICAL: Close frame to release native memory!
                try {
                    if (frame != null) {
                        frame.close();
                        frame = null; // Prevent double-close
                    }
                } catch (Exception e) {
                    // Ignore close errors
                }

                // Logging
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 10000) {
                    logger.info(stats.getLogSummary());
                    lastLogTime = now;
                }

                if (now - lastStatsUpdate > 5000) {
                    stats.updateFps();
                    lastStatsUpdate = now;
                }

            } catch (Exception e) {
                // Make sure frame is closed even on error
                if (frame != null) {
                    try {
                        frame.close();
                        frame = null;
                    } catch (Exception ignored) {}
                }

                stats.recordError(e);
                
                String msg = e.getMessage();

                // Check if this is a "no frames" reconnect trigger
                if (msg != null && msg.contains("Too many consecutive null frames")) {
                    // This will be caught by runStreamWithAutoReconnect and trigger reconnection
                    throw e;
                }

                consecutiveErrors++;

                if (msg != null && (
                        msg.contains("Could not find ref") ||
                        msg.contains("error while decoding") ||
                        msg.contains("corrupted") ||
                        msg.contains("cbp too large") ||
                        msg.contains("POC") ||
                        msg.contains("Invalid NAL"))) {

                    if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                        continue;
                    } else {
                        logger.warning(streamName + ": Too many HEVC errors - Triggering reconnect");
                        throw new RuntimeException("Too many HEVC errors: " + consecutiveErrors);
                    }
                }

                throw e;
            }
        }

        // Final cleanup: close any remaining frame
        if (frame != null) {
            try {
                frame.close();
            } catch (Exception ignored) {}
        }
    }

    private FFmpegFrameGrabber connectRtspWithRetry(String rtspUrl, String streamName) throws Exception {
        List<String> candidates = buildRtspCandidates(rtspUrl);
        Exception lastException = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            for (String url : candidates) {
                try {
                    FFmpegFrameGrabber grabber = createGrabber(url);
                    grabber.start();

                    Frame testFrame = grabber.grabImage();
                    if (testFrame != null && testFrame.image != null) {
                        // CRITICAL: Close test frame!
                        try {
                            testFrame.close();
                        } catch (Exception ignored) {}
                        
                        logger.info("✓ Connected to: " + url);
                        return grabber;
                    }

                    grabber.release();

                } catch (Exception e) {
                    lastException = e;
                }
            }

            if (attempt < 3) {
                Thread.sleep(1000 * attempt);
            }
        }

        throw lastException != null ? lastException : new RuntimeException("RTSP connection failed");
    }

    private FFmpegFrameGrabber createGrabber(String url) {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(url);
        g.setFormat("rtsp");
        g.setImageMode(org.bytedeco.javacv.FrameGrabber.ImageMode.COLOR);

        g.setOption("threads", "1");
        g.setOption("thread_count", "0");
        g.setVideoOption("threads", "1");

        g.setOption("err_detect", "ignore_err");
        g.setOption("ec", "favor_inter+guess_mvs+deblock");
        g.setOption("fflags", "discardcorrupt");
        g.setOption("flags", "low_delay");

        g.setOption("rtsp_transport", "tcp");
        g.setOption("rtsp_flags", "prefer_tcp");
        g.setOption("stimeout", "10000000");
        g.setOption("timeout", "1000000");

        g.setOption("analyzeduration", "2000000");
        g.setOption("probesize", "2000000");
        g.setOption("max_delay", "500000");
        g.setOption("allowed_media_types", "video");
        g.setOption("max_error_rate", "0.9");

        g.setOption("use_wallclock_as_timestamps", "1");
        g.setOption("fflags", "+genpts");

        return g;
    }

    private List<String> buildRtspCandidates(String rtspUrl) {
        List<String> candidates = new ArrayList<>();
        candidates.add(rtspUrl);

        try {
            URI u = new URI(rtspUrl);
            String base = u.getScheme() + "://" +
                    (u.getUserInfo() != null ? u.getUserInfo() + "@" : "") +
                    u.getHost() +
                    (u.getPort() > 0 ? ":" + u.getPort() : "");

            candidates.add(base + "/Streaming/Channels/101");
            candidates.add(base + "/live");
        } catch (Exception ignored) {
        }

        return candidates;
    }

    private FFmpegFrameRecorder createRecorder(String hlsOutput, File outputDir, int width, int height)
            throws Exception {
        int targetWidth = width;
        int targetHeight = height;

        if (height > 720) {
            double aspectRatio = (double) width / height;
            targetHeight = 720;
            targetWidth = (int) (targetHeight * aspectRatio);
            targetWidth = (targetWidth / 2) * 2;
            targetHeight = (targetHeight / 2) * 2;
        }

        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(hlsOutput, targetWidth, targetHeight, 0);
        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
        recorder.setFormat("hls");

        recorder.setFrameRate(TARGET_FPS);
        recorder.setGopSize(TARGET_FPS * 2);

        recorder.setOption("hls_time", "3");
        recorder.setOption("hls_list_size", "2");
        recorder.setOption("hls_flags", "delete_segments");
        recorder.setOption("hls_segment_type", "mpegts");
        recorder.setOption("hls_allow_cache", "0");

        String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/s%d.ts";
        recorder.setOption("hls_segment_filename", segPath);

        recorder.setOption("threads", "1");
        recorder.setVideoOption("threads", "1");

        recorder.setOption("preset", "veryfast");
        recorder.setOption("tune", "zerolatency");
        recorder.setOption("crf", "24");
        recorder.setOption("maxrate", "1000k");
        recorder.setOption("bufsize", "1500k");

        recorder.setOption("sc_threshold", "0");
        recorder.setOption("g", String.valueOf(TARGET_FPS * 2));
        recorder.setOption("keyint_min", String.valueOf(TARGET_FPS));
        recorder.setOption("refs", "2");
        recorder.setOption("bf", "1");
        recorder.setOption("me_method", "dia");
        recorder.setOption("subq", "0");
        recorder.setOption("trellis", "0");
        recorder.setOption("cabac", "0");
        recorder.setOption("fast-pskip", "1");
        recorder.setOption("flags", "+low_delay");
        recorder.setOption("fflags", "+genpts");
        recorder.setOption("fps_mode", "cfr");

        return recorder;
    }

    public void stopHLSStream(String streamName) {
        logger.info("■ Stopping: " + streamName);

        AtomicBoolean stopFlag = streamStopFlags.get(streamName);
        if (stopFlag != null) {
            stopFlag.set(true);
        }

        Future<?> future = streamTasks.remove(streamName);
        if (future != null && !future.isDone()) {
            future.cancel(true);

            try {
                future.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                logger.warning("⚠ Timeout waiting for " + streamName + " to stop");
            } catch (Exception e) {
                // Expected for cancelled tasks
            }
        }

        cleanupStreamState(streamName);
    }

    private void cleanupStreamState(String streamName) {
        streamLinks.remove(streamName);
        streamRtspUrls.remove(streamName);
        streamStopFlags.remove(streamName);
        lastFrameTimes.remove(streamName);
        streamReconnectAttempts.remove(streamName);

        StreamResources resources = streamResources.remove(streamName);
        if (resources != null) {
            safeCleanup(streamName, resources.grabber, resources.recorder, true);
        }

        StreamStats stats = streamStats.remove(streamName);
        if (stats != null) {
            logger.info("📊 Final stats: " + stats.getFinalSummary());
        }

        deleteStreamFiles(streamName);
    }

    private void safeCleanup(String streamName, FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder,
            boolean recorderWasStarted) {
        streamResources.remove(streamName);

        if (recorder != null && recorderWasStarted) {
            try {
                recorder.stop();
            } catch (Exception e) {
                logger.warning("⚠ Error stopping recorder: " + streamName);
            }

            try {
                recorder.release();
            } catch (Exception e) {
                logger.warning("⚠ Error releasing recorder: " + streamName);
            }
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                logger.warning("⚠ Error stopping grabber: " + streamName);
            }

            try {
                grabber.release();
            } catch (Exception e) {
                logger.warning("⚠ Error releasing grabber: " + streamName);
            }
        }
    }

    private void deleteStreamFiles(String streamName) {
        try {
            File dir = new File(HLS_ROOT, streamName);
            if (dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) {
                            logger.warning("⚠ Failed to delete: " + f.getName());
                        }
                    }
                }
                if (!dir.delete()) {
                    logger.warning("⚠ Failed to delete directory: " + streamName);
                }
            }
        } catch (Exception e) {
            logger.warning("⚠ Error deleting files: " + streamName);
        }
    }

    public List<String> getActiveStreams() {
        return new ArrayList<>(streamLinks.keySet());
    }

    public int getActiveStreamCount() {
        return streamLinks.size();
    }

    public int getQueueSize() {
        return startupQueue.get();
    }

    public boolean isStreamReady(String streamName) {
        return streamResources.containsKey(streamName);
    }

    public String getStreamStatus(String streamName) {
        if (!streamLinks.containsKey(streamName))
            return "NOT_FOUND";
        if (streamResources.containsKey(streamName))
            return "RUNNING";
        Future<?> future = streamTasks.get(streamName);
        if (future != null && !future.isDone())
            return "STARTING";
        return "STOPPED";
    }

    public StreamStats getStreamStats(String streamName) {
        return streamStats.get(streamName);
    }

    public Map<String, Object> getSystemStats() {
        Map<String, Object> stats = new HashMap<>();
        
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();
        
        stats.put("activeStreams", streamLinks.size());
        stats.put("startupQueue", startupQueue.get());
        stats.put("maxStreams", MAX_STREAMS);
        
        ThreadPoolExecutor pool = (ThreadPoolExecutor) streamExecutor;
        
        Map<String, Object> poolStats = new HashMap<>();
        poolStats.put("active", pool.getActiveCount());
        poolStats.put("total", WORKER_THREADS);
        poolStats.put("queueSize", pool.getQueue().size());
        poolStats.put("completed", pool.getCompletedTaskCount());
        stats.put("threadPool", poolStats);
        
        Map<String, Object> memoryStats = new HashMap<>();
        memoryStats.put("usedMB", usedMemory / (1024 * 1024));
        memoryStats.put("maxMB", maxMemory / (1024 * 1024));
        memoryStats.put("usedPercent", (usedMemory * 100.0) / maxMemory);
        stats.put("memory", memoryStats);
        
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = 
                (com.sun.management.OperatingSystemMXBean) osBean;
            Map<String, Object> cpuStats = new HashMap<>();
            cpuStats.put("systemLoad", sunOsBean.getSystemCpuLoad() * 100);
            cpuStats.put("processLoad", sunOsBean.getProcessCpuLoad() * 100);
            stats.put("cpu", cpuStats);
        }
        
        return stats;
    }

    @PreDestroy
    public void shutdown() {
        logger.info("╔════════════════════════════════════════════╗");
        logger.info("║  Shutting down HLS Service...             ║");
        logger.info("║  Active streams: " + String.format("%-24d", getActiveStreamCount()) + "║");
        logger.info("╚════════════════════════════════════════════╝");

        isShuttingDown.set(true);

        // Stop monitoring schedulers
        healthCheckScheduler.shutdown();
        memoryMonitor.shutdown();
        csvLogger.shutdown();
        
        try {
            if (!healthCheckScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                healthCheckScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            healthCheckScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        try {
            if (!memoryMonitor.awaitTermination(2, TimeUnit.SECONDS)) {
                memoryMonitor.shutdownNow();
            }
        } catch (InterruptedException e) {
            memoryMonitor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        try {
            if (!csvLogger.awaitTermination(2, TimeUnit.SECONDS)) {
                csvLogger.shutdownNow();
            }
        } catch (InterruptedException e) {
            csvLogger.shutdownNow();
            Thread.currentThread().interrupt();
        }

        streamStopFlags.values().forEach(flag -> flag.set(true));

        startupScheduler.shutdown();
        try {
            if (!startupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                startupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            startupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        List<String> streamsToStop = new ArrayList<>(streamLinks.keySet());
        logger.info("  Stopping " + streamsToStop.size() + " streams...");

        for (String streamName : streamsToStop) {
            try {
                stopHLSStream(streamName);
            } catch (Exception e) {
                logger.warning("⚠ Error stopping: " + streamName);
            }
        }

        streamExecutor.shutdown();
        try {
            if (!streamExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("⚠ Forcing executor shutdown...");
                streamExecutor.shutdownNow();

                if (!streamExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.severe("✗ Executor did not terminate");
                }
            }
        } catch (InterruptedException e) {
            streamExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close CSV writer
        if (csvWriter != null) {
            try {
                csvWriter.close();
                logger.info("✓ CSV logger closed");
            } catch (Exception e) {
                logger.warning("⚠ Error closing CSV logger");
            }
        }

        logger.info("  ✓ Shutdown complete");
        logger.info("╚════════════════════════════════════════════╝");
    }

    private static class StreamResources {
        final FFmpegFrameGrabber grabber;
        final FFmpegFrameRecorder recorder;

        StreamResources(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder) {
            this.grabber = grabber;
            this.recorder = recorder;
        }
    }

    public static class StreamStats {
        final String streamName;
        final long startTime;
        private final LongAdder totalReadFrames = new LongAdder();
        private final LongAdder totalEncodedFrames = new LongAdder();
        private final LongAdder skippedFrames = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final AtomicInteger startAttempts = new AtomicInteger(0);
        private volatile String encoder = "unknown";
        private volatile String sourceCodec = "unknown";
        private volatile String resolution = "unknown";
        private volatile double sourceFps = 0.0;
        private volatile double currentFps = 0.0;
        private volatile long lastFrameCount = 0;
        private volatile long lastFpsUpdate = System.currentTimeMillis();

        public StreamStats(String streamName) {
            this.streamName = streamName;
            this.startTime = System.currentTimeMillis();
        }

        public void recordReadFrame() {
            totalReadFrames.increment();
        }

        public void recordEncodedFrame() {
            totalEncodedFrames.increment();
        }

        public void recordSkippedFrame() {
            skippedFrames.increment();
        }

        public void recordError(Exception e) {
            errors.increment();
        }

        public void recordStartAttempt() {
            startAttempts.incrementAndGet();
        }

        public void setEncoder(String encoder) {
            this.encoder = encoder;
        }

        public void setSourceCodec(String codec) {
            this.sourceCodec = codec;
        }

        public void setSourceFps(double fps) {
            this.sourceFps = fps;
        }

        public void setResolution(int width, int height) {
            this.resolution = width + "x" + height;
        }

        public void updateFps() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastFpsUpdate;
            if (elapsed > 0) {
                long current = totalEncodedFrames.sum();
                long framesSinceLastUpdate = current - lastFrameCount;
                currentFps = (framesSinceLastUpdate * 1000.0) / elapsed;
                lastFrameCount = current;
                lastFpsUpdate = now;
            }
        }

        public String getLogSummary() {
            long uptime = (System.currentTimeMillis() - startTime) / 1000;
            long read = totalReadFrames.sum();
            long encoded = totalEncodedFrames.sum();
            long skipped = skippedFrames.sum();
            double readRate = uptime > 0 ? read / (double) uptime : 0;
            
            return String.format(
                "%s | %s(%.0ffps)→%s | %s | read: %d (%.1f/s) | enc: %d (%.1f fps) | skip: %d | err: %d | up: %ds",
                streamName, sourceCodec, sourceFps, encoder, resolution, read, readRate, 
                encoded, currentFps, skipped, errors.sum(), uptime);
        }

        public String getFinalSummary() {
            long totalTime = (System.currentTimeMillis() - startTime) / 1000;
            long read = totalReadFrames.sum();
            long encoded = totalEncodedFrames.sum();
            double avgReadFps = totalTime > 0 ? read / (double) totalTime : 0;
            double avgEncodedFps = totalTime > 0 ? encoded / (double) totalTime : 0;
            return String.format(
                    "%s | %s(%.0ffps)→%s | Read: %d (%.1f fps) | Enc: %d (%.1f fps) | Err: %d | Attempts: %d | Runtime: %ds",
                    streamName, sourceCodec, sourceFps, encoder, read, avgReadFps, encoded, avgEncodedFps, 
                    errors.sum(), startAttempts.get(), totalTime);
        }

        public String getStreamName() { return streamName; }
        public int getTotalReadFrames() { return totalReadFrames.intValue(); }
        public int getTotalEncodedFrames() { return totalEncodedFrames.intValue(); }
        public int getSkippedFrames() { return skippedFrames.intValue(); }
        public int getErrors() { return errors.intValue(); }
        public String getEncoder() { return encoder; }
        public String getSourceCodec() { return sourceCodec; }
        public String getResolution() { return resolution; }
        public double getCurrentFps() { return currentFps; }
        public double getSourceFps() { return sourceFps; }
        public long getUptimeSeconds() { return (System.currentTimeMillis() - startTime) / 1000; }
    }
}