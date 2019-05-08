import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.ben-manes.versions") version Plugin.VERSIONS
    kotlin("jvm") version Plugin.KOTLIN
    idea

    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

group = "com.github.bjoernpetersen"
version = "0.6.0"

repositories {
    jcenter()
    maven("https://oss.sonatype.org/content/repositories/snapshots") {
        mavenContent {
            snapshotsOnly()
        }
    }
}

idea {
    module {
        isDownloadJavadoc = true
    }
}

tasks {
    "compileKotlin"(KotlinCompile::class) {
        kotlinOptions.jvmTarget = "1.8"
    }

    "compileTestKotlin"(KotlinCompile::class) {
        kotlinOptions.jvmTarget = "1.8"
    }

    "test"(Test::class) {
        useJUnitPlatform()
    }
}

dependencies {
    compileOnly(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT)

    implementation(
        group = "com.github.bjoernpetersen",
        name = "musicbot-youtube",
        version = Lib.YOUTUBE_PROVIDER)

    implementation(
        group = "com.zaxxer",
        name = "nuprocess",
        version = Lib.NU_PROCESS)

    testImplementation(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT)
    testImplementation(
        group = "org.junit.jupiter",
        name = "junit-jupiter-api",
        version = Lib.JUNIT)
    testRuntime(
        group = "org.junit.jupiter",
        name = "junit-jupiter-engine",
        version = Lib.JUNIT)
}
