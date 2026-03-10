FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Layer 1: Gradle wrapper (rarely changes)
COPY orchestrator/gradle/ gradle/
COPY orchestrator/gradlew .
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Layer 2: Local Maven artifacts not available in remote repos
COPY orchestrator/libs/ /root/.m2/repository/

# Layer 3: Dependencies (changes when build scripts change)
COPY orchestrator/build.gradle.kts orchestrator/settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon

# Layer 3: Build (changes when source changes)
COPY orchestrator/src/ src/
RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends docker.io && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/build/libs/*.jar app.jar
COPY orchestrator/src/main/resources/orchestrator.yml /config/orchestrator.yml
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
