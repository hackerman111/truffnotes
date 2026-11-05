plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "inkspace.store"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tn-core"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.bundled)

    testImplementation(project(":tn-test-support"))
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

configurations.matching { it.name.contains("UnitTest") }.configureEach {
    exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
}

tasks.register<Test>("faultStore") {
    group = "verification"
    description = "Runs seeded child-process fault injection schedules against :tn-store"
    val testTask = tasks.named<Test>("testDebugUnitTest")
    dependsOn(testTask)
    testClassesDirs = testTask.get().testClassesDirs
    classpath = testTask.get().classpath
    useJUnitPlatform()
    filter {
        includeTestsMatching("inkspace.store.F03FaultStoreChildProcessTest")
    }
    systemProperty("faultStore.seed", project.findProperty("seed")?.toString() ?: "20261105")
    systemProperty("faultStore.runs", project.findProperty("runs")?.toString() ?: "1000")
    outputs.upToDateWhen { false }
}
