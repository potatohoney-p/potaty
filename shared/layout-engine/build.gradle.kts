/*
 * Copyright (c) 2026, Potaty
 *
 * shared/layout-engine — deterministic, coordinate-assigning layout for the Diagram IR.
 * Aesthetic quality lives here (Sugiyama crossing reduction, orthogonal routing, balanced
 * spacing). Pure Kotlin; depends only on graphicsgeo (integer geometry) and diagram-ir.
 */
plugins {
    kotlin("js")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(projects.commons)
    implementation(projects.diagramIr)
    implementation(projects.graphicsgeo)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.stdlib.js)
    testImplementation(libs.kotlin.test.js)
}

val compilerType: org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType by ext
kotlin {
    js(compilerType) {
        browser()
        nodejs()
    }
}
