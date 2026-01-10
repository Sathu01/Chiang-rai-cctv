package com.backendcam.backendcam.controller;
import com.backendcam.backendcam.model.dto.StreamRequest;
import com.backendcam.backendcam.service.hls.HLSStreamService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.backendcam.backendcam.model.dto.ListCamera;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.backendcam.backendcam.service.listcamera.ListCameraService;


@RestController
@RequestMapping("/stream")
@CrossOrigin(origins = "*")
public class ListcameraController {
    @Autowired
    private ListCameraService listCameraService;
    
    @GetMapping("/list/{page}")
    public ResponseEntity<List<ListCamera>> GetCameraList(@PathVariable int page){
        try {
            List<ListCamera> cameras = listCameraService.getCameraByPage(page, 10);
            return ResponseEntity.ok(cameras);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }

}
