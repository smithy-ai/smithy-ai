import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.3"
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
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("org.springframework.boot:spring-boot-starter-web")
implementation("com.hubspot.jinjava:jinjava:2.8.3")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("dev.smithy-ai:forgejo-client:14.0.3")
    implementation("com.github.victools:jsonschema-generator:4.38.0")
    implementation("com.github.victools:jsonschema-module-jackson:4.38.0")



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
