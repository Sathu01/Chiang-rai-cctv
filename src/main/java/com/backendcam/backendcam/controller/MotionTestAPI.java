package com.backendcam.backendcam.controller;

import com.backendcam.backendcam.service.hls.FFmpegGrabberConfig;
import com.backendcam.backendcam.service.motion.MotionDetector;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.backendcam.backendcam.service.motion.SaveMotionFrameService;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple Motion Detection Test API
 * Uses your existing FFmpegGrabberConfig
 * 
 * Test with:
 * GET http://localhost:8080/api/motion/test?url=rtsp://admin:pass@192.168.1.100/stream
 */
@RestController
@RequestMapping("/motion")
@CrossOrigin(origins = "*")
public class MotionTestAPI {

    @Autowired
    private FFmpegGrabberConfig grabberConfig;

    @Autowired
    private SaveMotionFrameService saveMotionFrameService;

    /**
     * Test motion detection on RTSP stream
     * 
     * GET /api/motion/test?url=rtsp://...&frames=10
     * 
     * @param url RTSP URL
     * @param frames Number of frames to test (default: 10)
     * @return Motion detection results
     */
    @GetMapping("/test")
    public Map<String, Object> testMotion(
            @RequestParam String url,
            @RequestParam(defaultValue = "3") int frames) {
        
        Map<String, Object> result = new HashMap<>();
        FFmpegFrameGrabber grabber = null;
        MotionDetector detector = null;

        try {
            System.out.println("Starting motion test for: " + url);
            
            // 1. Create and configure grabber with YOUR settings
            grabber = new FFmpegFrameGrabber(url);
            grabberConfig.configureGrabber(grabber);
            
            System.out.println("✓ Grabber configured and started");

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double fps = grabber.getFrameRate();

            System.out.println("Stream info: " + width + "x" + height + " @ " + fps + " fps");

            // 2. Initialize motion detector
            detector = new MotionDetector();
            detector.initialize(width, height);
            
            System.out.println("✓ Motion detector initialized");

            // 3. Test frames for motion
            int motionCount = 0;
            double totalMotionPercent = 0;
            int validFrames = 0;

            System.out.println("Testing " + frames + " frames...");

            for (int i = 0; i < frames; i++) {
                Frame frame = grabber.grabImage();
                
                if (frame != null && frame.image != null) {
                    boolean hasMotion = detector.detectMotion(frame);
                    double motionPercent = detector.getMotionPercentage(frame);
                    
                    if (hasMotion) {
                        motionCount++;
                        System.out.println("  Frame " + (i+1) + ": 🔴 MOTION (" + 
                                         String.format("%.1f%%", motionPercent) + ")");
                        String imageUrl = saveMotionFrameService.uploadMotionFrame(frame,"test-camera");
                        
                    } else {
                        System.out.println("  Frame " + (i+1) + ": ✓ No motion (" + 
                                         String.format("%.1f%%", motionPercent) + ")");
                    }
                    
                    totalMotionPercent += motionPercent;
                    validFrames++;
                    
                    frame.close();
                }
                
                // Small delay between frames
                Thread.sleep(100);
            }

            System.out.println("✓ Test complete");

            // 4. Build response
            result.put("success", true);
            result.put("url", url);
            result.put("resolution", width + "x" + height);
            result.put("fps", fps);
            result.put("framesTested", validFrames);
            result.put("framesWithMotion", motionCount);
            result.put("motionDetected", motionCount > 0);
            result.put("averageMotionPercentage", validFrames > 0 ? totalMotionPercent / validFrames : 0);
            result.put("motionRate", validFrames > 0 ? (motionCount * 100.0 / validFrames) : 0);
            
            // Add summary message
            if (motionCount > 0) {
                result.put("message", "⚠️ Motion detected in " + motionCount + " out of " + validFrames + " frames");
            } else {
                result.put("message", "✓ No motion detected in " + validFrames + " frames");
            }

        } catch (Exception e) {
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
        } finally {
            // Cleanup
            if (detector != null) {
                detector.cleanup();
                System.out.println("✓ Detector cleaned up");
            }
            if (grabber != null) {
                try { 
                    grabber.stop();
                    System.out.println("✓ Grabber stopped");
                } catch (Exception ignored) {}
            }
        }

        return result;
    }

    /**
     * Quick test - just check if motion detection is working
     * 
     * GET /api/motion/quick?url=rtsp://...
     * 
     * @param url RTSP URL
     * @return Simple yes/no motion result
     */
    @GetMapping("/quick")
    public Map<String, Object> quickTest(@RequestParam String url) {
        Map<String, Object> result = new HashMap<>();
        FFmpegFrameGrabber grabber = null;
        MotionDetector detector = null;

        try {
            // Connect
            grabber = new FFmpegFrameGrabber(url);
            grabberConfig.configureGrabber(grabber);

            // Initialize detector
            detector = new MotionDetector();
            detector.initialize(grabber.getImageWidth(), grabber.getImageHeight());

            // Test just 3 frames
            boolean anyMotion = false;
            for (int i = 0; i < 3; i++) {
                Frame frame = grabber.grabImage();
                if (frame != null && frame.image != null) {
                    if (detector.detectMotion(frame)) {
                        anyMotion = true;
                    }
                    frame.close();
                }
            }

            result.put("success", true);
            result.put("motionDetected", anyMotion);
            result.put("message", anyMotion ? "🔴 Motion detected!" : "✓ No motion");

        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        } finally {
            if (detector != null) detector.cleanup();
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
            }
        }

        return result;
    }


}