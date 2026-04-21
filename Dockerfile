# -------- Build stage --------
FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

# Copy pom first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests


# -------- Runtime stage --------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /app/target/graperank3-1.0-SNAPSHOT.jar app.jar

# Run the application
# Use shell form to allow JAVA_OPTS environment variable expansion
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
