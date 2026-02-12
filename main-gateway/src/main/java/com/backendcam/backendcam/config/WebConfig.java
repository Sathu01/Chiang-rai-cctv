package com.backendcam.backendcam.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RequestLoggingInterceptor requestLoggingInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**") // ให้ทุก endpoint
                .allowedOrigins("*") // อนุญาตทุก origin
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*"); // อนุญาตทุก header
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
                .addResourceHandler("/hls/**")
                .addResourceLocations("file:./hls/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor).addPathPatterns("/**");
    }
}