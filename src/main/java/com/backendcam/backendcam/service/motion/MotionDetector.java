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

    // Motion detection sensitivity (0-100, higher = more sensitive)
    private static final int MOTION_THRESHOLD = 500;      // Minimum pixels changed
    private static final double SENSITIVITY = 25.0;       // Background learning rate
    
    // OpenCV objects
    private BackgroundSubtractorMOG2 backgroundSubtractor;
    private OpenCVFrameConverter.ToMat converterToMat;
    private Mat foregroundMask;
    private Mat morphKernel;
    
    private boolean initialized = false;

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

        // Create background subtractor (MOG2 algorithm)
        // Parameters: history, varThreshold, detectShadows
        backgroundSubtractor = createBackgroundSubtractorMOG2(500, 16, false);
        backgroundSubtractor.setVarThreshold(SENSITIVITY);
        backgroundSubtractor.setDetectShadows(false); // Faster without shadow detection
        
        // Create mask for foreground
        foregroundMask = new Mat();
        
        // Create morphology kernel for noise reduction
        morphKernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        
        initialized = true;
        System.out.println("Motion detector initialized: " + width + "x" + height);
    }

    /**
     * Detect motion in a frame
     * 
     * @param frame Frame from FFmpegFrameGrabber
     * @return true if motion detected, false otherwise
     */
    public boolean detectMotion(Frame frame) {
        if (!initialized) {
            throw new IllegalStateException("Motion detector not initialized. Call initialize() first.");
        }

        if (frame == null || frame.image == null) {
            return false;
        }

        try {
            // Convert JavaCV Frame to OpenCV Mat
            Mat frameMat = converterToMat.convert(frame);
            
            if (frameMat == null || frameMat.empty()) {
                return false;
            }

            // Apply background subtraction
            backgroundSubtractor.apply(frameMat, foregroundMask);

            // Reduce noise with morphological operations
            morphologyEx(foregroundMask, foregroundMask, MORPH_OPEN, morphKernel);
            morphologyEx(foregroundMask, foregroundMask, MORPH_CLOSE, morphKernel);

            // Count non-zero pixels (white = motion)
            int motionPixels = countNonZero(foregroundMask);

            // Return true if motion exceeds threshold
            return motionPixels > MOTION_THRESHOLD;

        } catch (Exception e) {
            System.err.println("Motion detection error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Get motion percentage (0-100)
     * 
     * @param frame Frame to analyze
     * @return Percentage of pixels with motion
     */
    public double getMotionPercentage(Frame frame) {
        if (!initialized || frame == null || frame.image == null) {
            return 0.0;
        }

        try {
            Mat frameMat = converterToMat.convert(frame);
            if (frameMat == null) return 0.0;

            backgroundSubtractor.apply(frameMat, foregroundMask);
            morphologyEx(foregroundMask, foregroundMask, MORPH_OPEN, morphKernel);
            
            int motionPixels = countNonZero(foregroundMask);
            int totalPixels = frameMat.rows() * frameMat.cols();
            
            return (motionPixels * 100.0) / totalPixels;

        } catch (Exception e) {
            return 0.0;
        }
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
        initialized = false;
    }
}