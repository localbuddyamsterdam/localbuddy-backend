package com.localbuddy.auth;

import com.localbuddy.user.User;
import com.localbuddy.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** Machine-readable code the frontend keys on to route to the forced change screen. */
    public static final String PASSWORD_CHANGE_REQUIRED_CODE = "PASSWORD_CHANGE_REQUIRED";

    /**
     * Paths a must-change-password user may still reach: the public auth surface
     * (login/refresh/logout/reset/verify/me all live under {@code /api/auth/}) and
     * the forced set-password endpoint itself. Everything else authenticated is
     * blocked until they set a real password.
     */
    private static final List<String> PASSWORD_CHANGE_EXEMPT_PREFIXES = List.of(
            "/api/auth/",
            "/api/account/me/set-initial-password",
            "/api/public/",
            "/api/cities/",
            "/api/experience-categories/",
            "/illustrations/",
            "/api/health",
            "/actuator/",
            "/swagger-ui",
            "/v3/api-docs"
    );

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(7);

        try {
            UUID userId = jwtService.extractUserId(token);

            User user = userRepository.findById(userId).orElse(null);

            if (user != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                List<SimpleGrantedAuthority> authorities = List.of(
                        new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                user.getId().toString(),
                                null,
                                authorities
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);
            }

            // Defence in depth for the forced first-login change: a user carrying a
            // valid token but still owing a password change is refused everything
            // except the exempt surface — a custom client can't skip the change screen.
            // The flag is read off the user we already loaded above, so this adds no query.
            if (user != null && user.isMustChangePassword() && !isPasswordChangeExempt(request)) {
                writePasswordChangeRequired(request, response);
                return;
            }

        } catch (JwtException | IllegalArgumentException ex) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPasswordChangeExempt(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : PASSWORD_CHANGE_EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 403 whose {@code code} the frontend recognises to send the user to the set-password screen. */
    private void writePasswordChangeRequired(HttpServletRequest request,
                                             HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"timestamp":"%s","status":403,"error":"Forbidden","code":"%s",\
                "message":"Please set a new password before continuing.","path":"%s"}"""
                .formatted(Instant.now(), PASSWORD_CHANGE_REQUIRED_CODE, request.getRequestURI()));
    }
}