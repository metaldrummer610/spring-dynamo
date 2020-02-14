val assertjVersion: String by project
val awaitilityVersion: String by project
val detektToolVersion: String by project
val gradleVersion: String by project
val jacocoToolVersion: String by project
val jupiterVersion: String by project
val ktlintVersion: String by project
val dynamoDbSDKVersion: String by project

val nexusUrl: String by project
val nexusUser: String by project
val nexusPassword: String by project
val patchVersion: String by project
val baseVersion: String by project

// IMPORTANT!
// The Kotlin Version must be kept in sync both here and in the dependencyManagement section!
plugins {
    val kotlinVersion = "1.3.60"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("plugin.noarg") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    id("io.spring.dependency-management") version "1.0.8.RELEASE"
    id("org.jlleitschuh.gradle.ktlint") version "9.1.1"
    id("com.github.nwillc.vplugin") version "3.0.1"
    id("io.gitlab.arturbosch.detekt") version "1.1.1"
    id("org.sonarqube") version "2.8"
    id("com.avast.gradle.docker-compose") version "0.10.7"
    jacoco
    maven
    `maven-publish`
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:2.2.0.RELEASE") {
            bomProperty("kotlin.version", "1.3.60")
        }
        mavenBom("software.amazon.awssdk:bom:2.5.29")
    }
}

group = "com.group1001"
version = "$baseVersion.$patchVersion"

repositories {
    jcenter()
    maven(url = "http://nexus.jx.group1001.services/repository/group1001-maven/")
    maven(url = "https://oss.sonatype.org/content/repositories/snapshots")
    mavenLocal()
}

apply(plugin = "io.spring.dependency-management")

dependencies {
    listOf(
        kotlin("stdlib-jdk8"),
        kotlin("reflect"),
        "software.amazon.awssdk:dynamodb",
        "org.springframework.boot:spring-boot-starter",
        "org.reflections:reflections:0.9.11",
        "org.awaitility:awaitility:$awaitilityVersion"
    ).forEach { api(it) }

    listOf(
        "org.junit.jupiter:junit-jupiter:$jupiterVersion",
        "org.springframework.boot:spring-boot-starter-test"
    ).forEach { testImplementation(it) }

    listOf(
        kotlin("reflect"),
        "org.junit.jupiter:junit-jupiter-engine:$jupiterVersion",
        "org.junit.vintage:junit-vintage-engine:$jupiterVersion"
    ).forEach { testRuntime(it) }

    kapt("org.springframework.boot:spring-boot-configuration-processor")
}

tasks {
    named("sonarqube") {
        dependsOn("jacocoTestReport")
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = JavaVersion.VERSION_1_8.toString()
        kotlinOptions.freeCompilerArgs += "-Xuse-experimental=kotlin.Experimental" // Have to have this compiler param because we are using experimental reflection apis
    }
    withType<JacocoReport> {
        dependsOn("test")
        reports {
            xml.apply {
                isEnabled = true
            }
            html.apply {
                isEnabled = true
            }
        }
    }
    withType<Test> {
        useJUnitPlatform()
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
    withType<Wrapper> {
        this.gradleVersion = "5.6.3" // version required
    }
    named("check") {
        dependsOn("jacocoTestCoverageVerification")
    }
    withType<Test> {
        useJUnitPlatform()
    }
    withType<io.gitlab.arturbosch.detekt.Detekt> {
        // Target version of the generated JVM bytecode. It is used for type resolution.
        this.jvmTarget = "1.8"
    }
}

ktlint {
    ignoreFailures.set(true)
    version.set(ktlintVersion)
}

detekt {
    toolVersion = detektToolVersion
    config = files("detekt-config.yml")
}

jacoco {
    toolVersion = jacocoToolVersion
}

sonarqube {
    properties {
        property("sonar.projectName", "daap-dynamo")
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

noArg {
    annotation("com.group1001.daap.dynamo.Throughput")
}

publishing {
    repositories {
        maven {
            url = uri(nexusUrl)
            credentials {
                username = nexusUser
                password = nexusPassword
            }
        }
    }
    publications {
        register("mavenJava", MavenPublication::class) {
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }
}
