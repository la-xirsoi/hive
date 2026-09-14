plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    kotlin("plugin.jpa") version "2.4.20"
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

group = "hive"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Spring Boot starters -------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // --- Kotlin ---------------------------------------------------------------
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // --- Persistence / migrations --------------------------------------------
    // Spring Boot 4 moved Flyway's auto-configuration out of
    // spring-boot-autoconfigure into its own module. Without it, flyway-core is
    // on the classpath but never runs and every `spring.flyway.*` property is
    // silently ignored -- so the migrations would be dead files.
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-sqlserver")
    runtimeOnly("com.microsoft.sqlserver:mssql-jdbc")

    // --- Test -----------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Kover is wired up so that `./gradlew koverHtmlReport` / `koverXmlReport` work.
// NOTE: no coverage verification rule is configured yet -- a later issue owns
// the 70% line-coverage gate mandated by the spec.
kover {
    reports {
        filters {
            excludes {
                // The Spring Boot entry point has no meaningful logic to cover.
                classes("hive.HiveApplicationKt", "hive.HiveApplication")
            }
        }
    }
}
