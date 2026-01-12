package com.backendcam.backendcam.service.hls;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class HLSStreamService {

    private static final Logger logger = LoggerFactory.getLogger(HLSStreamService.class);

    private final Map<String, Thread> streamThreads = new ConcurrentHashMap<>();
    private final Map<String, StreamContext> streamContexts = new ConcurrentHashMap<>();

    @Autowired
    private FFmpegGrabberConfig grabberConfig;

    @Autowired
    private FFmpegRecorderConfig recorderConfig;

    @Autowired
    private StreamResourceManager resourceManager;

    /**
     * Initialize the HLS service by cleaning up any leftover files from previous
     * runs
     * This ensures a clean state when the application starts, preventing issues
     * with
     * residual .ts and .m3u8 files from crashed or interrupted sessions
     */
    @PostConstruct
    public void init() {
        logger.info("Initializing HLS Stream Service...");
        resourceManager.cleanupAllStreams();
        logger.info("HLS Stream Service initialized successfully");
    }

    public String StartHLSstream(String RTSPUrl, String streamName) {

        // Check if stream already exists
        if (streamThreads.containsKey(streamName)) {
            return "/api/hls/" + streamName + "/stream.m3u8";
        }

        File outputDir = new File(resourceManager.getHlsRoot(), streamName);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new RuntimeException("Failed to create output directory: " + outputDir.getAbsolutePath());
        }

        StreamContext context = new StreamContext();

        Thread t = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;

            try {
                String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";

                grabber = new FFmpegFrameGrabber(RTSPUrl);
                context.grabber = grabber;

                // Use configuration class for grabber setup
                grabberConfig.configureGrabber(grabber);

                int width = grabber.getImageWidth();
                int height = grabber.getImageHeight();

                recorder = new FFmpegFrameRecorder(hlsOutput, width, height, 0);
                context.recorder = recorder;

                // Use configuration class for recorder setup
                recorderConfig.configureRecorder(recorder, outputDir);

                Frame frame;
                // Check both interrupt flag AND shouldStop flag
                while (!Thread.currentThread().isInterrupted() && !context.shouldStop) {
                    frame = grabber.grabImage();
                    if (frame == null) {
                        break; // End of stream
                    }
                    recorder.record(frame);
                }

            } catch (Exception e) {
                logger.error("Error in stream processing for {}: {}", streamName, e.getMessage(), e);
            } finally {
                logger.info("Cleaning up resources for {}", streamName);
                resourceManager.cleanupResources(context);
                streamContexts.remove(streamName);
                streamThreads.remove(streamName);
            }
        });

        t.setName("HLS-" + streamName);
        t.setDaemon(false); // Ensure thread completes cleanup before JVM shutdown

        // Atomically put context and thread - prevents race condition
        StreamContext existingContext = streamContexts.putIfAbsent(streamName, context);
        Thread existingThread = streamThreads.putIfAbsent(streamName, t);

        if (existingContext != null || existingThread != null) {
            // Another thread won the race, clean up our context
            streamContexts.remove(streamName, context);
            streamThreads.remove(streamName, t);
            return "/api/hls/" + streamName + "/stream.m3u8";
        }

        t.start();
        logger.info("Started stream thread for {}", streamName);

        return "/api/hls/" + streamName + "/stream.m3u8";
    }

    public String stopStream(String streamName) {
        // Get and remove thread first to prevent new operations
        Thread t = streamThreads.remove(streamName);
        StreamContext context = streamContexts.get(streamName);

        if (t == null && context == null) {
            return "Stream not found or already stopped.";
        }

        // Signal thread to stop
        if (context != null) {
            context.shouldStop = true;
        }

        // Interrupt thread if it exists
        if (t != null) {
            t.interrupt();

            try {
                // Wait for thread to finish with timeout
                t.join(5000); // Wait up to 5 seconds

                if (t.isAlive()) {
                    // Force close resources to help thread exit
                    if (context != null) {
                        resourceManager.cleanupResources(context);
                    }
                    // Try one more time with shorter timeout
                    t.join(2000);

                    if (t.isAlive()) {
                        logger.error("Thread {} still alive after forced cleanup. It will be abandoned.",
                                streamName);
                    }
                } else {
                    logger.info("Thread {} stopped gracefully", streamName);
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for thread {} to stop", streamName);
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
        }

        // Remove context if still present (should be removed by thread's finally block)
        streamContexts.remove(streamName);

        // Small delay to ensure OS releases file handles
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Delete stream directory
        resourceManager.deleteStreamDirectory(streamName);
        logger.info("Stream {} stopped and files deleted", streamName);

        return "Stream stopped and files deleted.";
    }
}
