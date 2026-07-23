/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

plugins {
    kotlin("js")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(projects.actionManager)
    implementation(projects.browserManager)
    implementation(projects.commons)
    implementation(projects.graphicsgeo)
    implementation(projects.keycommand)
    implementation(projects.lifecycle)
    implementation(projects.livedata)
    implementation(projects.potatyBoard)
    implementation(projects.potatyBitmap)
    implementation(projects.potatyBitmapManager)
    implementation(projects.shape)
    implementation(projects.shapeClipboard)
    implementation(projects.shapeSelection)
    implementation(projects.shapeSerialization)
    implementation(projects.statemanager)
    implementation(projects.storeManager)
    implementation(projects.uiAppStateManager)
    implementation(projects.uiCanvas)
    implementation(projects.uiToolbar)

    // --- Anything-to-Diagram: source-grounded generation wired into the editor ---
    implementation(projects.diagramIr)
    implementation(projects.layoutEngine)
    implementation(projects.renderCore)
    implementation(projects.rendererAscii)
    implementation(projects.workbenchClient)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.js)
}
val compilerType: org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType by ext
kotlin {
    js(compilerType) {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }
}
