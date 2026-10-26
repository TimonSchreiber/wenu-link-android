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
    testImplementation(project(":mavlink"))
    testImplementation(project(":state-model"))

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // Passes Kotest's seed settings from -P to the test JVM, where PropertyTesting reads them.
    // Used by save-run.sh and run-mutants.sh in the thesis repo.
    listOf(
        "kotest.proptest.default.seed",
        "kotest.proptest.seed.write-failed",
        "kotest.proptest.seed.fail-if-set"
    ).forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty(name, it) }
    }
}
