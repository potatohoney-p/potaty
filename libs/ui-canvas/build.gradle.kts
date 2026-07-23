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
    implementation(projects.commons)
    implementation(projects.graphicsgeo)
    implementation(projects.htmlDsl)
    implementation(projects.lifecycle)
    implementation(projects.livedata)
    implementation(projects.potatyBoard)
    implementation(projects.shapeInteractionBound)
    implementation(projects.uiAppStateManager)
    implementation(projects.uiModal)
    implementation(projects.uiTheme)

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
