/*
 * Copyright (c) 2026, Potaty
 *
 * shared/renderer-ascii — headless IR -> ASCII using the EXISTING Potaty engine
 * (PotatyBitmapManager -> PotatyBoard -> toStringInBound). No hand-concatenated characters.
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
    implementation(projects.layoutEngine)
    implementation(projects.renderCore)
    implementation(projects.graphicsgeo)
    implementation(projects.shape)
    implementation(projects.potatyBitmap)
    implementation(projects.potatyBitmapManager)
    implementation(projects.potatyBoard)
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
