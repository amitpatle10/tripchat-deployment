package com.tripchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TripChat Backend — Entry Point
 *
 * @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan
 * Auto-configuration wires Spring MVC, JPA, Security, Redis, Kafka based on
 * what's on the classpath — convention over configuration.
 *
 * @EnableAspectJAutoProxy activates AOP proxy creation for our execution-time
 * logging aspect (CLAUDE.md requirement). Uses JDK dynamic proxies by default;
 * proxyTargetClass=true would use CGLIB for class-based proxying.
 */
@SpringBootApplication
@EnableAspectJAutoProxy
@EnableScheduling   // activates @Scheduled on OutboxRelay
public class TripChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripChatApplication.class, args);
    }
}
