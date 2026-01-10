package com.backendcam.backendcam.model.dto;

public class ListCamera {
    private String id;
    private String name;
    private String latlong;
    private String address;
    private String status;
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getLatlong() {
        return latlong;
    }
    public void setLatlong(String latlong) {
        this.latlong = latlong;
    }
    public String getAddress() {
        return address;
    }
    public void setAddress(String address) {
        this.address = address;
    }
    public String getStatus() {
        return status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
}
