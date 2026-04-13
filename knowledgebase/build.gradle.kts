import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "3.5.7"
    id("com.diffplug.spotless") version "7.0.3"
}

group = "dev.smithyai"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone")
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("org.springframework.ai:spring-ai-bom:1.1.4"))

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

spotless {
    java {
        toggleOffOn()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        prettier(mapOf("prettier" to "3.5.3", "prettier-plugin-java" to "2.8.1"))
            .config(mapOf(
                "tabWidth" to 4,
                "useTabs" to false,
                "printWidth" to 120,
                "plugins" to listOf("prettier-plugin-java")
            ))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
