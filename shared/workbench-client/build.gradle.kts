/*
 * Copyright (c) 2026, Potaty
 *
 * shared/workbench-client (WS8) — a browser-consumable API client for the Potaty backend.
 *
 * A Kotlin/JS (IR) library that the editor UI links against to drive the source-grounded
 * diagram pipeline over the /api/v1 contract: create a source, enqueue a diagram job, poll it,
 * then fetch the rendered diagram version. It deserialises into @Serializable DTOs that mirror
 * the backend shapes and reuses com.potaty.ir.DiagramIR for the `ir` field, so the IR model is a
 * single source of truth (no JS mirror).
 *
 * The networking layer is an injectable HttpTransport; the default fetch-backed implementation
 * adapts the browser fetch API (kotlin.js.Promise) to a `suspend` API using kotlin.coroutines
 * primitives from the stdlib only — so the module pulls no extra coroutines dependency and the
 * pure-logic tests run on `nodeTest` with a fake transport (no DOM, no network).
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.stdlib.js)
    testImplementation(libs.kotlin.test.js)
}

kotlin {
    js(org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType.IR) {
        browser()
        nodejs()
    }
}
