package com.localbuddy.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .formLogin(formLogin -> formLogin.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // `/api/auth/**` is public, but `/me` is the one endpoint under it
                        // that describes the *caller*, so it needs a principal. Matched
                        // ahead of the blanket permitAll below: otherwise a request with a
                        // bad or expired token reaches the handler with a null
                        // Authentication, which it dereferences into a 500 instead of the
                        // clean 401 this rule produces. (Rules are first-match-wins.)
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers(
                                "/api/health",
                                "/actuator/health",
                                "/actuator/info",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/api/auth/**",
                                "/api/public/**",
                                "/api/experience-categories/**",
                                "/api/cities/**",
                                "/illustrations/**"
                        ).permitAll()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // Host-only surface — a positive role gate so it never depends solely on each
                        // handler's own in-code check (defence in depth against an unguarded endpoint).
                        .requestMatchers("/api/host/**").hasRole("LOCAL")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        // Missing/expired/invalid JWT → 401 (the stateless default is a bare
                        // 403, which clients can't tell apart from a real role denial).
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(request, response, 401, "Unauthorized",
                                        "Authentication required — please log in."))
                        // Authenticated but lacking the required role → 403.
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(request, response, 403, "Forbidden",
                                        "You don't have permission to perform this action.")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** Mirrors {@link com.localbuddy.common.exception.ErrorResponse}'s JSON shape. */
    private static void writeError(HttpServletRequest request, HttpServletResponse response,
                                   int status, String error, String message) throws IOException {
        response.setStatus(status);
        // Set the charset explicitly: these handlers write JSON by hand rather
        // than through Jackson, and a bare "application/json" leaves the writer
        // on the servlet default (ISO-8859-1), which turns the em-dash in our
        // messages into a literal "?".
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":%d,"error":"%s","message":"%s","path":"%s"}"""
                .formatted(Instant.now(), status, error, message, request.getRequestURI()));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:3000}") String allowedOrigins
    ) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Headers the browser is allowed to read cross-origin (downloads, pagination, retry-after).
        config.setExposedHeaders(List.of("Location", "Content-Disposition", "X-Total-Count", "Retry-After"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}