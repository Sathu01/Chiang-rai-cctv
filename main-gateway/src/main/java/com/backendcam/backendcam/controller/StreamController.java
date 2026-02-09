package com.backendcam.backendcam.controller;

import com.backendcam.backendcam.model.dto.StreamRequest;
import com.backendcam.backendcam.service.hls.HLSStreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/stream")
@CrossOrigin(origins = "*")
public class StreamController {

    @Autowired
    private HLSStreamService hlsService;

    @PostMapping("/hls/start")
    public ResponseEntity<Map<String, String>> startStream(@RequestBody StreamRequest request) {
        // Input validation
        if (request.getRtspUrl() == null || request.getRtspUrl().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "RTSP URL cannot be null or empty"));
        }
        if (request.getStreamName() == null || request.getStreamName().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stream name cannot be null or empty"));
        }

        // Sanitize stream name to prevent directory traversal
        String sanitizedStreamName = request.getStreamName().replaceAll("[^a-zA-Z0-9_-]", "_");

        String hlsUrl = hlsService.StartHLSstream(request.getRtspUrl(), sanitizedStreamName);
        return ResponseEntity.ok(Map.of("message", hlsUrl));
    }

    @PostMapping("/hls/stop/{streamName}")
    public ResponseEntity<Map<String, String>> stopStream(@PathVariable String streamName) {
        if (streamName == null || streamName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Stream name cannot be null or empty"));
        }

        // Sanitize stream name consistently
        String sanitizedStreamName = streamName.replaceAll("[^a-zA-Z0-9_-]", "_");

        hlsService.stopStream(sanitizedStreamName);
        return ResponseEntity.ok(Map.of("message", "Stream stopped: " + sanitizedStreamName));
    }

}
