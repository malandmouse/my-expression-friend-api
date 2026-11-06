# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

COPY src/main/ src/main/
RUN ./gradlew --no-daemon bootJar --console=plain

FROM eclipse-temurin:21-jre-jammy AS java-runtime

# One deployable service: Java API + private Node worker + Python Typecast bridge.
# Node's Debian runtime is newer than the copied Jammy JRE's minimum glibc.
FROM node:24-bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends python3 ca-certificates libstdc++6 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
ENV PYTHONDONTWRITEBYTECODE=1
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY worker/ /app/worker/
COPY runtime/ /app/runtime/
RUN java -version && node --version && python3 --version
RUN python3 /app/runtime/preflight.py --image
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD ["python3", "/app/runtime/serve.py", "--healthcheck"]
ENTRYPOINT ["python3", "/app/runtime/serve.py"]
