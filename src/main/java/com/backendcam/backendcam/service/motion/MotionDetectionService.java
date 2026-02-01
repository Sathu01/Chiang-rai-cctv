package com.backendcam.backendcam.service.motion;

import com.backendcam.backendcam.model.dto.MotionEvent;
import com.backendcam.backendcam.service.hls.FFmpegGrabberConfig;
import com.backendcam.backendcam.service.kafka.MotionEventProducer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Service for managing motion detection on camera streams
 */
@Service
public class MotionDetectionService {

    @Autowired
    private FFmpegGrabberConfig grabberConfig;

    @Autowired
    private SaveMotionFrameService saveMotionFrameService;

    @Autowired
    private MotionEventProducer motionEventProducer;

    // Track active detection sessions
    private final Map<String, DetectionSession> activeSessions = new ConcurrentHashMap<>();
    
    // Thread pool for handling multiple camera streams
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    
    
    // Number of frames to skip at start to ensure keyframe is received
    private static final int WARMUP_FRAMES = 30;
    
    // COOLDOWN DISABLED: Save every motion event immediately
    private static final int MOTION_COOLDOWN_SECONDS = 0;
    
    // FRAME BUFFER: Collect frames during motion and pick sharpest one
    private static final int MOTION_FRAME_BUFFER_SIZE = 5;
    private static final double MIN_SHARPNESS_THRESHOLD = 50.0; // Reject very blurry frames


     /**
     * Start motion detection for a camera
     * 
     * @param cameraId Unique camera identifier
     * @param url RTSP stream URL
     * @return true if started successfully, false if already running
     */
    public boolean startDetection(String cameraId, String url) {
    return startDetection(cameraId, url, 3);
}

    /**
     * Start motion detection for a camera
     * 
     * @param cameraId Unique camera identifier
     * @param url RTSP stream URL
     * @param checkIntervalSeconds Interval between frame checks (recommended: 3-4 seconds)
     * @return true if started successfully, false if already running
     */
    public boolean startDetection(String cameraId, String url, int checkIntervalSeconds) {
        // Check if already running
        if (activeSessions.containsKey(cameraId)) {
            return false;
        }

        // Create new session
        DetectionSession session = new DetectionSession(cameraId, url, checkIntervalSeconds);
        activeSessions.put(cameraId, session);
        
        // Start detection loop in separate thread
        Future<?> future = executorService.submit(() -> runDetectionLoop(session));
        session.setFuture(future);
        
        return true;
    }

    /**
     * Stop motion detection for a camera
     * 
     * @param cameraId Camera identifier
     * @return Statistics map or null if camera not found
     */
    public Map<String, Object> stopDetection(String cameraId) {
        DetectionSession session = activeSessions.get(cameraId);
        
        if (session == null) {
            return null;
        }
        
        // Stop the session
        session.stop();
        activeSessions.remove(cameraId);
        
        // Return statistics
        Map<String, Object> stats = new HashMap<>();
        stats.put("framesChecked", session.getFramesChecked());
        stats.put("motionDetected", session.getMotionCount());
        stats.put("duration", session.getDuration());
        
        return stats;
    }

    /**
     * Get status of all active detections
     * 
     * @return Map of camera IDs to their status information
     */
    public Map<String, Object> getActiveDetections() {
        Map<String, Object> cameras = new HashMap<>();
        
        for (Map.Entry<String, DetectionSession> entry : activeSessions.entrySet()) {
            DetectionSession session = entry.getValue();
            Map<String, Object> info = new HashMap<>();
            
            info.put("cameraId", session.getCameraId());
            info.put("url", session.getUrl());
            info.put("running", session.isRunning());
            info.put("framesChecked", session.getFramesChecked());
            info.put("motionDetected", session.getMotionCount());
            info.put("lastCheck", session.getLastCheckTime());
            info.put("checkInterval", session.getCheckIntervalSeconds() + "s");
            info.put("startTime", session.getStartTime());
            
            cameras.put(entry.getKey(), info);
        }
        
        return cameras;
    }

