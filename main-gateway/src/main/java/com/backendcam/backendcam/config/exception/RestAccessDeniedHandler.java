package com.backendcam.backendcam.config.exception;

import java.io.IOException;
import java.util.Map;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.backendcam.backendcam.util.ResponseWriter;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        ResponseWriter.writeJson(
			response, 
			HttpServletResponse.SC_FORBIDDEN,
			Map.of(
				"statusCode", HttpServletResponse.SC_FORBIDDEN, 
				"message", "Forbidden"
			)
		);
    }
}