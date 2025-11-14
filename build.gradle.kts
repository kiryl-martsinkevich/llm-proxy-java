plugins {
    java
    application
}

group = "com.llmproxy"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    // Vert.x
    implementation(platform("io.vertx:vertx-stack-depchain:4.5.10"))
    implementation("io.vertx:vertx-core")
    implementation("io.vertx:vertx-web")
    implementation("io.vertx:vertx-web-client")

    // JSON Processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.1")

    // JSONPath
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Testing
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.vertx:vertx-junit5")
    testImplementation("org.assertj:assertj-core:3.27.0")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
}

application {
    mainClass.set("com.llmproxy.Main")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// Shadow JAR configuration (add shadow plugin back later if needed)
// tasks.shadowJar {
//     archiveBaseName.set("llm-proxy")
//     archiveClassifier.set("")
//     archiveVersion.set(project.version.toString())
//     mergeServiceFiles()
// }

// The 'run' task is already provided by the application plugin
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
