package com.backendcam.backendcam.model.dto.auth;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AuthResponseDto {

    private String token;
    private String type = "Bearer";
    private String username;

    public AuthResponseDto(String token, String username) {
        this.token = token;
        this.username = username;
    }

}
