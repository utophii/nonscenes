plugins {
    kotlin("jvm") version "2.4.20-RC"
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.nonxedy"
version = "11-a"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

// Resolved by the server from Maven Central, see plugin.yml `libraries`
val runtimeLibraries = listOf(
    "com.zaxxer:HikariCP:7.0.2",
    "org.postgresql:postgresql:42.7.12",
    "org.mongodb:mongodb-driver-sync:5.6.1"
)

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven {
        name = "faststatsReleases"
        url = uri("https://repo.faststats.dev/releases")
    }
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // LuckPerms API
    compileOnly("net.luckperms:api:5.4")

    // PacketEvents for ASYNC_PACKET playback mode
    compileOnly("com.github.retrooper:packetevents-spigot:2.7.0")

    // Kotlin runtime
    implementation(kotlin("stdlib"))

    // FastStats metrics and error tracking
    implementation("dev.faststats.metrics:bukkit:0.30.2")

    // SQLite and MySQL drivers are bundled with the server
    compileOnly("org.xerial:sqlite-jdbc:3.49.1.0")

    // Downloaded by the server at startup via plugin.yml `libraries`
    runtimeLibraries.forEach { compileOnly(it) }

    // Test dependencies
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        val libraries = runtimeLibraries.joinToString("\n") { "  - $it" }
        inputs.property("libraries", libraries)
        filesMatching("plugin.yml") {
            expand(
                "version" to project.version,
                "name" to project.name,
                "libraries" to libraries
            )
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "com.nonxedy.libs.kotlin")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}