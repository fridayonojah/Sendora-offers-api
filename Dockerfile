# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# The cache mount keeps ~/.m2 between builds, so dependencies are downloaded once and a
# failed download resumes from what is already cached instead of starting over.
#
# Tests are skipped here: the integration tests use Testcontainers and need a Docker daemon,
# which is not available inside a build. Run `mvn test` on the host or in CI instead.
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests

# ---------- Stage 2: runtime ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as an unprivileged user.
RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build --chown=app:app /workspace/target/*.jar app.jar
USER app

# Size the heap from the container's memory limit rather than the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
