package com.backendcam.backendcam.service.hls;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Manages cleanup of stream resources and directories
 */
@Component
class StreamResourceManager {

    private static final String HLS_ROOT = "hls";

    /**
     * Clean up FFmpeg resources for a stream
     * 
     * @param context The stream context containing resources to clean up
     */
    public void cleanupResources(StreamContext context) {
        if (context == null) {
            return;
        }

        if (context.recorder != null) {
            try {
                context.recorder.stop();
                context.recorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (context.grabber != null) {
            try {
                context.grabber.stop();
                context.grabber.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Delete the stream directory and all its contents
     * 
     * @param streamName The name of the stream
     */
    public void deleteStreamDirectory(String streamName) {
        Path streamDir = Paths.get(HLS_ROOT, streamName);

        if (!Files.exists(streamDir)) {
            return;
        }

        try {
            Files.walk(streamDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            System.err.println("Failed to delete: " + path + " - " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getHlsRoot() {
        return HLS_ROOT;
    }
}
