package com.pms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Value("${storage.local-dir:uploads}")
    private String storageLocalDir;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = "*".equals(allowedOrigins)
                ? new String[]{"*"}
                : allowedOrigins.split(",\\s*");
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
        registry.addMapping("/files/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve stored files from /files/** mapping
        String location = "file:" + (storageLocalDir.endsWith("/") ? storageLocalDir : storageLocalDir + "/");
        registry.addResourceHandler("/files/**").addResourceLocations(location);
    }
}
