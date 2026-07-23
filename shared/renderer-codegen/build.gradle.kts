/*
 * Copyright (c) 2026, Potaty
 *
 * shared/renderer-codegen — pure IR -> diagram-as-code compilers (Mermaid / D2 / PlantUML /
 * Graphviz DOT) plus a Markdown exporter. Kotlin Multiplatform (js + jvm): the JS target
 * serves the browser/Node; the JVM target lets the Ktor backend emit code formats directly
 * from the canonical IR. Pure functions DiagramIR -> String; no coordinates, no engine.
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
        nodejs()
    }
    jvm()

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                implementation(projects.diagramIr)
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
