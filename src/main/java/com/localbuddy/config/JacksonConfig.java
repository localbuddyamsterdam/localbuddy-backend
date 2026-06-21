package com.localbuddy.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes a shared {@link ObjectMapper} bean. Some services (e.g. the AI client)
 * inject ObjectMapper directly, and the current web starter does not register one
 * as a bean, which otherwise breaks application startup. Modules (java.time, etc.)
 * are auto-discovered from the classpath.
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        return objectMapper;
    }
}
