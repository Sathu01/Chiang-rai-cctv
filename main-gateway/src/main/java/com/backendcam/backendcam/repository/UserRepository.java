package com.backendcam.backendcam.repository;

import com.backendcam.backendcam.model.entity.User;
import com.backendcam.backendcam.service.firestore.FirebaseAdminBootstrap;
import com.google.cloud.firestore.*;
import com.google.firebase.cloud.FirestoreClient;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;

@Repository
@RequiredArgsConstructor
public class UserRepository {

    private static final String COLLECTION = "users";
    private final FirebaseAdminBootstrap bootstrap;

    private Firestore getFirestore() {
        if (!bootstrap.isInitialized()) {
            throw new IllegalStateException("Firebase is not initialized");
        }
        return FirestoreClient.getFirestore();
    }

    public User save(User user) {
        try {
            Firestore db = getFirestore();
            Map<String, Object> userData = user.toMap();

            if (user.getId() == null || user.getId().isEmpty()) {
                // Create new document with auto-generated ID
                DocumentReference docRef = db.collection(COLLECTION).document();
                user.setId(docRef.getId());
                userData = user.toMap(); // Update map with new ID
                docRef.set(userData).get();
            } else {
                // Update existing document
                db.collection(COLLECTION).document(user.getId()).set(userData, SetOptions.merge()).get();
            }

            return user;
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to save user", e);
        }
    }

    public Optional<User> findByUsername(String username) {
        try {
            Firestore db = getFirestore();
            QuerySnapshot querySnapshot = db.collection(COLLECTION)
                    .whereEqualTo("username", username)
                    .limit(1)
                    .get()
                    .get();

            if (querySnapshot.isEmpty()) {
                return Optional.empty();
            }

            DocumentSnapshot doc = querySnapshot.getDocuments().get(0);
            return Optional.of(User.fromMap(doc.getId(), doc.getData()));
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find user by username", e);
        }
    }

    public Optional<User> findById(String id) {
        try {
            Firestore db = getFirestore();
            DocumentSnapshot doc = db.collection(COLLECTION).document(id).get().get();

            if (!doc.exists()) {
                return Optional.empty();
            }

            return Optional.of(User.fromMap(doc.getId(), doc.getData()));
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to find user by ID", e);
        }
    }

    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    public void deleteById(String id) {
        try {
            Firestore db = getFirestore();
            db.collection(COLLECTION).document(id).delete().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException("Failed to delete user", e);
        }
    }
}
