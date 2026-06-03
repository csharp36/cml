plugins {
    application
    java
}
repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
dependencies {
    implementation("com.github.uwol:proleap-cobol-parser:v2.4.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
application { mainClass = "oracle.ExtractorMain" }
tasks.test { useJUnitPlatform() }
