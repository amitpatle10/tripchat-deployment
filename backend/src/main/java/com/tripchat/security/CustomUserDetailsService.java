package com.tripchat.security;

import com.tripchat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * CustomUserDetailsService — loads user by email for Spring Security.
 *
 * Extracted into its own @Service to break the circular dependency:
 *   SecurityConfig defines UserDetailsService + imports JwtAuthFilter
 *   JwtAuthFilter needs UserDetailsService → cycle.
 *
 * Fix: UserDetailsService lives here, independent of SecurityConfig.
 *   Both SecurityConfig and JwtAuthFilter inject this bean — no cycle.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getEmail())
                        .password(user.getPasswordHash() != null ? user.getPasswordHash() : "")
                        .authorities("ROLE_USER")
                        .accountLocked(!user.isActive())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
    }
}
