# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src/main/ src/main/
RUN ./gradlew --no-daemon bootJar --console=plain

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
