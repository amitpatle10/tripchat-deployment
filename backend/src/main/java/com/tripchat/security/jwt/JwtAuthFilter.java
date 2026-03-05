package com.tripchat.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JwtAuthFilter — validates JWT on every incoming request.
 *
 * Pattern: Chain of Responsibility (Servlet Filter Chain)
 * This filter sits in the Spring Security filter chain.
 * It runs once per request (OncePerRequestFilter guarantees this).
 *
 * Flow:
 *   1. Extract token from Authorization header (Bearer <token>)
 *   2. Validate token via JwtService
 *   3. Load user from DB via UserDetailsService
 *   4. Set authentication in SecurityContextHolder
 *   5. Pass request down the filter chain
 *
 * If token is missing or invalid → skip authentication, pass through.
 * Spring Security will then reject the request if the endpoint requires auth.
 *
 * Thread safety:
 *   SecurityContextHolder uses ThreadLocal by default — each request thread
 *   has its own SecurityContext. No shared mutable state between threads.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // No token — pass through, Spring Security handles authorization
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7); // strip "Bearer "
        final String email;

        try {
            email = jwtService.extractEmail(token);
        } catch (Exception e) {
            log.debug("Failed to extract email from JWT: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // Only authenticate if not already authenticated in this request
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            if (jwtService.isTokenValid(token)) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                // Create authentication token and set in context
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        filterChain.doFilter(request, response);
    }
}
