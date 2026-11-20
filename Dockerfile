# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21.0.13_9-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && mv target/selfhost-crm-*.jar target/app.jar \
    && java -Djarmode=tools -jar target/app.jar extract --layers --destination target/extracted

# ---- Runtime stage ----
FROM eclipse-temurin:21.0.13_9-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/target/extracted/application/ ./
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -fs http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1
ENV TZ=UTC
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
