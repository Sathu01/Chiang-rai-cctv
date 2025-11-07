package com.backendcam.backendcam.firestore;

import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FirestoreService {
    private static final String COLLECTION = "cameras";
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public List<QueryDocumentSnapshot> fetchAllCameras() {
        try {
            Firestore db = FirestoreClient.getFirestore();
            return db.collection(COLLECTION).get().get().getDocuments();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch cameras", e);
        }
    }

    /**
     * อัปเดตสถานะ ONLINE: เขียน status และ lastSeen (timestamp + message)
     * (timestamp เป็น Asia/Bangkok)
     */
    public void updateOnline(String docId, String message) {
        Firestore db = FirestoreClient.getFirestore();

        Map<String, Object> lastSeen = new HashMap<>();
        lastSeen.put("timestamp", OffsetDateTime.now(BANGKOK).format(ISO_OFFSET));
        lastSeen.put("message", message);

        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "online");
        payload.put("lastSeen", lastSeen);

        db.collection(COLLECTION).document(docId).set(payload, SetOptions.merge());
    }

    /**
     * อัปเดตสถานะ OFFLINE: เขียนเฉพาะ status (ไม่แตะ lastSeen)
     */
    public void updateOffline(String docId) {
        Firestore db = FirestoreClient.getFirestore();
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "offline");

        db.collection(COLLECTION).document(docId).set(payload, SetOptions.merge());
    }

    public static Optional<String> pickRtspUrl(Map<String, Object> data) {
        Object u1 = data.get("url");
        Object u2 = data.get("URL");
        String url = u1 instanceof String ? (String) u1 : (u2 instanceof String ? (String) u2 : null);
        return Optional.ofNullable(url).map(String::trim).filter(s -> !s.isEmpty());
    }
}