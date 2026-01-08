package com.backendcam.backendcam.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // TODO: REDUNDANT - No need for separate @Bean, can override methods directly
    // @Bean
    // public WebMvcConfigurer corsConfigurer() {
    // return new WebMvcConfigurer() {
    // @Override
    // public void addCorsMappings(CorsRegistry registry) {
    // registry.addMapping("/**")
    // .allowedOrigins("*")
    // .allowedMethods("GET", "POST", "OPTIONS")
    // .allowedHeaders("*");
    // }
    // };
    // }

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
        // or absolute path:
        // .addResourceLocations("file:/C:/path/to/BACKENDCAM/hls/");
    }
}
