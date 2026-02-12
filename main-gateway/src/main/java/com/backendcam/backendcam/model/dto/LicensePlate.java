package com.backendcam.backendcam.model.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
public class LicensePlate {
    private String licensePlate;
    private String urlImage;
    private LocalDateTime dateTime;
    private String cameraId;
}
