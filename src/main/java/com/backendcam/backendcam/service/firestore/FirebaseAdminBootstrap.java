package com.backendcam.backendcam.service.firestore;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

import java.io.InputStream;

@Configuration
public class FirebaseAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminBootstrap.class);
    private volatile boolean initialized = false;

    @Value("${firebase.credentials.path:}")
    private Resource saResource;

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                initialized = true;
                log.info("Firebase Admin SDK already initialized.");
                return;
            }

            if (saResource == null || !saResource.exists()) {
                log.warn(
                        "Firebase Admin NOT initialized: property 'firebase.credentials.path' is missing or file not found. Firestore features will be disabled.");
                return;
            }

            try (InputStream in = saResource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .build();
                FirebaseApp.initializeApp(options);
                initialized = true;
                log.info("Firebase Admin SDK initialized successfully.");
            }
        } catch (Exception e) {
            log.warn("Firebase Admin initialization failed. App will continue without Firestore. Cause: {}",
                    e.getMessage(), e);
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
