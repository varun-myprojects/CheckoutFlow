# =========================================================================
# Stage 1: Build Stage (Maven + JDK 21 Alpine)
# =========================================================================
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Cache Maven dependencies by copying pom.xml first
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy application source code and compile fat jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# =========================================================================
# Stage 2: Runtime Stage (Lightweight Temurin JRE 21 Alpine)
# =========================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

# Security: create non-root application user and group
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy compiled jar from builder stage
COPY --from=builder /build/target/checkout-engine-0.0.1-SNAPSHOT.jar app.jar

# Enforce non-root ownership
RUN chown -R appuser:appgroup /app

USER appuser

# Expose application port
EXPOSE 8080

# Container liveness probe via Spring Boot Actuator
HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=40s \
  CMD wget -q -O - http://localhost:8080/actuator/health || exit 1

# Containerized JVM execution parameters
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:InitialRAMPercentage=50.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
