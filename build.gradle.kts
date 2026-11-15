plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("io.ktor.plugin") version "3.6.0"
}

group = "com.example.cricketscoring"
version = "0.1.0"

application {
    mainClass.set("com.example.cricketscoring.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-netty:3.6.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    implementation("io.ktor:ktor-server-status-pages:3.6.0")
    implementation("io.ktor:ktor-server-request-validation:3.6.0")
    implementation("io.ktor:ktor-server-call-logging:3.6.0")
    implementation("io.ktor:ktor-server-auth:3.6.0")
    implementation("io.ktor:ktor-server-auth-jwt:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("org.jetbrains.exposed:exposed-core:1.6.1")
    implementation("org.jetbrains.exposed:exposed-jdbc:1.6.1")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("org.flywaydb:flyway-core:11.14.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.14.1")

    implementation("ch.qos.logback:logback-classic:1.5.21")

    implementation("com.auth0:java-jwt:4.6.0")
    implementation("com.auth0:jwks-rsa:0.24.1")
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("io.ktor:ktor-client-core:3.6.0")
    implementation("io.ktor:ktor-client-cio:3.6.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.0")

    testImplementation("io.ktor:ktor-server-test-host:3.6.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.6.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.4.20")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

tasks.test {
    useJUnitPlatform()
    // Docker Engine 29+ rejects API versions below 1.40; Testcontainers 1.21.x (docker-java) defaults lower.
    systemProperty("api.version", "1.44")
}

// `./gradlew run` loads KEY=VALUE pairs from .env (if present) without overriding real env vars.
tasks.named<JavaExec>("run") {
    val dotEnv = rootProject.file(".env")
    if (dotEnv.isFile) {
        dotEnv.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val key = line.substringBefore("=").trim()
                val value = line.substringAfter("=").trim().removeSurrounding("\"")
                if (System.getenv(key) == null) environment(key, value)
            }
    }
}
