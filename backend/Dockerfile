# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Use the full Maven image with JDK 21 to compile and package the JAR.
# The fat JAR produced by spring-boot-maven-plugin is self-contained.
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom.xml first and resolve dependencies in a separate layer.
# This layer is cached as long as pom.xml doesn't change, making
# subsequent builds fast (only source recompiles, not dependency download).
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Now copy source and build. -DskipTests because tests run in CI, not here.
COPY src ./src
RUN mvn package -DskipTests -B


# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# eclipse-temurin:21-jre-jammy is the slim JRE-only image (~200MB vs ~600MB for JDK).
# We don't need Maven or the JDK compiler at runtime.
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy only the fat JAR from the build stage — nothing else from the build layer
# (no Maven cache, no source files) ends up in the final image.
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Use exec form (not shell form) so the JVM process receives OS signals directly.
# This ensures graceful shutdown when Docker sends SIGTERM.
# JVM flags:
#   -XX:+UseContainerSupport  — respect cgroup CPU/memory limits (default in JDK 11+)
#   -XX:MaxRAMPercentage=75.0 — use 75% of container memory for heap, leave room for OS
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
