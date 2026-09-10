plugins {
    kotlin("jvm") version "2.4.20-RC"
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.nonxedy"
version = "09-a"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
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

    // SnakeYAML for config
    implementation("org.yaml:snakeyaml:2.6")

    // Adventure text minimessage
    implementation("net.kyori:adventure-text-minimessage:4.24.0")

    // Apache Commons Lang
    implementation("org.apache.commons:commons-lang3:3.20.0")

    // Database dependencies
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.5.0")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mongodb:mongodb-driver-sync:5.6.1")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand(
                "version" to project.version,
                "name" to project.name
            )
        }
    }

    jar {
        enabled = false
    }

    shadowJar {
        archiveClassifier.set("")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }
}