import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.scotty"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.scotty"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("SCOTTY_VERSION_CODE")?.toIntOrNull() ?: 7)
        versionName = (System.getenv("SCOTTY_VERSION") ?: "0.4.2")
    }

    // Release signing comes from the environment (CI secrets or a local keystore); without it the
    // release build falls back to the debug key so it can still be sideloaded.
    val releaseKeystore = System.getenv("SCOTTY_KEYSTORE")?.let { project.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SCOTTY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCOTTY_KEY_ALIAS") ?: "scotty"
                keyPassword = System.getenv("SCOTTY_KEY_PASSWORD") ?: System.getenv("SCOTTY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    // Optional "power mode": shell-level capture + one-time permanent grants via Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}


// ---- Rust core ------------------------------------------------------------------------------
// Builds core/ for the phone (arm64) and for Waydroid / emulators (x86_64) via cargo-ndk.
val cargoBuild by tasks.registering(Exec::class) {
    val core = rootProject.file("core")
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs").asFile
    workingDir = core
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a", "-t", "x86_64", "--platform", "30",
        "-o", jniLibs.absolutePath, "build", "--release",
    )
    inputs.dir(core.resolve("src"))
    inputs.file(core.resolve("Cargo.toml"))
    outputs.dir(jniLibs)
}
tasks.named("preBuild") { dependsOn(cargoBuild) }
