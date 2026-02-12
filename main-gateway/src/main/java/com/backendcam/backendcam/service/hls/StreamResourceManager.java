package com.backendcam.backendcam.service.hls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class StreamResourceManager {

    private static final Logger logger = LoggerFactory.getLogger(StreamResourceManager.class);
    private static final String HLS_ROOT = "hls";

    /**
     * Clean up FFmpeg resources for a stream
     * This method ensures all resources are properly released even if errors occur
     * 
     * @param context The stream context containing resources to clean up
     */
    public void cleanupResources(StreamContext context) {
        if (context == null) {
            logger.warn("Attempted to cleanup null context");
            return;
        }

        // Clean up recorder
        if (context.recorder != null) {
            try {
                context.recorder.stop();
                logger.debug("Recorder stopped successfully");
            } catch (Exception e) {
                logger.warn("Error stopping recorder: {}", e.getMessage());
            }

            try {
                context.recorder.release();
                logger.debug("Recorder released successfully");
            } catch (Exception e) {
                logger.error("Error releasing recorder: {}", e.getMessage(), e);
            } finally {
                context.recorder = null; // Help GC and prevent double-release
            }
        }

        // Clean up grabber
        if (context.grabber != null) {
            try {
                context.grabber.stop();
                logger.debug("Grabber stopped successfully");
            } catch (Exception e) {
                logger.warn("Error stopping grabber: {}", e.getMessage());
            }

            try {
                context.grabber.release();
                logger.debug("Grabber released successfully");
            } catch (Exception e) {
                logger.error("Error releasing grabber: {}", e.getMessage(), e);
            } finally {
                context.grabber = null; // Help GC and prevent double-release
            }
        }

        logger.info("Resource cleanup completed");
    }

    /**
     * Delete the stream directory and all its contents
     * Uses a safe, defensive approach to handle file deletion failures
     * 
     * @param streamName The name of the stream
     */
    public void deleteStreamDirectory(String streamName) {
        if (streamName == null || streamName.trim().isEmpty()) {
            logger.warn("Attempted to delete directory with null or empty stream name");
            return;
        }

        Path streamDir = Paths.get(HLS_ROOT, streamName);

        if (!Files.exists(streamDir)) {
            logger.debug("Stream directory does not exist: {}", streamDir);
            return;
        }

        if (!Files.isDirectory(streamDir)) {
            logger.warn("Path exists but is not a directory: {}", streamDir);
            return;
        }

        logger.info("Deleting stream directory: {}", streamDir);

        try {
            Files.walk(streamDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            boolean deleted = Files.deleteIfExists(path);
                            if (deleted) {
                                logger.trace("Deleted: {}", path);
                            }
                        } catch (IOException e) {
                            logger.error("Failed to delete: {} - {}", path, e.getMessage());
                        }
                    });
            logger.info("Stream directory deleted successfully: {}", streamDir);
        } catch (IOException e) {
            logger.error("Error walking directory tree for deletion: {}", streamDir, e);
        }
    }

    public String getHlsRoot() {
        return HLS_ROOT;
    }

    /**
     * Clean up all HLS streams by deleting the entire HLS root directory
     * This is typically called on application startup to remove leftover files
     * from previous runs (e.g., after crash or Ctrl+C shutdown)
     */
    public void cleanupAllStreams() {
        Path hlsRootPath = Paths.get(HLS_ROOT);

        if (!Files.exists(hlsRootPath)) {
            logger.info("HLS root directory does not exist, nothing to clean up");
            return;
        }

        if (!Files.isDirectory(hlsRootPath)) {
            logger.warn("HLS root path exists but is not a directory: {}", hlsRootPath);
            return;
        }

        logger.info("Cleaning up all HLS streams from previous runs...");

        try {
            // Delete entire directory tree
            Files.walk(hlsRootPath)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            boolean deleted = Files.deleteIfExists(path);
                            if (deleted) {
                                logger.trace("Deleted: {}", path);
                            }
                        } catch (IOException e) {
                            logger.error("Failed to delete: {} - {}", path, e.getMessage());
                        }
                    });

            // Recreate the root directory
            Files.createDirectories(hlsRootPath);
            logger.info("HLS root directory cleaned and recreated successfully");

        } catch (IOException e) {
            logger.error("Error during HLS cleanup: {}", e.getMessage(), e);
        }
    }
}
