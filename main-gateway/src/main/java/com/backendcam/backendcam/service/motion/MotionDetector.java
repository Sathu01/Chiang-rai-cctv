package com.backendcam.backendcam.service.motion;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_video.BackgroundSubtractorMOG2;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_video.*;

/**
 * Motion Detection using JavaCV (OpenCV wrapper)
 * 
 * ✅ Integrates directly with your existing HLS streaming code
 * ✅ Uses the same Frame objects from FFmpegFrameGrabber
 * ✅ Zero conversion overhead
 * 
 * Usage:
 * MotionDetector detector = new MotionDetector();
 * detector.initialize(width, height);
 * 
 * while (streaming) {
 *     Frame frame = grabber.grabImage();
 *     boolean motion = detector.detectMotion(frame);
 *     if (motion) {
 *         // Trigger alert, recording, etc.
 *     }
 * }
 */
@Component
public class MotionDetector {

    // Motion detection sensitivity - TUNED FOR OUTDOOR TRAFFIC CAMERAS
    // For 1920x1080 = 2,073,600 pixels, we need significant motion
    private static final int MOTION_THRESHOLD = 8000;     // Minimum pixels changed (~0.4% of frame)
    private static final double SENSITIVITY = 16.0;       // Lower = less sensitive to small changes
    private static final double MIN_MOTION_PERCENT = 0.3; // Minimum % of frame that must change
    
    // OpenCV objects
    private BackgroundSubtractorMOG2 backgroundSubtractor;
    private OpenCVFrameConverter.ToMat converterToMat;
    private Mat foregroundMask;
    private Mat morphKernel;
    private Mat grayMat;
    private Mat laplacianMat;
    
    private boolean initialized = false;
    private int frameWidth = 0;
    private int frameHeight = 0;

    public MotionDetector() {
        this.converterToMat = new OpenCVFrameConverter.ToMat();
    }

    /**
     * Initialize motion detector
     * Call this once before starting detection
     * 
     * @param width Frame width
     * @param height Frame height
     */
    public void initialize(int width, int height) {
        if (initialized) {
            cleanup();
        }

        this.frameWidth = width;
        this.frameHeight = height;

        // Create background subtractor (MOG2 algorithm)
        // Parameters: history=1000 (longer history for stable background), varThreshold, detectShadows
        backgroundSubtractor = createBackgroundSubtractorMOG2(1000, 25, false);
        backgroundSubtractor.setVarThreshold(SENSITIVITY);
        backgroundSubtractor.setDetectShadows(false); // Faster without shadow detection
        backgroundSubtractor.setNMixtures(5); // More Gaussian mixtures for complex scenes
        
        // Create mask for foreground
        foregroundMask = new Mat();
        
        // Create LARGER morphology kernel for better noise reduction
        morphKernel = getStructuringElement(MORPH_RECT, new Size(5, 5));
        
        // For sharpness calculation
        grayMat = new Mat();
        laplacianMat = new Mat();
        
        initialized = true;
        System.out.println("Motion detector initialized: " + width + "x" + height + 
                         " (threshold: " + MOTION_THRESHOLD + " pixels, " + MIN_MOTION_PERCENT + "%)");
    }

    /**
     * Detect motion in a frame
     * Uses both pixel count AND percentage thresholds to avoid false positives
     * 
     * @param frame Frame from FFmpegFrameGrabber
     * @return true if motion detected, false otherwise
     */
    // Store last motion detection results to avoid redundant calculations
    private int lastMotionPixels = 0;
    private double lastMotionPercent = 0.0;
    
