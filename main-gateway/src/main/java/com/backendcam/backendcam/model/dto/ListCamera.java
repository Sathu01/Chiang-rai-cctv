package com.backendcam.backendcam.model.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ListCamera {
    private String id;
    private String name;
    private String latlong;
    private String address;
    private String status;
   
}
