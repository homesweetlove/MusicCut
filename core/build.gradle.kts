import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Pure-Java LAME port (only the de.sciss.jump3r.mp3 package is used: no javax.sound needed)
    api(libs.jump3r)
    // ONNX Runtime API for AI stem separation; the app supplies onnxruntime-android at runtime
    compileOnly(libs.onnxruntime.jvm)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
}

tasks.test {
    // Optional AI-separation test: -PmdxModel=/path/to/UVR-MDX-NET-Voc_FT.onnx -PmdxRef=/path/ref-dir
    project.findProperty("mdxModel")?.let { systemProperty("mdxModel", it) }
    project.findProperty("mdxRef")?.let { systemProperty("mdxRef", it) }
    maxHeapSize = "3g"
}
