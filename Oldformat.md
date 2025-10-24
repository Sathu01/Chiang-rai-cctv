package com.backendcam.backendcam;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FFmpegLogCallback;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * HLSStreamService
 *
 * บริการสำหรับแปลง RTSP เป็น HLS โดยใช้ JavaCV/FFmpeg
 * - รองรับ fallback RTSP URL/transport หาก URL ไม่มาตรฐาน
 * - แปลงวิดีโอเป็น HLS segment (.ts) และ playlist (.m3u8)
 */
@Service
public class HLSStreamService {

    private static final Logger logger = Logger.getLogger(HLSStreamService.class.getName());

    private static final String HLS_ROOT = "./hls"; // โฟลเดอร์ root สำหรับเก็บ HLS output

    /**
     * เริ่มต้นสตรีม HLS จาก RTSP
     * 
     * @param rtspUrl    URL ของกล้อง/MTX RTSP
     * @param streamName ชื่อ stream สำหรับโฟลเดอร์ output
     * @return path ของ m3u8 playlist สำหรับ controller
     */
    public String startHLSStream(String rtspUrl, String streamName) {
        try {
            logger.info("Starting HLS stream: " + rtspUrl + " as " + streamName);

            // --- Step 1: Connect to RTSP with fallback variants ---
            FFmpegFrameGrabber grabber = tryStartRtspWithFallback(rtspUrl);

            logger.info("Grabber started. Initial resolution: "
                    + grabber.getImageWidth() + "x" + grabber.getImageHeight());

            // --- Step 2: สร้างโฟลเดอร์ output ---
            File outputDir = new File(HLS_ROOT + "/" + streamName);
            if (!outputDir.exists()) {
                boolean created = outputDir.mkdirs();
                logger.info("Created folder: " + outputDir.getAbsolutePath() + " - Success: " + created);
            }

            String hlsOutput = outputDir.getAbsolutePath() + "/stream.m3u8";

            // --- Step 3: Warm-up grab few frames เพื่อหาความละเอียด ---
            int warmupMax = 100; // limit loop ป้องกัน infinite
            int warmCount = 0;
            Frame firstVideoFrame = null;
            while (warmCount < warmupMax) {
                Frame f = grabber.grab();
                if (f == null)
                    break;
                if (f.image != null) {
                    firstVideoFrame = f;
                    break;
                }
                warmCount++;
            }

            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            if ((width <= 0 || height <= 0) && firstVideoFrame != null) {
                width = firstVideoFrame.imageWidth;
                height = firstVideoFrame.imageHeight;
            }
            if (width <= 0 || height <= 0) {
                throw new RuntimeException("Could not determine video resolution from RTSP stream");
            }

            // --- Step 4: Setup FFmpegFrameRecorder สำหรับ HLS ---
            FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(
                    hlsOutput,
                    width,
                    height,
                    Math.max(0, grabber.getAudioChannels()) // ตรวจสอบ audio channels
            );

            // --- Video Settings ---
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
            recorder.setFormat("hls");
            int fps = Math.max(15, grabber.getFrameRate() > 0 ? (int) Math.round(grabber.getFrameRate()) : 25);
            recorder.setFrameRate(fps);
            recorder.setGopSize(2 * fps); // keyframe ทุก ~2 วินาที

            // --- Audio Settings ---
            if (grabber.getAudioChannels() > 0) {
                recorder.setAudioCodec(avcodec.AV_CODEC_ID_AAC);
                recorder.setSampleRate(grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 8000);
                recorder.setAudioBitrate(128000);
            }

            // --- HLS Options ---
            recorder.setOption("hls_time", "1"); // 1 วินาทีต่อ segment
            recorder.setOption("hls_list_size", "6"); // playlist window เล็ก
            recorder.setOption("hls_flags", "delete_segments+independent_segments+program_date_time");
            recorder.setOption("hls_segment_type", "mpegts");
            recorder.setOption("hls_allow_cache", "0");
            String segPath = outputDir.getAbsolutePath().replace('\\', '/') + "/seg%05d.ts";
            recorder.setOption("hls_segment_filename", segPath);
            recorder.setOption("reset_timestamps", "1");

            // --- Encoding Optimization ---
            recorder.setOption("preset", "ultrafast");
            recorder.setOption("tune", "zerolatency");
            recorder.setOption("crf", "23");
            recorder.setOption("maxrate", "2M");
            recorder.setOption("bufsize", "4M");
            recorder.setOption("fflags", "+genpts+igndts");
            recorder.setOption("avoid_negative_ts", "make_zero");
            recorder.setOption("fps_mode", "cfr");

            recorder.start();
            logger.info("Recorder started at " + hlsOutput + " with size " + width + "x" + height);

            // --- Step 5: เริ่ม streaming ใน thread แยก ---
            new Thread(() -> streamToHLS(grabber, recorder, streamName)).start();

            return "/hls/" + streamName + "/stream.m3u8"; // path relative สำหรับ controller

        } catch (Exception e) {
            logger.severe("Failed to start HLS stream: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Failed to start HLS stream", e);
        }
    }

