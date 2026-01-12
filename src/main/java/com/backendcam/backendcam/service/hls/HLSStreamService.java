package com.backendcam.backendcam.service.hls;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.bytedeco.ffmpeg.global.avutil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HLSStreamService {

    // ⚙️ CONFIGURATION: Change this to adjust output FPS (5, 8, 10, 15, etc.)
    private static final int TARGET_OUTPUT_FPS = 10;
    
    private final Map<String, Thread> streamThreads = new ConcurrentHashMap<>();
    private final Map<String, StreamContext> streamContexts = new ConcurrentHashMap<>();

    @Autowired
    private FFmpegGrabberConfig grabberConfig;

    @Autowired
    private FFmpegRecorderConfig recorderConfig;

    @Autowired
    private StreamResourceManager resourceManager;

    public HLSStreamService() {
        // Suppress FFmpeg logging
        FFmpegLogCallback.set();
        avutil.av_log_set_level(avutil.AV_LOG_FATAL);
    }

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

                grabber = new FFmpegFrameGrabber(RTSPUrl);
                context.grabber = grabber;

                // Configure grabber
                grabberConfig.configureGrabber(grabber, RTSPUrl);

                // Get first frame for dimensions
                Frame firstFrame = null;
                int width = 0;
                int height = 0;
                int maxRetries = 70;
                
                for (int i = 0; i < maxRetries && !context.shouldStop; i++) {
                    firstFrame = grabber.grabImage();
                    if (firstFrame != null && firstFrame.imageWidth > 0 && firstFrame.imageHeight > 0) {
                        width = firstFrame.imageWidth;
                        height = firstFrame.imageHeight;
                        firstFrame.close();
                        break;
                    }
                    Thread.sleep(100);
                }

                if (width <= 0 || height <= 0) {
                    width = 1280;
                    height = 720;
                }

                // Setup recorder with TARGET_OUTPUT_FPS
                recorder = new FFmpegFrameRecorder(hlsOutput, width, height, 0);
                context.recorder = recorder;

                // Configure recorder - it will use TARGET_OUTPUT_FPS internally
                recorderConfig.configureRecorder(recorder, outputDir, width, height, TARGET_OUTPUT_FPS);

                // Get source FPS from camera
                double sourceFps = grabber.getFrameRate();
                if (sourceFps <= 0 || sourceFps > 60) {
                    sourceFps = 25.0; // Default assumption for IP cameras
                }
                
                // Calculate how many frames to skip
                // Example: 25fps source → 10fps target = skip ratio of 2.5 (encode every ~3rd frame)
                int frameSkipRatio = Math.max(1, (int) Math.round(sourceFps / TARGET_OUTPUT_FPS));
                
                System.out.println("Stream " + streamName + 
                                 " - Source: " + sourceFps + "fps → Target: " + TARGET_OUTPUT_FPS + 
                                 "fps (encoding 1 out of every " + frameSkipRatio + " frames)");

                // Stream with frame skipping to achieve target FPS
                streamFramesWithSkipping(grabber, recorder, streamName, context, sourceFps, frameSkipRatio);
                
                System.out.println("Stream " + streamName + " ended normally");

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                resourceManager.cleanupResources(context);
                streamContexts.remove(streamName);
            }
        });

        t.setName(streamName);
        Thread existing = streamThreads.putIfAbsent(streamName, t);

        if (existing != null) {
            streamContexts.remove(streamName);
            return "/api/hls/" + streamName + "/stream.m3u8";
        }
        
        t.start();
        return "/api/hls/" + streamName + "/stream.m3u8";
    }

    /**
     * ✅ Stream frames with proper pacing and selective encoding
     * 
     * This method:
     * 1. Reads frames at camera speed (e.g., 25fps = every 40ms)
     * 2. Only encodes selected frames based on frameSkipRatio
     * 3. Creates consistent HLS segment timing
     */
    private void streamFramesWithSkipping(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder,
                                         String streamName, StreamContext context, 
                                         double sourceFps, int frameSkipRatio) {
        
        final int MAX_NULL_FRAMES = 500;
        final long FRAME_TIME_MS = (long)(1000.0 / sourceFps);
        
        int consecutiveNullFrames = 0;
        int frameCounter = 0;
        int encodedFrames = 0;
        long lastFrameTime = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();

        System.out.println("Frame timing: Read every " + FRAME_TIME_MS + "ms, encode every " + frameSkipRatio + "th frame");

        while (!Thread.currentThread().isInterrupted() && !context.shouldStop) {
            Frame frame = null;
            
            try {
                long now = System.currentTimeMillis();
                
                // ✅ CRITICAL: Pace frame reading to match camera speed
                long timeSinceLastFrame = now - lastFrameTime;
                if (timeSinceLastFrame < FRAME_TIME_MS) {
                    Thread.sleep(FRAME_TIME_MS - timeSinceLastFrame);
                }
                lastFrameTime = System.currentTimeMillis();

                // Read frame from camera
                frame = grabber.grabImage();

                // ✅ Handle null frames properly
                if (frame == null) {
                    consecutiveNullFrames++;

                    if (consecutiveNullFrames >= MAX_NULL_FRAMES) {
                        System.out.println("Stream " + streamName + " stalled - " + consecutiveNullFrames + " null frames");
                        break;
                    }
                    
                    Thread.sleep(consecutiveNullFrames < 10 ? 5 : 
                                consecutiveNullFrames < 100 ? 10 : 
                                consecutiveNullFrames < 300 ? 20 : 50);
                    continue;
                }

                consecutiveNullFrames = 0;
                frameCounter++;

                // Validate frame before encoding
                if (frame.image == null || frame.imageWidth <= 0 || frame.imageHeight <= 0) {
                    frame.close();
                    frame = null;
                    Thread.sleep(5);
                    continue;
                }

                // ✅ Selective encoding: Only encode every Nth frame
                boolean shouldEncode = (frameCounter % frameSkipRatio == 0);

                if (shouldEncode) {
                    try {
                        recorder.record(frame);
                        encodedFrames++;
                        
                        // Log every 100 encoded frames
                        if (encodedFrames % 100 == 0) {
                            long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                            double actualFps = encodedFrames / (double)elapsed;
                            System.out.println(streamName + ": Read " + frameCounter + " frames, " +
                                             "Encoded " + encodedFrames + " @ " + 
                                             String.format("%.1f", actualFps) + " fps");
                        }
                    } catch (Exception e) {
                        // Continue on encoding errors
                    }
                }

                // ✅ CRITICAL: Always close frame immediately after use
                frame.close();
                frame = null;

            } catch (Exception e) {
                // Continue streaming on errors
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    break;
                }
                
            } finally {
                // ✅ Safety net: Ensure frame is closed
                if (frame != null) {
                    try {
                        frame.close();
                    } catch (Exception ignored) {}
                    frame = null;
                }
            }
        }
        
        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        double avgFps = totalTime > 0 ? encodedFrames / (double)totalTime : 0;
        System.out.println(streamName + ": Finished - Read " + frameCounter + 
                         " frames, Encoded " + encodedFrames + " in " + totalTime + 
                         "s (avg " + String.format("%.1f", avgFps) + " fps)");
    }

    public String stopStream(String streamName) {
        StreamContext context = streamContexts.get(streamName);
        if (context != null) {
            context.shouldStop = true;

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

        Thread t = streamThreads.remove(streamName);
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {}

        resourceManager.deleteStreamDirectory(streamName);
        return "Stream stopped and files deleted.";
    }
}