FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

COPY knowledgebase/gradle/ gradle/
COPY knowledgebase/gradlew .
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY knowledgebase/build.gradle.kts knowledgebase/settings.gradle.kts ./
RUN ./gradlew dependencies --no-daemon || true

COPY knowledgebase/src/ src/
RUN ./gradlew bootJar --no-daemon -x test -x spotlessCheck

FROM eclipse-temurin:25-jre
WORKDIR /app

RUN mkdir -p /app/git-knowledge /app/vectorstore

COPY --from=build /build/build/libs/*.jar app.jar
COPY knowledgebase/src/main/resources/knowledgebase.yml /config/knowledgebase.yml

EXPOSE 8000

CMD ["java", "-jar", "app.jar"]
