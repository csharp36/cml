plugins {
    java
    application
}

group = "com.indexer"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // MCP SDK
    implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")

    // HTTP server
    implementation("io.javalin:javalin:6.4.0")

    // Database
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.jdbi:jdbi3-core:3.45.4")
    implementation("org.jdbi:jdbi3-postgres:3.45.4")
    implementation("org.jdbi:jdbi3-sqlobject:3.45.4")
    implementation("org.flywaydb:flyway-core:10.21.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.21.0")

    // Config
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Tree-sitter via JNR-FFI
    implementation("com.github.jnr:jnr-ffi:2.2.16")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("io.javalin:javalin-testtools:6.4.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

application {
    mainClass = "com.indexer.Application"
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    useJUnitPlatform {
        includeTags("integration")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
}