    /**
     * สตรีม frames จาก grabber → recorder
     */
    private void streamToHLS(FFmpegFrameGrabber grabber, FFmpegFrameRecorder recorder, String streamName) {
        try {
            Frame frame;
            int count = 0;
            while ((frame = grabber.grab()) != null) {
                recorder.record(frame);
                count++;
                if (count % 100 == 0)
                    logger.info("Processed " + count + " frames for " + streamName);
            }
        } catch (Exception e) {
            logger.severe("Error streaming " + streamName + ": " + e.getMessage());
            stopHLSStream(streamName);
        } finally {
            try {
                if (recorder != null) {
                    recorder.stop();
                    recorder.release();
                }
                if (grabber != null) {
                    grabber.stop();
                    grabber.release();
                }
            } catch (Exception e) {
                logger.warning("Cleanup error: " + e.getMessage());
            }
        }
    }

    /**
     * ลบ HLS output ทั้งหมดสำหรับ streamName
     */
    public void stopHLSStream(String streamName) {
        File dir = new File(HLS_ROOT + "/" + streamName);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files)
                    f.delete();
            dir.delete();
        }
    }

    // --- Helper: Connect RTSP ด้วย fallback variants ---
    private FFmpegFrameGrabber tryStartRtspWithFallback(String rtspUrl) throws Exception {
        FFmpegLogCallback.set(); // ให้ FFmpeg log callback

        List<String> candidates = new ArrayList<>();
        candidates.add(rtspUrl);

        try {
            URI u = new URI(rtspUrl);
            String userInfo = u.getUserInfo();
            String base = u.getScheme() + "://" + (userInfo != null ? userInfo + "@" : "") + u.getHost()
                    + (u.getPort() > 0 ? ":" + u.getPort() : "");
            String path = u.getPath() != null ? u.getPath() : "/";

            // ถ้า path เป็น vendor indices ลอง variants ต่าง ๆ
            if (path.matches("/.+/.*")) {
                candidates.add(base + "/Streaming/Channels/101"); // Hikvision
                candidates.add(base + "/Streaming/Channels/102");
                candidates.add(base + "/cam/realmonitor?channel=1&subtype=0"); // Dahua
                candidates.add(base + "/live");
                candidates.add(base + "/live.sdp");
                candidates.add(base + "/h264");
                candidates.add(base + "/ch1-s1");
                candidates.add(base + "/unicast/c1/s0/live");
                candidates.add(base + "/avstream/channel=1/stream=0.sdp");
            }

            // TrackID suffixes
            candidates.add(base + path + (path.endsWith("/") ? "" : "/") + "trackID=0");
            candidates.add(base + path + (path.endsWith("/") ? "" : "/") + "trackID=1");

        } catch (Exception ignore) {
        }

        Exception last = null;
        for (String cand : candidates) {
            for (String transport : new String[] { "udp", "tcp", "http" }) {
                logger.info("Trying RTSP URL: " + cand + " via " + transport);
                FFmpegFrameGrabber g = new FFmpegFrameGrabber(cand);
                g.setFormat("rtsp");
                g.setOption("rtsp_transport", transport);
                if ("udp".equals(transport)) {
                    g.setOption("fflags", "+nobuffer+genpts+igndts");
                    g.setOption("max_delay", "0"); // ลด delay buffer
                    g.setOption("flags", "low_delay"); // low-latency mode
                    g.setOption("probesize", "500000"); // probe น้อยลงเพื่อเร็วขึ้น
                    g.setOption("analyzeduration", "500000"); // analyze short duration
                }
                if ("tcp".equals(transport)){
                    g.setOption("rtsp_flags", "prefer_tcp");
                    
                }
                
                g.setOption("stimeout", "20000000");
                g.setOption("rw_timeout", "20000000");
                g.setOption("analyzeduration", "1000000");
                g.setOption("probesize", "1000000");
                g.setOption("fflags", "+nobuffer+genpts+igndts");
                g.setOption("max_delay", "0");
                g.setOption("reorder_queue_size", "0");
                g.setOption("use_wallclock_as_timestamps", "1");
                g.setOption("allowed_media_types", "video");
                g.setOption("user_agent", "LibVLC/3.0.18 (LIVE555 Streaming Media v2021.06.08)");
                g.setOption("loglevel", "info");

                try {
                    g.start();
                    logger.info(
                            "Connected to RTSP: " + cand + ", size=" + g.getImageWidth() + "x" + g.getImageHeight());
                    return g;
                } catch (Exception e) {
                    last = e;
                    logger.warning("Failed URL/transport: " + cand + " (" + transport + ") - " + e.getMessage());
                    try {
                        g.release();
                    } catch (Exception ignore) {
                    }
                }
            }
        }
        if (last != null)
            throw last;
        throw new RuntimeException("RTSP connection failed for all candidates");
    }
}
