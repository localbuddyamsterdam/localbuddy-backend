package com.localbuddy.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "Health", description = "Service health check endpoint")
public class HealthController {

    @Operation(
            summary = "Service health check",
            description = "Returns the current health status of the service. Public."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Service is up")
    })
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "localbuddy-backend");
        response.put("timestamp", Instant.now().toString());
        return response;
    }
}

