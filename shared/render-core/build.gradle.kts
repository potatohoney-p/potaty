/*
 * Copyright (c) 2026, Potaty
 *
 * shared/render-core — maps a laid-out Diagram IR onto the EXISTING Potaty shape model
 * (Rectangle / Text / Line). This is the bridge that lets us reuse Potaty's beautiful ASCII engine
 * as the IR renderer instead of hand-concatenating characters.
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
    implementation(projects.layoutEngine)
    implementation(projects.graphicsgeo)
    implementation(projects.shape)
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
