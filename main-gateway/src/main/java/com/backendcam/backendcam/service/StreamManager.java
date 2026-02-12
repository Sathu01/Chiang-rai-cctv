package com.backendcam.backendcam.service;

import com.backendcam.backendcam.model.CameraStreamState;
import com.backendcam.backendcam.service.hls.HLSStreamService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StreamManager {

    @Autowired
    private HLSStreamService hlsStreamService;

    private final ConcurrentHashMap<String, CameraStreamState> streamStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> streamRefCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentHashMap<String, Long> cooldownTimers = new ConcurrentHashMap<>();
    private static final long COOLDOWN_PERIOD_MS = 30000; // 30 seconds cooldown

    public synchronized void subscribe(String cameraId, String rtspUrl) {
        streamRefCounts.putIfAbsent(cameraId, new AtomicInteger(0));
        int refCount = streamRefCounts.get(cameraId).incrementAndGet();
        long now = System.currentTimeMillis();
        CameraStreamState state = streamStates.get(cameraId);
        if (state == null) {
            String streamName = "stream-" + cameraId;
            hlsStreamService.startHLSStream(rtspUrl, streamName);
            state = new CameraStreamState(cameraId, "RUNNING", refCount, now, streamName);
            streamStates.put(cameraId, state);
        } else {
            state.setRefCount(refCount);
            state.setLastAccessAt(now);
            state.setStatus("RUNNING");
        }
    }

    public synchronized void unsubscribe(String cameraId) {
        if (streamRefCounts.containsKey(cameraId)) {
            int count = streamRefCounts.get(cameraId).decrementAndGet();
            CameraStreamState state = streamStates.get(cameraId);
            if (state != null) {
                state.setRefCount(count);
                state.setLastAccessAt(System.currentTimeMillis());
            }
            if (count <= 0) {
                long currentTime = System.currentTimeMillis();
                cooldownTimers.put(cameraId, currentTime);
                scheduler.schedule(() -> {
                    if (System.currentTimeMillis() - cooldownTimers.getOrDefault(cameraId, 0L) >= COOLDOWN_PERIOD_MS) {
                        stopStream(cameraId);
                        streamRefCounts.remove(cameraId);
                        cooldownTimers.remove(cameraId);
                    }
                }, COOLDOWN_PERIOD_MS, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void stopStream(String cameraId) {
        CameraStreamState state = streamStates.get(cameraId);
        if (state != null) {
            hlsStreamService.stopHLSStream((String) state.getProcessHandle());
            state.setStatus("STOPPED");
            streamStates.remove(cameraId);
        }
    }

    public CameraStreamState getStreamState(String cameraId) {
        return streamStates.get(cameraId);
    }
}
