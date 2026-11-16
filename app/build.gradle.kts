import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.musiccut.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.musiccut.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every GitHub Release installs as an update.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"
    }

    // Public builds never contain or depend on a committed signing key.
    // Release signing is enabled only when a private keystore is supplied in CI/local env.
    val releaseKeystorePath = System.getenv("MUSICCUT_RELEASE_KEYSTORE")
    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("musiccutRelease") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("MUSICCUT_RELEASE_STORE_PASSWORD")
                    ?: error("MUSICCUT_RELEASE_STORE_PASSWORD is required")
                keyAlias = System.getenv("MUSICCUT_RELEASE_KEY_ALIAS")
                    ?: error("MUSICCUT_RELEASE_KEY_ALIAS is required")
                keyPassword = System.getenv("MUSICCUT_RELEASE_KEY_PASSWORD")
                    ?: error("MUSICCUT_RELEASE_KEY_PASSWORD is required")
            }
        }
    }

    buildTypes {
        release {
            if (!releaseKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("musiccutRelease")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Google Drive upload: Google Identity authorization + Drive REST v3
    // AI stem separation runtime (MDX-Net ONNX models)
    implementation(libs.onnxruntime.android)

    implementation(libs.play.services.auth)
    implementation(libs.okhttp)
}
