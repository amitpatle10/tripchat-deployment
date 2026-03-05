package com.tripchat.config;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Execution Time Logging Aspect — Decorator Pattern via AOP
 *
 * Pattern: Decorator (via AOP Proxy)
 * Why here: Every service method needs execution time logging (CLAUDE.md requirement).
 * Adding timing code to each method manually violates DRY and pollutes business logic.
 * AOP wraps every service method transparently — the service has zero awareness of it.
 *
 * How it works:
 * Spring creates a proxy around every @Service bean. When a method is called,
 * the proxy intercepts it, runs this @Around advice, which times the real method call.
 * The real method (joinPoint.proceed()) runs inside our timing wrapper.
 *
 * Alternative: Manual System.currentTimeMillis() in every method — noisy, duplicated.
 * Alternative: Micrometer/Actuator metrics — better for production dashboards,
 *              overkill for our learning + latency target verification use case.
 *
 * Pointcut: execution(* com.tripchat.service..*(..))
 *   *         = any return type
 *   com.tripchat.service.. = service package and all sub-packages
 *   *(..))    = any method name, any parameters
 */
@Aspect
@Component
@Slf4j
public class ExecutionTimeAspect {

    @Around("execution(* com.tripchat.service..*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();

        Object result = joinPoint.proceed(); // execute the actual method

        long elapsed = System.currentTimeMillis() - start;
        String methodName = joinPoint.getSignature().toShortString();

        // Log at WARN if above our 20ms target (CLAUDE.md latency requirement)
        if (elapsed > 20) {
            log.warn("[SLOW] {} completed in {}ms — target is <20ms", methodName, elapsed);
        } else {
            log.debug("[AOP] {} completed in {}ms", methodName, elapsed);
        }

        return result;
    }
}
