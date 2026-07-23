/*
 * Copyright (c) 2026, Potaty
 *
 * shared/diagram-demo — a Node-runnable harness that renders hand-written DiagramIR to ASCII so the
 * IR -> layout -> engine -> ASCII pipeline can be verified end-to-end without a browser.
 */
plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(projects.diagramIr)
    implementation(projects.rendererAscii)
    implementation(projects.layoutEngine)
    implementation(libs.kotlin.stdlib.js)
}

val compilerType: org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType by ext
kotlin {
    js(compilerType) {
        binaries.executable()
        nodejs()
    }
}