    public boolean detectMotion(Frame frame) {
        if (!initialized) {
            throw new IllegalStateException("Motion detector not initialized. Call initialize() first.");
        }

        if (frame == null || frame.image == null) {
            return false;
        }

        Mat frameMat = null;
        try {
            // Convert JavaCV Frame to OpenCV Mat
            frameMat = converterToMat.convert(frame);
            
            if (frameMat == null || frameMat.empty()) {
                return false;
            }

            // Apply background subtraction
            backgroundSubtractor.apply(frameMat, foregroundMask);

            // Apply threshold to remove weak detections (shadows, noise)
            threshold(foregroundMask, foregroundMask, 200, 255, THRESH_BINARY);

            // Reduce noise with morphological operations (open removes small noise, close fills gaps)
            morphologyEx(foregroundMask, foregroundMask, MORPH_OPEN, morphKernel);
            morphologyEx(foregroundMask, foregroundMask, MORPH_CLOSE, morphKernel);
            
            // Additional erosion to remove edge noise
            erode(foregroundMask, foregroundMask, morphKernel);

            // Count non-zero pixels (white = motion)
            lastMotionPixels = countNonZero(foregroundMask);
            int totalPixels = frameWidth * frameHeight;
            lastMotionPercent = (lastMotionPixels * 100.0) / totalPixels;

            // Must exceed BOTH thresholds (absolute pixel count AND percentage)
            boolean hasMotion = lastMotionPixels > MOTION_THRESHOLD && lastMotionPercent > MIN_MOTION_PERCENT;
            
            return hasMotion;

        } catch (Exception e) {
            System.err.println("Motion detection error: " + e.getMessage());
            return false;
        } finally {
            // CRITICAL: Close Mat to prevent memory leak
            if (frameMat != null) {
                frameMat.close();
            }
        }
    }

    /**
     * Calculate sharpness of a frame using Laplacian variance
     * Higher value = sharper image, lower value = more blur
     * 
     * @param frame Frame to analyze
     * @return Sharpness score (typically 0-1000+, higher is sharper)
     */
    public double calculateSharpness(Frame frame) {
        if (!initialized || frame == null || frame.image == null) {
            return 0.0;
        }

        Mat frameMat = null;
        Mat mean = null;
        Mat stddev = null;
        org.bytedeco.javacpp.indexer.Indexer indexer = null;
        
        try {
            frameMat = converterToMat.convert(frame);
            if (frameMat == null || frameMat.empty()) return 0.0;

            // Convert to grayscale
            cvtColor(frameMat, grayMat, COLOR_BGR2GRAY);
            
            // Calculate Laplacian (edge detection)
            Laplacian(grayMat, laplacianMat, CV_64F);
            
            // Calculate variance of Laplacian (measure of sharpness)
            mean = new Mat();
            stddev = new Mat();
            meanStdDev(laplacianMat, mean, stddev);
            
            indexer = stddev.createIndexer();
            double variance = Math.pow(indexer.getDouble(0), 2);
            
            return variance;

        } catch (Exception e) {
            return 0.0;
        } finally {
            // CRITICAL: Close all resources to prevent memory leak
            if (indexer != null) {
                try { indexer.close(); } catch (Exception ignored) {}
            }
            if (mean != null) mean.close();
            if (stddev != null) stddev.close();
            if (frameMat != null) frameMat.close();
        }
    }

    /**
     * Get motion percentage (0-100)
     * Returns the cached value from the last detectMotion() call.
     * IMPORTANT: Call detectMotion() first, then call this method.
     * 
     * @return Percentage of pixels with motion from last detection
     */
    public double getLastMotionPercentage() {
        return lastMotionPercent;
    }
    
    /**
     * Get motion pixel count from last detection
     * 
     * @return Number of pixels with motion from last detection
     */
    public int getLastMotionPixels() {
        return lastMotionPixels;
    }

    /**
     * Get the foreground mask (for visualization/debugging)
     * Returns a Frame showing detected motion in white
     * 
     * @return Frame with motion mask or null
     */
    public Frame getMotionMask() {
        if (foregroundMask != null && !foregroundMask.empty()) {
            return converterToMat.convert(foregroundMask);
        }
        return null;
    }

    /**
     * Reset background model (call when camera moves or scene changes)
     */
    public void resetBackground() {
        if (backgroundSubtractor != null) {
            backgroundSubtractor.close();
            backgroundSubtractor = createBackgroundSubtractorMOG2(500, 16, false);
            backgroundSubtractor.setVarThreshold(SENSITIVITY);
            backgroundSubtractor.setDetectShadows(false);
        }
    }

    /**
     * Cleanup resources
     */
    public void cleanup() {
        if (backgroundSubtractor != null) {
            backgroundSubtractor.close();
        }
        if (foregroundMask != null) {
            foregroundMask.close();
        }
        if (morphKernel != null) {
            morphKernel.close();
        }
        if (grayMat != null) {
            grayMat.close();
        }
        if (laplacianMat != null) {
            laplacianMat.close();
        }
        initialized = false;
    }
}