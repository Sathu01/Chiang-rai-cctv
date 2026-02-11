package com.backendcam.backendcam.model;

public class CameraStreamState {
    private String cameraId;
    private String status; // STOPPED | RUNNING
    private int refCount;
    private long lastAccessAt;
    private Object processHandle; // Can be Thread or Process

    public CameraStreamState(String cameraId, String status, int refCount, long lastAccessAt, Object processHandle) {
        this.cameraId = cameraId;
        this.status = status;
        this.refCount = refCount;
        this.lastAccessAt = lastAccessAt;
        this.processHandle = processHandle;
    }

    public String getCameraId() { return cameraId; }
    public void setCameraId(String cameraId) { this.cameraId = cameraId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRefCount() { return refCount; }
    public void setRefCount(int refCount) { this.refCount = refCount; }

    public long getLastAccessAt() { return lastAccessAt; }
    public void setLastAccessAt(long lastAccessAt) { this.lastAccessAt = lastAccessAt; }

    public Object getProcessHandle() { return processHandle; }
    public void setProcessHandle(Object processHandle) { this.processHandle = processHandle; }
}
