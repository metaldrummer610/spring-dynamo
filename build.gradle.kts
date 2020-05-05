val assertjVersion: String by project
val awaitilityVersion: String by project
val detektToolVersion: String by project
val gradleVersion: String by project
val jacocoToolVersion: String by project
val jupiterVersion: String by project
val ktlintVersion: String by project
val dynamoDbSDKVersion: String by project

// IMPORTANT!
// The Kotlin Version must be kept in sync both here and in the dependencyManagement section!
plugins {
    val kotlinVersion = "1.3.71"

    kotlin("jvm") version kotlinVersion
    kotlin("plugin.spring") version kotlinVersion
    kotlin("kapt") version kotlinVersion
    id("org.jetbrains.dokka") version "0.10.0"
    id("io.spring.dependency-management") version "1.0.9.RELEASE"
    id("com.jfrog.bintray") version "1.8.4"
    jacoco
    maven
    `maven-publish`
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:2.2.6.RELEASE") {
            bomProperty("kotlin.version", "1.3.71")
        }
        mavenBom("software.amazon.awssdk:bom:2.5.29")
    }
}

group = "com.github.metaldrummer610"
version = "0.2.3"

repositories {
    mavenCentral()
    jcenter()
    maven("http://oss.jfrog.org/artifactory/oss-snapshot-local/")
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
        "org.springframework.boot:spring-boot-starter-test",
        "com.playtika.testcontainers:embedded-dynamodb:1.48",
        "org.springframework.cloud:spring-cloud-starter:2.2.2.RELEASE",
        "com.playtika.testcontainers:testcontainers-spring-boot:1.48"
    ).forEach { testImplementation(it) }

    listOf(
        kotlin("reflect"),
        "org.junit.jupiter:junit-jupiter-engine:$jupiterVersion",
        "org.junit.vintage:junit-vintage-engine:$jupiterVersion"
    ).forEach { testRuntime(it) }

    kapt("org.springframework.boot:spring-boot-configuration-processor")
}

tasks {
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
    withType<org.jetbrains.dokka.gradle.DokkaTask> {
        outputFormat = "html"
        outputDirectory = "$buildDir/dokka"
        configuration {
            includes = listOf("Module.md")
        }
    }
    withType<GenerateMavenPom> {
        destination = file("$buildDir/libs/${project.name}-${project.version}.pom")
    }
    withType<com.jfrog.bintray.gradle.tasks.BintrayUploadTask> {
        onlyIf {
            if (project.version.toString().contains('-')) {
                logger.lifecycle("Version v${project.version} is not a release version - skipping upload.")
                false
            } else {
                true
            }
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    classifier = "sources"
    from(sourceSets["main"].allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    dependsOn("dokka")
    classifier = "javadoc"
    from("$buildDir/dokka")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()

            from(components["java"])
            artifact(sourcesJar.get())
            artifact(javadocJar.get())
        }
    }
}

bintray {
    user = System.getenv("BINTRAY_USER")
    key = System.getenv("BINTRAY_API_KEY")
    dryRun = false
    publish = true
    setPublications("maven")
    pkg(delegateClosureOf<com.jfrog.bintray.gradle.BintrayExtension.PackageConfig> {
        repo = "maven"
        name = project.name
        desc = "Spring Data inspired DynamoDB wrapper written in Kotlin"
        websiteUrl = "https://github.com/metaldrummer610/${project.name}"
        issueTrackerUrl = "https://github.com/metaldrummer610/${project.name}/issues"
        vcsUrl = "https://github.com/metaldrummer610/${project.name}.git"
        version.vcsTag = "v${project.version}"
        setLicenses("ISC")
        setLabels("kotlin", "dynamo", "spring")
        publicDownloadNumbers = true
    })
}
