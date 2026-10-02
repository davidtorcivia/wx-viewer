import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "zone.disinfo.wx.macrobenchmark"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents { beforeVariants { it.enable = it.buildType == "benchmark" } }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// This pixel oracle has no Android dependencies. Run its adversarial composites
// on the host, reusing exactly the helper compiled into the live preview test APK.
tasks.register<JavaExec>("testPlaybackShapeOracle") {
    group = "verification"
    description = "Checks the live playback shape oracle against transparent-map and malformed-fill pixels"
    val compiledTests = tasks.named<KotlinCompile>("compileBenchmarkKotlin")
    dependsOn(compiledTests)
    classpath(compiledTests.flatMap { it.destinationDirectory },
        configurations.named("benchmarkRuntimeClasspath"))
    mainClass.set("org.junit.runner.JUnitCore")
    args("zone.disinfo.wx.macrobenchmark.RadarPlaybackShapeOracleTest")
}
