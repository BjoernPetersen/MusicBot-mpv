import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.github.ben-manes.versions") version Plugin.VERSIONS
    kotlin("jvm") version Plugin.KOTLIN
    idea

    id("com.github.spotbugs") version Plugin.SPOTBUGS_PLUGIN

    id("com.github.johnrengelman.shadow") version Plugin.SHADOW_JAR
}

group = "com.github.bjoernpetersen"
version = "0.3.0"

repositories {
    jcenter()
    // maven("https://oss.sonatype.org/content/repositories/snapshots")
}

idea {
    module {
        isDownloadJavadoc = true
    }
}

spotbugs {
    isIgnoreFailures = true
    toolVersion = Plugin.SPOTBUGS_TOOL
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
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
    implementation(
        group = "io.github.microutils",
        name = "kotlin-logging",
        version = Lib.KOTLIN_LOGGING) {
        exclude("org.slf4j")
        exclude("org.jetbrains")
        exclude("org.jetbrains.kotlin")
    }
    compileOnly(
        group = "com.github.bjoernpetersen",
        name = "musicbot",
        version = Lib.MUSICBOT)

    implementation(
        group = "com.github.bjoernpetersen",
        name = "musicbot-youtube",
        version = Lib.YOUTUBE_PROVIDER)

    testImplementation(
        group = "org.junit.jupiter",
        name = "junit-jupiter-api",
        version = Lib.JUNIT)
    testRuntime(
        group = "org.junit.jupiter",
        name = "junit-jupiter-engine",
        version = Lib.JUNIT)
    testImplementation(group = "io.mockk", name = "mockk", version = Lib.MOCK_K)
    testImplementation(group = "org.assertj", name = "assertj-core", version = Lib.ASSERT_J)
}
