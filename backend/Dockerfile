# syntax=docker/dockerfile:1
# Multi-stage build for the backend. Build context: this backend/ folder.
#   docker build -t eventbooking-backend .
# Runs with the prod profile; every secret comes from the environment (see docs/deployment.md).

# ---- build: compile and package with Maven, then split the jar into layers ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

# Dependencies first: this layer is reused until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN mvn -B -q package -DskipTests \
 && cp target/eventbooking-*.jar app.jar \
 && java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app

# Least- to most-frequently changing, so a code change only replaces the last layer.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER app
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
