package com.backendcam.backendcam.controller;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.backendcam.backendcam.model.dto.ListCamera;
import org.springframework.web.bind.annotation.GetMapping;
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
