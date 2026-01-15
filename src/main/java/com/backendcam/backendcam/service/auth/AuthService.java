package com.backendcam.backendcam.service.auth;

import java.util.Map;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.backendcam.backendcam.model.dto.auth.AuthResponseDto;
import com.backendcam.backendcam.model.dto.auth.LoginDto;
import com.backendcam.backendcam.model.dto.auth.RegisterDto;
import com.backendcam.backendcam.model.entity.User;
import com.backendcam.backendcam.repository.UserRepository;
import com.backendcam.backendcam.util.jwt.Jwt;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Jwt jwt;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    public AuthResponseDto register(RegisterDto request) {

        // Check if username already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username already exists");
        }

        User user = new User(
                request.getUsername(),
                passwordEncoder.encode(request.getPassword()));

        user = userRepository.save(user);

        // Generate JWT token
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwt.generateToken(userDetails);

        return new AuthResponseDto(token, user.getUsername());
    }

    public Map<String, String> login(LoginDto loginDto) {
        Authentication authenticationRequest =
			UsernamePasswordAuthenticationToken.unauthenticated(loginDto.getUsername(), loginDto.getPassword());

		Authentication authenticationResponse =
			this.authenticationManager.authenticate(authenticationRequest);

        UserDetails userDetails = (UserDetails) authenticationResponse.getPrincipal();
        String token = jwt.generateToken(userDetails);

        return Map.of("token", token, "username", userDetails.getUsername());
    }
}
