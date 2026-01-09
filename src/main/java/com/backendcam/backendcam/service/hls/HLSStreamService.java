package com.backendcam.backendcam.service.hls;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HLSStreamService {

    private final Map<String, Thread> streamThreads = new ConcurrentHashMap<>();
    private final Map<String, StreamContext> streamContexts = new ConcurrentHashMap<>();

    @Autowired
    private FFmpegGrabberConfig grabberConfig;

    @Autowired
    private FFmpegRecorderConfig recorderConfig;

    @Autowired
    private StreamResourceManager resourceManager;

    public String StartHLSstream(String RTSPUrl, String streamName) {
        File outputDir = new File(resourceManager.getHlsRoot(), streamName);
        outputDir.mkdirs();

        StreamContext context = new StreamContext();
        streamContexts.put(streamName, context);

        Thread t = new Thread(() -> {
            FFmpegFrameGrabber grabber = null;
            FFmpegFrameRecorder recorder = null;

            try {
                String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";
                // TODO: REDUNDANT - Variables below are never used
                // String playlistPath = "/api/hls/" + streamName + "/stream.m3u8";
                // String HLSoutput = "/hls/" + streamName + "/stream.m3u8";

                grabber = new FFmpegFrameGrabber(RTSPUrl);
                context.grabber = grabber;

                // Use configuration class for grabber setup
                grabberConfig.configureGrabber(grabber, RTSPUrl);

                Frame firstFrame = null;
                int width = 0;
                int height = 0;
                int maxRetries = 70; // Try for ~7 seconds
                for (int i = 0; i < maxRetries && !context.shouldStop; i++) {
                    firstFrame = grabber.grabImage();
                    if (firstFrame != null && firstFrame.imageWidth > 0 && firstFrame.imageHeight > 0) {
                        System.out.println("Got first frame: " + firstFrame.imageWidth + "x" + firstFrame.imageHeight);
                        break;
                    }
                    Thread.sleep(100); // Wait 100ms between attempts
                }

                if (firstFrame == null || firstFrame.imageWidth <= 0 || firstFrame.imageHeight <= 0) {
                    width = 1280;
                    height = 720;
                } else {
                    width = firstFrame.imageWidth;
                    height = firstFrame.imageHeight;

                }

                // int width = grabber.getImageWidth();
                // int height = grabber.getImageHeight();
                
                recorder = new FFmpegFrameRecorder(hlsOutput, width, height, 0);
                context.recorder = recorder;

                // Use configuration class for recorder setup
                recorderConfig.configureRecorder(recorder, outputDir, width, height);

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
                e.printStackTrace();
            } finally {
                // Always cleanup resources in finally block
                resourceManager.cleanupResources(context);
                streamContexts.remove(streamName);
            }
        });

        t.setName(streamName);
        Thread existing = streamThreads.putIfAbsent(streamName, t);

        if (existing != null) {
            streamContexts.remove(streamName);
            // TODO: Consider logging a warning here - stream already exists
            return "/api/hls/" + streamName + "/stream.m3u8";
        }
        t.start();

        return "/api/hls/" + streamName + "/stream.m3u8";
    }

    public String stopStream(String streamName) {
        // Get context and signal stop
        StreamContext context = streamContexts.get(streamName);
        if (context != null) {
            context.shouldStop = true; // Signal the loop to stop

            // Force close FFmpeg resources to unblock grabImage()
            try {
                if (context.recorder != null) {
                    context.recorder.stop();
                }
            } catch (Exception ignored) {
            }

            try {
                if (context.grabber != null) {
                    context.grabber.stop();
                }
            } catch (Exception ignored) {
            }
        }

        // Get and interrupt thread
        Thread t = streamThreads.remove(streamName);
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000); // Wait 5 seconds for clean shutdown
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt(); // Restore interrupt status
            }

            // Force stop if still alive
            if (t.isAlive()) {
                System.err.println("Warning: Thread " + streamName + " did not stop gracefully");
                // Thread will eventually die when finally block executes
            }
        }

        // Small delay to ensure files are fully released
        try {
            Thread.sleep(500); // Give OS time to release file handles
        } catch (InterruptedException ignored) {
        }

        // Now safe to delete files
        resourceManager.deleteStreamDirectory(streamName);
        return "Stream stopped and files deleted.";
    }
}
