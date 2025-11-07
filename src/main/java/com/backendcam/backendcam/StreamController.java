package com.backendcam.backendcam;

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
    public ResponseEntity<Map<String, String>> startHLS(@RequestBody StreamRequest request) {
        if (request.getRtspUrl() == null || request.getStreamName() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "RTSP URL and stream name are required"));
        }
        String hlsUrl = hlsService.startHLSStream(request.getRtspUrl(), request.getStreamName());
        return ResponseEntity.ok(Map.of("message", hlsUrl));
    }

    @PostMapping("/hls/stop/{streamName}")
    public ResponseEntity<Map<String, String>> stopHLS(@PathVariable String streamName) {
        hlsService.stopHLSStream(streamName);
        return ResponseEntity.ok(Map.of("message", "Stream stopped: " + streamName));
    }
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "OK"));
    }

    
}