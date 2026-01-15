package com.backendcam.backendcam.config.exception;

import java.io.IOException;
import java.util.Map;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.backendcam.backendcam.util.ResponseWriter;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        ResponseWriter.writeJson(
			response, 
			HttpServletResponse.SC_UNAUTHORIZED,
			Map.of(
				"statusCode", HttpServletResponse.SC_UNAUTHORIZED, 
				"message", "Unauthorized"
			)
		);
    }
}
