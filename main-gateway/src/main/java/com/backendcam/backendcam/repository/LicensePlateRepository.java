package com.backendcam.backendcam.repository;

import com.backendcam.backendcam.model.dto.LicensePlate;
import com.backendcam.backendcam.service.firestore.FirebaseAdminBootstrap;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutionException;

@Repository
@RequiredArgsConstructor
public class LicensePlateRepository {

    private static final String COLLECTION = "LicensePlate";
    private final FirebaseAdminBootstrap bootstrap;

    private Firestore getFirestore() {
        if (!bootstrap.isInitialized()) {
            throw new IllegalStateException("Firebase is not initialized");
        }
        return FirestoreClient.getFirestore();
    }

    // Save a license plate record
    public LicensePlate save(LicensePlate licensePlate) {
        try {
            Firestore db = getFirestore();
            Map<String, Object> data = new HashMap<>();
            data.put("licensePlate", licensePlate.getLicensePlate());
            data.put("urlImage", licensePlate.getUrlImage());
            data.put("dateTime", localDateTimeToTimestamp(licensePlate.getDateTime()));
            data.put("cameraId", licensePlate.getCameraId());

            DocumentReference docRef = db.collection(COLLECTION).document();
            docRef.set(data).get();

            return licensePlate;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to save license plate", e);
        }
    }

    // Get all license plate records
    public List<LicensePlate> getAll() throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<LicensePlate> plates = new ArrayList<>();

        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION).get();
        List<QueryDocumentSnapshot> documents = future.get().getDocuments();

        for (QueryDocumentSnapshot document : documents) {
            plates.add(documentToLicensePlate(document));
        }

        return plates;
    }

    // Search by license plate text
    public List<LicensePlate> findByLicensePlate(String plateText) throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<LicensePlate> plates = new ArrayList<>();

        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereEqualTo("licensePlate", plateText)
                .get();

        List<QueryDocumentSnapshot> documents = future.get().getDocuments();

        for (QueryDocumentSnapshot document : documents) {
            plates.add(documentToLicensePlate(document));
        }

        return plates;
    }

    // Search by camera ID
    public List<LicensePlate> findByCameraId(String cameraId) throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<LicensePlate> plates = new ArrayList<>();

        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereEqualTo("cameraId", cameraId)
                .get();

        List<QueryDocumentSnapshot> documents = future.get().getDocuments();

        for (QueryDocumentSnapshot document : documents) {
            plates.add(documentToLicensePlate(document));
        }

        return plates;
    }

    // Search by date range
    public List<LicensePlate> findByDateRange(LocalDateTime startDate, LocalDateTime endDate) throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<LicensePlate> plates = new ArrayList<>();

        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereGreaterThanOrEqualTo("dateTime", localDateTimeToTimestamp(startDate))
                .whereLessThanOrEqualTo("dateTime", localDateTimeToTimestamp(endDate))
                .get();

        List<QueryDocumentSnapshot> documents = future.get().getDocuments();

        for (QueryDocumentSnapshot document : documents) {
            plates.add(documentToLicensePlate(document));
        }

        return plates;
    }

    // Search by camera ID and date range
    public List<LicensePlate> findByCameraIdAndDateRange(String cameraId, LocalDateTime startDate, LocalDateTime endDate)
            throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<LicensePlate> plates = new ArrayList<>();

        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereEqualTo("cameraId", cameraId)
                .whereGreaterThanOrEqualTo("dateTime", localDateTimeToTimestamp(startDate))
                .whereLessThanOrEqualTo("dateTime", localDateTimeToTimestamp(endDate))
                .get();

        List<QueryDocumentSnapshot> documents = future.get().getDocuments();

        for (QueryDocumentSnapshot document : documents) {
            plates.add(documentToLicensePlate(document));
        }

        return plates;
    }

    // Helper: convert Firestore document to LicensePlate
    private LicensePlate documentToLicensePlate(QueryDocumentSnapshot document) {
        LicensePlate plate = new LicensePlate();
        plate.setLicensePlate(document.getString("licensePlate"));
        plate.setUrlImage(document.getString("urlImage"));
        plate.setCameraId(document.getString("cameraId"));

        // Convert Firestore Timestamp to LocalDateTime
        Timestamp timestamp = document.getTimestamp("dateTime");
        if (timestamp != null) {
            plate.setDateTime(timestamp.toDate().toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime());
        }

        return plate;
    }

    // Helper: convert LocalDateTime to Firestore Timestamp
    private Timestamp localDateTimeToTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return Timestamp.ofTimeSecondsAndNanos(
                dateTime.atZone(ZoneId.systemDefault()).toEpochSecond(),
                dateTime.getNano());
    }
}
