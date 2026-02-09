package com.backendcam.backendcam.model.entity;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {
    private String id;
    private String username;
    private String password;
    private List<String> roles; // e.g., ["USER"], ["USER", "ADMIN"]
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public User() {
        this.roles = new ArrayList<>();
        this.roles.add("USER"); // Default role
    }

    public User(String username, String password) {
        this();
        this.username = username;
        this.password = password;
        this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Bangkok"));
        this.updatedAt = this.createdAt;
    }

    // Convert to Map for Firestore
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("username", username);
        map.put("password", password);
        map.put("roles", roles);
        map.put("createdAt", createdAt != null ? createdAt.toString() : null);
        map.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
        return map;
    }

    // Create from Firestore document
    public static User fromMap(String id, Map<String, Object> data) {
        User user = new User();
        user.setId(id);
        user.setUsername((String) data.get("username"));
        user.setPassword((String) data.get("password"));

        @SuppressWarnings("unchecked")
        List<String> rolesList = (List<String>) data.get("roles");
        user.setRoles(rolesList != null ? rolesList : Arrays.asList("USER"));

        String createdAtStr = (String) data.get("createdAt");
        if (createdAtStr != null) {
            user.setCreatedAt(OffsetDateTime.parse(createdAtStr));
        }

        String updatedAtStr = (String) data.get("updatedAt");
        if (updatedAtStr != null) {
            user.setUpdatedAt(OffsetDateTime.parse(updatedAtStr));
        }

        return user;
    }
}
