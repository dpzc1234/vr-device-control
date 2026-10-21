plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.vrdevicecontrol.simulator.MainKt")
}

dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-protocol"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
}
