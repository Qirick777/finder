plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    // `/evotest` maps to running this headless harness.
    // Usage: ./gradlew run --args="all"   (or genetics, etc.)
    mainClass.set("com.evosim.test.EvoTest")
}

tasks.named<JavaExec>("run") {
    // Let `gradlew run` inherit console args and print cleanly.
    standardInput = System.`in`
}
