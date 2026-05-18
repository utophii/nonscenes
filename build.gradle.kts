plugins {
    kotlin("jvm") version "2.3.0"
    id("java")
    id("com.gradleup.shadow") version "9.4.1"
}

group = "com.nonxedy"
version = "06-a"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")

    // LuckPerms API
    compileOnly("net.luckperms:api:5.4")

    // Kotlin runtime
    implementation(kotlin("stdlib"))

    // SnakeYAML for config
    implementation("org.yaml:snakeyaml:2.4")

    // Adventure text minimessage
    implementation("net.kyori:adventure-text-minimessage:4.24.0")

    // Apache Commons Lang
    implementation("org.apache.commons:commons-lang3:3.18.0")

    // Database dependencies
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.5.0")
    implementation("org.postgresql:postgresql:42.7.11")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.mongodb:mongodb-driver-sync:5.6.1")
    implementation("redis.clients:jedis:7.0.0")
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
}