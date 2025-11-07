package com.backendcam.backendcam.firestore;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;

@Configuration
public class FirebaseAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminBootstrap.class);
    private volatile boolean initialized = false;

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                initialized = true;
                log.info("Firebase Admin SDK already initialized.");
                return;
            }

            String saPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
            if (saPath == null || saPath.isBlank()) {
                log.warn("Firebase Admin NOT initialized: env GOOGLE_APPLICATION_CREDENTIALS is missing. Firestore features will be disabled.");
                return; // ไม่โยน exception ให้แอปรันต่อ
            }

            try (FileInputStream in = new FileInputStream(saPath)) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp.initializeApp(options);
                initialized = true;
                log.info("Firebase Admin SDK initialized successfully.");
            }
        } catch (Exception e) {
            log.warn("Firebase Admin initialization failed. App will continue without Firestore. Cause: {}", e.getMessage(), e);
        }
    }

    /** ถ้ายังไม่ init ให้ warn ทุกครั้งที่โดนเรียก */
    public boolean isInitialized() {
        boolean ok = initialized || !FirebaseApp.getApps().isEmpty();
        if (!ok) {
            log.warn("Firestore is NOT initialized. Skipping operation.");
        }
        return ok;
    }
}