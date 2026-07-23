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
    implementation(projects.storeDao)
    implementation(projects.lifecycle)
    implementation(projects.livedata)

    implementation(libs.kotlin.stdlib.js)
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
