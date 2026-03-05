package com.tripchat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JpaConfig — enables Spring Data JPA auditing.
 *
 * @EnableJpaAuditing activates AuditingEntityListener, which auto-populates
 * @CreatedDate and @LastModifiedDate fields on entity save and update.
 *
 * Separated from main application class to keep TripChatApplication clean
 * and to allow disabling auditing in specific test slices (@DataJpaTest).
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
