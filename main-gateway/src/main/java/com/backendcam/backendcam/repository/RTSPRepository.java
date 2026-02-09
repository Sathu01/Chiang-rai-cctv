package com.backendcam.backendcam.repository;

import com.backendcam.backendcam.model.entity.RTSP;
import com.backendcam.backendcam.service.firestore.FirebaseAdminBootstrap;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;

@Repository
@RequiredArgsConstructor
public class RTSPRepository {
     private static final String COLLECTION = "cameras";
    private final FirebaseAdminBootstrap bootstrap;

    private Firestore getFirestore() {
        if (!bootstrap.isInitialized()) {
            throw new IllegalStateException("Firebase is not initialized");
        }
        return FirestoreClient.getFirestore();
    }

    // Get all RTSP cameras
    public List<RTSP> getAllCameras() throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<RTSP> cameras = new ArrayList<>();
        
        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION).get();
        List<QueryDocumentSnapshot> documents = future.get().getDocuments();
        
        for (QueryDocumentSnapshot document : documents) {
            RTSP camera = document.toObject(RTSP.class);
            camera.setId(document.getId());
            cameras.add(camera);
        }
        
        return cameras;
    }

    // Get RTSP camera by ID
    public Optional<RTSP> getCameraById(String id) throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        
        DocumentReference docRef = db.collection(COLLECTION).document(id);
        ApiFuture<DocumentSnapshot> future = docRef.get();
        DocumentSnapshot document = future.get();
        
        if (document.exists()) {
            RTSP camera = document.toObject(RTSP.class);
            camera.setId(document.getId());
            return Optional.of(camera);
        }
        
        return Optional.empty();
    }

    // Get RTSP camera by name
    public Optional<RTSP> getCameraByName(String name) throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        
        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereEqualTo("name", name)
                .limit(1)
                .get();
        
        List<QueryDocumentSnapshot> documents = future.get().getDocuments();
        
        if (!documents.isEmpty()) {
            QueryDocumentSnapshot document = documents.get(0);
            RTSP camera = document.toObject(RTSP.class);
            camera.setId(document.getId());
            return Optional.of(camera);
        }
        
        return Optional.empty();
    }

    // Get only the RTSP link by camera ID
    public Optional<String> getRtspLinkById(String id) throws ExecutionException, InterruptedException {
        return getCameraById(id).map(RTSP::getRtspLink);
    }

    // Get all active cameras (status = "active")
    public List<RTSP> getActiveCameras() throws ExecutionException, InterruptedException {
        Firestore db = getFirestore();
        List<RTSP> cameras = new ArrayList<>();
        
        ApiFuture<QuerySnapshot> future = db.collection(COLLECTION)
                .whereEqualTo("status", "active")
                .get();
        
        List<QueryDocumentSnapshot> documents = future.get().getDocuments();
        
        for (QueryDocumentSnapshot document : documents) {
            RTSP camera = document.toObject(RTSP.class);
            camera.setId(document.getId());
            cameras.add(camera);
        }
        
        return cameras;
    }

}
