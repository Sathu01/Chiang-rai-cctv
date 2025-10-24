package com.backendcam.backendcam;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")           // ให้ทุก endpoint
                        .allowedOrigins("*")         // อนุญาตทุก origin
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");       // อนุญาตทุก header
            }
        };
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
