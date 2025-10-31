# Backendcam – Dockerized Spring Boot (Maven)

This repository contains a Spring Boot 3 (Java 17) app. A multi-stage Dockerfile is included to build the app with Maven and run it on a slim JRE.

## How it works
- Build stage: Uses `maven:3.9.9-eclipse-temurin-17` to compile the project and produce a fat jar.
- Runtime stage: Uses `eclipse-temurin:17-jre-jammy` to run the jar.
- The image exposes port `8080` (configured in `src/main/resources/application.properties`).
- The build context excludes the `Docker/` folder and other heavy assets via `.dockerignore` (your requested behavior).

## Optional: Build and run (PowerShell)
The following commands are provided as optional references if you want to build and run locally with Docker:

```powershell
# Build the image (from the project root)
docker build -t backendcam:latest .

# Run the container, mapping host port 8080 to container port 8080
docker run --rm -p 8080:8080 --name backendcam backendcam:latest

# (Optional) Pass JVM options
# docker run --rm -e JAVA_OPTS="-Xms256m -Xmx512m" -p 8080:8080 backendcam:latest
```

## Or, use Docker Compose (recommended)
Simple one-liner to build and start the app:

```powershell
# Build and start (from the project root)
docker compose up --build -d

# View logs
docker compose logs -f

# Stop and remove containers
docker compose down
```

Notes:
- The compose file mounts `./hls` to `/hls` to match `hls.server.path=/hls`.
- The `Docker/` folder is not used by this compose file and is excluded from the build context.

## Notes

## Troubleshooting
- If the Docker build fails on the Maven step due to network/cache issues, try rebuilding with `--no-cache`.
- If your host lacks Docker permissions, ensure Docker Desktop is running and you have access.
- If port `8080` is in use, change the host mapping (e.g., `-p 8081:8080`).