plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.huzzysnuzzy"
version = "0.14.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    // Discord bot library. Audio is not needed, so skip its native/crypto deps.
    implementation("net.dv8tion:JDA:6.7.0") {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Velocity already provides SLF4J; shading a second copy breaks logging.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}
