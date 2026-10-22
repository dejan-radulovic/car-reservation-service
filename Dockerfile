# syntax=docker/dockerfile:1

# ---- build: compile and package with the Maven wrapper (tests run outside the image: ./mvnw verify)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# dependencies first, so source changes do not invalidate this layer; the cache mount keeps ~/.m2 across builds
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests -Djacoco.skip=true \
    && java -Djarmode=tools -jar target/car-reservation-service-*.jar extract --layers --launcher --destination extracted

# ---- runtime: JRE only, non-root, one image layer per Spring Boot layer (dependencies change least often)
FROM eclipse-temurin:25-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
USER app

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# size the heap from the container memory limit rather than the host's RAM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080

# the image has neither curl nor wget; bash's /dev/tcp is enough for a plain HTTP probe
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=3 CMD ["bash", "-c", \
    "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health/readiness HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
