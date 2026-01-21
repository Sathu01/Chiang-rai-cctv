package com.backendcam.backendcam;

import org.junit.jupiter.api.*;

import com.backendcam.backendcam.service.hls.HLSStreamService;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class HLSStreamServiceTest {

    private HLSStreamService service;

    @BeforeEach
    void setUp() {
        service = new HLSStreamService();
    }

    @AfterEach
    void tearDown() {
        // clean test folders
        File testDir = new File("./hls/testStream");
        if (testDir.exists()) {
            File[] files = testDir.listFiles();
            if (files != null) for (File f : files) f.delete();
            testDir.delete();
        }
    }

    @Test
    void testStartHLSStream_ReturnsPlaylistUrl() {
        String rtspUrl = "rtsp://Police:PoliceCR1234$@183.89.209.110:4442/1/2";
        String streamName = "testStream";

        String result = service.StartHLSstream(rtspUrl, streamName);

        // Expect the HLS playlist path
        assertEquals("/hls/" + streamName + "/stream.m3u8", result);
    }

    @Test
    void testStartHLSStream_CreatesFolder() throws Exception {
        String rtspUrl = "rtsp://Police:PoliceCR1234$@183.89.209.110:4442/1/2";
        String streamName = "testStream";

        service.StartHLSstream(rtspUrl, streamName);

        // Give async startup a moment if needed
        Thread.sleep(100);

        File outputDir = new File("./hls", streamName);
        assertTrue(outputDir.exists() && outputDir.isDirectory(),
                "HLS folder should be created for the stream");
    }

    @Test
    void testStartHLSStream_AlreadyExists_ReturnsSameUrl() {
        String rtspUrl = "rtsp://Police:PoliceCR1234$@183.89.209.110:4442/1/2";
        String streamName = "testStream";

        String first = service.StartHLSstream(rtspUrl, streamName);
        String second = service.StartHLSstream(rtspUrl, streamName);

        assertEquals(first, second, "Starting the same stream twice should return the same URL");
    }
}


