FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Layer 1: Gradle wrapper (rarely changes)
COPY orchestrator/gradle/ gradle/
COPY orchestrator/gradlew .
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Layer 2: Local Maven artifacts not available in remote repos
COPY orchestrator/libs/ /root/.m2/repository/

# Layer 3: Build scripts + frontend dependency manifest
COPY orchestrator/build.gradle.kts orchestrator/settings.gradle.kts ./
COPY orchestrator/backend/build.gradle.kts backend/
COPY orchestrator/frontend/build.gradle.kts frontend/
COPY orchestrator/frontend/package.json orchestrator/frontend/package-lock.json frontend/
RUN ./gradlew :backend:dependencies :frontend:npmInstall --no-daemon

# Layer 4: Frontend source + build
COPY orchestrator/frontend/ frontend/
RUN ./gradlew :frontend:assembleFrontend --no-daemon

# Layer 5: Backend source + build (copies frontend dist into JAR)
COPY orchestrator/backend/src/ backend/src/
RUN ./gradlew :backend:bootJar --no-daemon -x test -x spotlessCheck

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends docker.io && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/backend/build/libs/*.jar app.jar
COPY orchestrator/backend/src/main/resources/orchestrator.yml /config/orchestrator.yml
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
