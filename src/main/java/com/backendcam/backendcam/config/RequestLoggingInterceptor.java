package com.backendcam.backendcam.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;

@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    @Value("${logging.request.enabled:false}")
    private boolean enabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }

        long ts = System.currentTimeMillis();
        String iso = Instant.ofEpochMilli(ts).toString();
        String handlerInfo = (handler instanceof HandlerMethod) ? ((HandlerMethod) handler).getShortLogMessage() : String.valueOf(handler);

        logger.info("Incoming request - time={}, method={}, uri={}, handler={}, remoteAddr={}",
                iso, request.getMethod(), request.getRequestURI(), handlerInfo, request.getRemoteAddr());

        return true;
    }
}
