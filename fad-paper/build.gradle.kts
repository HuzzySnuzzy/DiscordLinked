plugins {
    java
}

group = "com.huzzysnuzzy"
version = rootProject.version

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Folia uses Paper's API. Built against 1.21.4 so it runs on Folia/Paper 1.21.4 and everything newer.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("DiscordLinkedFAD")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
