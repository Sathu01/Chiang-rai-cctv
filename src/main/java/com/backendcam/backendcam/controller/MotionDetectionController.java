package com.backendcam.backendcam.controller;

import com.backendcam.backendcam.service.motion.MotionDetectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Motion Detection Controller
 * 
 * Endpoints:
 * - POST /motion/start - Start motion detection for a camera
 * - POST /motion/stop - Stop motion detection for a camera
 * - GET /motion/status - Get status of all active cameras
 */
@RestController
@RequestMapping("/motion")
@CrossOrigin(origins = "*")
public class MotionDetectionController {

    @Autowired
    private MotionDetectionService motionDetectionService;

    /**
     * Start motion detection for a camera
     * 
     * POST /motion/start
     * {
     *   "cameraId": "camera-1",
     *   "url": "rtsp://admin:pass@192.168.1.100/stream",
     *   "checkIntervalSeconds": 1
     * }
     */
    @PostMapping("/start")
    public Map<String, Object> startMotionDetection(@RequestBody StartRequest request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            boolean started = motionDetectionService.startDetection(
                request.getCameraId(),
                request.getUrl(),
                request.getCheckIntervalSeconds()
            );
            
            if (started) {
                response.put("success", true);
                response.put("message", "Motion detection started for camera: " + request.getCameraId());
                response.put("cameraId", request.getCameraId());
                response.put("url", request.getUrl());
                response.put("checkInterval", request.getCheckIntervalSeconds() + " seconds");
            } else {
                response.put("success", false);
                response.put("message", "Camera " + request.getCameraId() + " is already running");
            }
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return response;
    }

    /**
     * Stop motion detection for a camera
     * 
     * POST /motion/stop
     * {
     *   "cameraId": "camera-1"
     * }
     */
    @PostMapping("/stop")
    public Map<String, Object> stopMotionDetection(@RequestBody StopRequest request) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, Object> stats = motionDetectionService.stopDetection(request.getCameraId());
            
            if (stats != null) {
                response.put("success", true);
                response.put("message", "Motion detection stopped for camera: " + request.getCameraId());
                response.put("cameraId", request.getCameraId());
                response.put("statistics", stats);
            } else {
                response.put("success", false);
                response.put("message", "No active detection for camera: " + request.getCameraId());
            }
            
        } catch (Exception e) {
            response.put("success", false);
            response.put("error", e.getMessage());
        }
        
        return response;
    }

    /**
     * Get status of all active cameras
     * 
     * GET /motion/status
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> cameras = motionDetectionService.getActiveDetections();
        
        response.put("activeCameras", cameras.size());
        response.put("cameras", cameras);
        
        return response;
    }

    /**
     * Request DTOs
     */
    public static class StartRequest {
        private String cameraId;
        private String url;
        private int checkIntervalSeconds = 1; // default 1 second

        public String getCameraId() { return cameraId; }
        public void setCameraId(String cameraId) { this.cameraId = cameraId; }
        
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        
        public int getCheckIntervalSeconds() { return checkIntervalSeconds; }
        public void setCheckIntervalSeconds(int checkIntervalSeconds) { 
            this.checkIntervalSeconds = checkIntervalSeconds; 
        }
    }

    public static class StopRequest {
        private String cameraId;

        public String getCameraId() { return cameraId; }
        public void setCameraId(String cameraId) { this.cameraId = cameraId; }
    }
}