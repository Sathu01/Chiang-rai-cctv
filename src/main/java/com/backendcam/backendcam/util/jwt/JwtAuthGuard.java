package com.backendcam.backendcam.util.jwt;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.backendcam.backendcam.util.jwt.decorator.PublicEndpoint;
import com.backendcam.backendcam.util.jwt.service.CustomUserDetailsService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT Authentication Filter
 * Intercepts all requests and validates JWT token
 * Supports token extraction from:
 * 1. Authorization header (Priority 1)
 * 2. Cookie (Priority 2)
 * 
 * Skips authentication for methods annotated with @PublicEndpoint
 */
@Component
public class JwtAuthGuard extends OncePerRequestFilter {

    private final Jwt jwt;
    private final CustomUserDetailsService userDetailsService;
    private final RequestMappingHandlerMapping handlerMapping;

    public JwtAuthGuard(Jwt jwt, CustomUserDetailsService userDetailsService,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        this.jwt = jwt;
        this.userDetailsService = userDetailsService;
        this.handlerMapping = handlerMapping;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Check if endpoint is public
        if (isPublicEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Extract JWT token (Priority: Authorization header > Cookie)
        String token = extractJwtToken(request);

        // If token exists and is valid, set authentication
        if (token != null) {
            try {
                String username = jwt.extractUsername(token);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                    if (jwt.validateToken(token, userDetails)) {
                        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities());
                        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(authToken);
                    }
                }
            } catch (Exception e) {
                logger.error("Cannot set user authentication: ", e);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract JWT token from request
     * Priority 1: Authorization header (Bearer token)
     * Priority 2: Cookie named "jwt_token"
     */
    private String extractJwtToken(HttpServletRequest request) {
        // Priority 1: Check Authorization header
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // Priority 2: Check cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("jwt_token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }

    /**
     * Check if the endpoint is annotated with @PublicEndpoint
     */
    private boolean isPublicEndpoint(HttpServletRequest request) {
        try {
            HandlerExecutionChain handlerChain = handlerMapping.getHandler(request);
            if (handlerChain != null && handlerChain.getHandler() instanceof HandlerMethod) {
                HandlerMethod handlerMethod = (HandlerMethod) handlerChain.getHandler();
                return handlerMethod.hasMethodAnnotation(PublicEndpoint.class);
            }
        } catch (Exception e) {
            logger.error("Could not determine if endpoint is public: ", e);
        }
        return false;
    }
}
