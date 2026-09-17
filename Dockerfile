# Packages the server with the ffmpeg it shells out to, so the host only needs a
# container runtime. Debian's ffmpeg is built with libopus, which is the one
# codec that matters here.
#
# Build and run:
#   docker build -t lofi-server .
#   docker run --rm -p 8477:8477 lofi-server

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies change far less often than sources, so resolving them in their own
# layer keeps a code-only rebuild down to a few seconds.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /src/target/lofi-server.jar app.jar

ENV PORT=8477
EXPOSE 8477

# Reports the ffmpeg it found and the live stations, so a broken image fails the
# check rather than failing silently on the first listener.
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s \
  CMD curl -fsS "http://127.0.0.1:${PORT}/api/health" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
