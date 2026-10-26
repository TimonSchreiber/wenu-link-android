plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
}

kotlin {
    jvmToolchain(11)
}

kotest {
    alwaysRerunTests = true
}

dependencies {
    testImplementation(project(":mavlink"))
    testImplementation(project(":state-model"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
