# syntax=docker/dockerfile:1.7

# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    for i in 1 2 3; do \
        mvn -B -Dmaven.wagon.http.retryHandler.count=5 dependency:go-offline && exit 0; \
        echo "Retrying Maven dependency download ($i/3)..." >&2; \
        sleep 5; \
    done; \
    exit 1

# Copy source code and build
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/target/crm-kimtele-be-0.0.1-SNAPSHOT.jar app.jar

# Create uploads directory
RUN mkdir -p /app/uploads

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]