    /**
     * Deep copy a frame by converting to BufferedImage and back
     * This ensures the image data is completely independent
     */
    private BufferedImage deepCopyFrameToImage(Frame frame) {
        if (frame == null || frame.image == null) {
            return null;
        }
        
        // Create a NEW converter for each conversion to avoid buffer sharing
        Java2DFrameConverter converter = new Java2DFrameConverter();
        BufferedImage original = converter.convert(frame);
        
        if (original == null) {
            return null;
        }
        
        // Create a completely new BufferedImage with copied data
        BufferedImage copy = new BufferedImage(
            original.getWidth(), 
            original.getHeight(), 
            BufferedImage.TYPE_3BYTE_BGR  // Force RGB format
        );
        copy.getGraphics().drawImage(original, 0, 0, null);
        
        return copy;
    }

   
    /**
     * Main detection loop - runs continuously checking for motion
     * Uses cooldown and sharpest-frame selection to improve capture quality
     */
    private void runDetectionLoop(DetectionSession session) {
        FFmpegFrameGrabber grabber = null;
        MotionDetector detector = null;
        
        try {
            System.out.println("🎥 Starting motion detection for: " + session.getCameraId());
            
            // Initialize video stream grabber with motion detection config (proper frame quality)
            grabber = new FFmpegFrameGrabber(session.getUrl());
            grabberConfig.configureGrabberForMotionDetection(grabber);
            
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            
            // Initialize motion detector
            detector = new MotionDetector();
            detector.initialize(width, height);
            
            System.out.println("✓ Motion detector initialized: " + width + "x" + height);
            System.out.println("✓ No cooldown - saving every motion event");
            
            // WARMUP: Skip initial frames to ensure we have a proper keyframe (I-frame)
            System.out.println("⏳ Warming up - waiting for keyframe (" + WARMUP_FRAMES + " frames)...");
            for (int i = 0; i < WARMUP_FRAMES && session.isRunning(); i++) {
                Frame warmupFrame = grabber.grabImage();
                if (warmupFrame != null) {
                    // Feed to detector to build background model
                    detector.detectMotion(warmupFrame);
                    warmupFrame.close();
                }
            }
            System.out.println("✓ Warmup complete - background model initialized");
            
            // Frame buffer for sharpest frame selection
            BufferedImage sharpestImage = null;
            double bestSharpness = 0;
            int consecutiveMotionFrames = 0;
            
            // Main detection loop
            while (session.isRunning()) {
                try {
                    // Grab frame from stream
                    Frame frame = grabber.grabImage();
                    
                    if (frame != null && frame.image != null) {
                        session.incrementFramesChecked();
                        long timestamp = System.currentTimeMillis();
                        
                        // Detect motion in frame
                        boolean hasMotion = detector.detectMotion(frame);
                        double motionPercent = detector.getMotionPercentage(frame);
                        
                        if (hasMotion) {
                            consecutiveMotionFrames++;
                            
                            // Check if we can save (cooldown)
                            if (session.canSaveMotion()) {
                                // Calculate sharpness
                                double sharpness = detector.calculateSharpness(frame);
                                
                                System.out.println("🔴 MOTION - Camera: " + session.getCameraId() + 
                                                 " (" + String.format("%.1f%%", motionPercent) + 
                                                 ", sharpness: " + String.format("%.0f", sharpness) + ")");
                                
                                // Collect frames to find sharpest one
                                if (sharpness > bestSharpness && sharpness >= MIN_SHARPNESS_THRESHOLD) {
                                    bestSharpness = sharpness;
                                    sharpestImage = deepCopyFrameToImage(frame);
                                }
                                
                                // After collecting enough frames OR motion stops, save the sharpest
                                if (consecutiveMotionFrames >= MOTION_FRAME_BUFFER_SIZE) {
                                    if (sharpestImage != null && bestSharpness >= MIN_SHARPNESS_THRESHOLD) {
                                        session.incrementMotionCount();
                                        
                                        // Upload the sharpest frame
                                        String imageUrl = saveMotionFrameService.uploadMotionFrame(
                                            sharpestImage,
                                            session.getCameraId()
                                        );
                                        
                                        // Send event to Kafka
                                        if (imageUrl != null) {
                                            MotionEvent event = new MotionEvent(
                                                session.getCameraId(),
                                                timestamp,
                                                imageUrl,
                                                String.format("%.1f%% motion, sharpness: %.0f", motionPercent, bestSharpness)
                                            );
                                            
                                            motionEventProducer.send(event);
                                            session.setLastMotionSavedTime(LocalDateTime.now());
                                            
                                            System.out.println("✓ Motion saved - Sharpness: " + 
                                                             String.format("%.0f", bestSharpness) + " - " + imageUrl);
                                        }
                                    } else {
                                        System.out.println("⚠️ Skipped save - all frames too blurry (best: " + 
                                                         String.format("%.0f", bestSharpness) + ")");
                                    }
                                    
                                    // Reset buffer
                                    sharpestImage = null;
                                    bestSharpness = 0;
                                    consecutiveMotionFrames = 0;
                                }
                            }
                        } else {
                            // No motion - if we had motion frames buffered, save the best one
                            if (consecutiveMotionFrames > 0 && sharpestImage != null && 
                                bestSharpness >= MIN_SHARPNESS_THRESHOLD && session.canSaveMotion()) {
                                
                                session.incrementMotionCount();
                                
                                String imageUrl = saveMotionFrameService.uploadMotionFrame(
                                    sharpestImage,
                                    session.getCameraId()
                                );
                                
                                if (imageUrl != null) {
                                    MotionEvent event = new MotionEvent(
                                        session.getCameraId(),
                                        timestamp,
                                        imageUrl,
                                        String.format("Motion ended, sharpness: %.0f", bestSharpness)
                                    );
                                    
                                    motionEventProducer.send(event);
                                    session.setLastMotionSavedTime(LocalDateTime.now());
                                    
                                    System.out.println("✓ Motion ended - saved sharpest frame: " + imageUrl);
                                }
                            }
                            
                            // Reset buffer
                            sharpestImage = null;
                            bestSharpness = 0;
                            consecutiveMotionFrames = 0;
                        }
                        
                        frame.close();
                    }
                    
                    session.updateLastCheckTime();
                    
                    // Wait for next check interval (recommended: 3-4 seconds)
                    Thread.sleep(session.getCheckIntervalSeconds() * 1000L);
                    
                } catch (InterruptedException e) {
                    System.out.println("⚠️ Detection interrupted for: " + session.getCameraId());
                    break;
                } catch (Exception e) {
                    System.err.println("❌ Error processing frame: " + e.getMessage());
                    // Continue detection despite errors
                }
            }
            
        } catch (Exception e) {
            System.err.println("❌ Fatal error in detection: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cleanup resources
            if (detector != null) {
                detector.cleanup();
                System.out.println("✓ Detector cleaned up for: " + session.getCameraId());
            }
            if (grabber != null) {
                try {
                    grabber.stop();
                    System.out.println("✓ Grabber stopped for: " + session.getCameraId());
                } catch (Exception ignored) {}
            }
            
            session.stop();
            System.out.println("🛑 Detection stopped for: " + session.getCameraId());
        }
    }

    /**
     * Inner class to track detection session state
     */
    private static class DetectionSession {
        private final String cameraId;
        private final String url;
        private final int checkIntervalSeconds;
        private final LocalDateTime startTime;
        private volatile boolean running = true;
        private int framesChecked = 0;
        private int motionCount = 0;
        private LocalDateTime lastCheckTime;
        private LocalDateTime lastMotionSavedTime; // For cooldown
        private Future<?> future;

        public DetectionSession(String cameraId, String url, int checkIntervalSeconds) {
            this.cameraId = cameraId;
            this.url = url;
            this.checkIntervalSeconds = checkIntervalSeconds;
            this.startTime = LocalDateTime.now();
            this.lastCheckTime = LocalDateTime.now();
        }

        public void stop() {
            running = false;
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }

        public synchronized void incrementFramesChecked() { 
            framesChecked++; 
        }
        
        public synchronized void incrementMotionCount() { 
            motionCount++; 
        }
        
        public synchronized void updateLastCheckTime() { 
            lastCheckTime = LocalDateTime.now(); 
        }

        public String getDuration() {
            long seconds = java.time.Duration.between(startTime, LocalDateTime.now()).getSeconds();
            long minutes = seconds / 60;
            long hours = minutes / 60;
            
            if (hours > 0) {
                return String.format("%dh %dm", hours, minutes % 60);
            } else if (minutes > 0) {
                return String.format("%dm %ds", minutes, seconds % 60);
            } else {
                return String.format("%ds", seconds);
            }
        }

        // Getters
        public String getCameraId() { return cameraId; }
        public String getUrl() { return url; }
        public int getCheckIntervalSeconds() { return checkIntervalSeconds; }
        public boolean isRunning() { return running; }
        public int getFramesChecked() { return framesChecked; }
        public int getMotionCount() { return motionCount; }
        public LocalDateTime getLastCheckTime() { return lastCheckTime; }
        public LocalDateTime getStartTime() { return startTime; }
        public void setFuture(Future<?> future) { this.future = future; }
        
        public LocalDateTime getLastMotionSavedTime() { return lastMotionSavedTime; }
        public synchronized void setLastMotionSavedTime(LocalDateTime time) { this.lastMotionSavedTime = time; }
        
        public boolean canSaveMotion() {
            if (lastMotionSavedTime == null) return true;
            long secondsSinceLastSave = java.time.Duration.between(lastMotionSavedTime, LocalDateTime.now()).getSeconds();
            return secondsSinceLastSave >= MOTION_COOLDOWN_SECONDS;
        }
    }
}