plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(11)
}

kotest {
    alwaysRerunTests = true
}

ktlint {
    version.set(libs.versions.ktlintEngine)
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)

    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

dependencies {
    implementation(project(":mavlink"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
