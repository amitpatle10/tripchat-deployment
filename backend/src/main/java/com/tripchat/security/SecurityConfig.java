package com.tripchat.security;

import com.tripchat.security.jwt.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * SecurityConfig — Spring Security wiring.
 *
 * Key decisions:
 *
 * Stateless sessions (STATELESS):
 *   JWT is self-contained — no server-side session needed.
 *   Tradeoff: can't invalidate tokens server-side (token lives until expiry).
 *   At Phase 1 scope, this is acceptable. Token blacklisting via Redis is a
 *   future concern.
 *
 * CSRF disabled:
 *   CSRF attacks exploit cookie-based sessions. We use JWT in Authorization
 *   header — no cookies, no CSRF risk. Disabling is correct for stateless APIs.
 *
 * BCryptPasswordEncoder cost factor 10 (default):
 *   ~100ms per hash. Discussed and agreed — sufficient for 1000 DAUs.
 *   Injected as a bean so it can be replaced (Argon2) without changing callers.
 *
 * UserDetailsService:
 *   Extracted into CustomUserDetailsService to break circular dependency:
 *   JwtAuthFilter needs UserDetailsService; SecurityConfig previously defined it
 *   and also imported JwtAuthFilter → cycle. Separate class breaks the cycle.
 *
 * Extensibility for Google OAuth2:
 *   Adding "Login with Google" = add oauth2Login() to the HttpSecurity chain.
 *   Zero changes to JWT flow or existing endpoints.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;   // injected from CustomUserDetailsService

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless JWT — no cookies, no CSRF risk
                .csrf(AbstractHttpConfigurer::disable)

                // No server-side sessions — each request is independently authenticated via JWT
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Authorization rules
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()   // register, login — public
                        .requestMatchers("/actuator/health").permitAll()  // Docker/LB health check — no auth needed
                        // WebSocket HTTP handshake — permit at HTTP layer.
                        // Auth is enforced at the STOMP layer by JwtChannelInterceptor
                        // (STOMP CONNECT frame). Spring Security can't read JWT from
                        // STOMP frames, so the handshake itself must be open.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated()                      // everything else requires JWT
                )

                // Wire our custom AuthenticationProvider
                .authenticationProvider(authenticationProvider())

                // JWT filter runs before Spring's default username/password filter
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * DaoAuthenticationProvider — wires UserDetailsService + PasswordEncoder.
     * Used by AuthenticationManager during login flow.
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    /**
     * AuthenticationManager — entry point for programmatic authentication.
     * Injected into AuthService for the login flow (Phase 2).
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config)
            throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * BCryptPasswordEncoder — cost factor 10 (default).
     * Bean declaration here ensures single instance (Singleton pattern via Spring).
     * Injectable anywhere — EmailPasswordAuthStrategy, future strategies.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
