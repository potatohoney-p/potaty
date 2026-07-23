/*
 * Copyright (c) 2026, Potaty
 *
 * shared/diagram-ir — the canonical, coordinate-free, evidence-linked Diagram IR.
 * Pure Kotlin (only kotlinx.serialization). Kotlin Multiplatform (js + jvm): the JS target
 * feeds the browser engine and the Node demo; the JVM target is consumed by the Ktor backend
 * so the IR model + validator + coverage scorer are a single source of truth (no JVM mirror).
 *
 * Source layout is kept flat (src/main/kotlin, src/test/kotlin) and wired into commonMain/
 * commonTest via srcDir so the conversion needed no file moves.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

kotlin {
    js(org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType.IR) {
        browser()
        nodejs()
    }
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val commonTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
