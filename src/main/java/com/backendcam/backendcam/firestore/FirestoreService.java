package com.backendcam.backendcam.firestore;

import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;
import com.backendcam.backendcam.util.TimeAgoFormatter;

import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;

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

    /** OFFLINE: อัปเดต status และคำนวณ lastSeen.message ใหม่ โดย “ไม่เปลี่ยน” timestamp เดิม */
    public void updateOffline(String docId) {
        try {
            Firestore db = FirestoreClient.getFirestore();
            DocumentReference ref = db.collection(COLLECTION).document(docId);
            DocumentSnapshot snap = ref.get().get();

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "offline");

            // ดึง timestamp เดิม (ถ้ามี) แล้วคำนวณ message ใหม่
            if (snap.exists()) {
                Map<String, Object> lastSeen = (Map<String, Object>) snap.get("lastSeen");
                String tsStr = lastSeen != null ? (String) lastSeen.get("timestamp") : null;

                if (tsStr != null && !tsStr.isBlank()) {
                    OffsetDateTime ts = OffsetDateTime.parse(tsStr, ISO_OFFSET);
                    long secs = Duration.between(ts, OffsetDateTime.now(BANGKOK)).getSeconds();
                    String msg = TimeAgoFormatter.humanizeSinceSeconds(Math.max(0, secs));

                    Map<String, Object> newLastSeen = new HashMap<>();
                    newLastSeen.put("timestamp", tsStr);  // คงค่าเดิม
                    newLastSeen.put("message", msg);      // อัปเดตใหม่
                    payload.put("lastSeen", newLastSeen);
                }
                // ถ้าไม่มี timestamp เดิม ก็ไม่แตะ lastSeen (หรือจะใส่ message="unknown" ก็ได้)
                // else { payload.put("lastSeen", Map.of("message", "unknown")); }
            }

            ref.set(payload, SetOptions.merge());
        } catch (Exception e) {
            throw new RuntimeException("Failed to update offline status", e);
        }
    }

    public static Optional<String> pickRtspUrl(Map<String, Object> data) {
        Object u1 = data.get("url");
        Object u2 = data.get("URL");
        String url = u1 instanceof String ? (String) u1 : (u2 instanceof String ? (String) u2 : null);
        return Optional.ofNullable(url).map(String::trim).filter(s -> !s.isEmpty());
    }
}