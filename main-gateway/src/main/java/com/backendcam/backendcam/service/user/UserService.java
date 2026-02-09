package com.backendcam.backendcam.service.user;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.backendcam.backendcam.model.entity.User;
import com.backendcam.backendcam.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Map<String, Object> getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return user.toMap();
    }

    public Map<String, Object> getUserById(String id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        return user.toMap();
    }
}
