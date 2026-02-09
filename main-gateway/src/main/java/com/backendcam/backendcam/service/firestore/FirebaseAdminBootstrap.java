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
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Configuration
public class FirebaseAdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminBootstrap.class);
    private volatile boolean initialized = false;

    @Autowired
    private ResourceLoader resourceLoader;

    @Value("${firebase.credentials.path:}")
    private String saPath;

    @PostConstruct
    public void init() {
        try {
            if (!FirebaseApp.getApps().isEmpty()) {
                initialized = true;
                log.info("Firebase Admin SDK already initialized.");
                return;
            }

            List<String> tried = new ArrayList<>();
            Resource resource = null;

            if (saPath != null && !saPath.isBlank()) {
                tried.add(saPath);
                resource = resourceLoader.getResource(saPath);
                log.info("Configured firebase.credentials.path='{}'", saPath);
            }

            if ((resource == null || !resource.exists())) {
                String env = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
                if (env != null && !env.isBlank()) {
                    String envRef = env.startsWith("file:") ? env : "file:" + env;
                    tried.add(envRef);
                    resource = resourceLoader.getResource(envRef);
                    log.info("Environment GOOGLE_APPLICATION_CREDENTIALS='{}'", env);
                }
            }

            if ((resource == null || !resource.exists())) {
                // Common docker-compose mount in this project: /secrets/serviceAccount.json
                String fallback = "file:/secrets/serviceAccount.json";
                tried.add(fallback);
                resource = resourceLoader.getResource(fallback);
                log.info("Trying fallback path {}", fallback);
            }

            boolean exists = (resource != null && resource.exists());
            if (!exists) {
                log.warn("Firebase Admin NOT initialized: property 'firebase.credentials.path' is missing or file not found. Tried: {}. Firestore features will be disabled.", tried);
                return;
            }

            try (InputStream in = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(in))
                        .setStorageBucket("centralcamera-7de28.firebasestorage.app")
                        .build();
                FirebaseApp.initializeApp(options);
                initialized = true;
                log.info("Firebase Admin SDK initialized successfully using resource: {}", resource);
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
