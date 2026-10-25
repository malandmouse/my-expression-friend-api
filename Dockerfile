# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

# Windows checkouts can contain CRLF line endings in the wrapper script.
RUN sed -i 's/\r$//' gradlew \
    && sh ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/uploads \
    && chown app:app /app/uploads

COPY --from=build /workspace/build/libs/*.jar /app/app.jar

ENV SPRING_PROFILE=prod
USER app

# Spring Boot uses Render's PORT environment variable when it is provided.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/app.jar"]
