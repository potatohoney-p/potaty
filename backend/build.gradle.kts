/*
 * Copyright (c) 2026, Potaty
 *
 * backend — the Kotlin/JVM Ktor service for the source-grounded Anything-to-Diagram
 * platform. Hosts source ingestion, the job pipeline, the LLM provider abstraction,
 * tenant-scoped persistence (Postgres + pgvector via Flyway/Exposed/Hikari), and the
 * /api/v1 contract.
 *
 * IR SHARING NOTE:
 *   :diagram-ir and :renderer-codegen are now Kotlin Multiplatform (js + jvm, plan section 6 /
 *   WS1). This JVM module depends on their JVM targets directly, so the canonical DiagramIR,
 *   IrValidator, EvidenceCoverageScorer, IrPatcher and the five code compilers (Mermaid / D2 /
 *   PlantUML / Graphviz / Markdown) are a SINGLE source of truth shared with the browser — no
 *   hand-maintained JVM mirror. (:layout-engine and :renderer-ascii remain JS-only for now, so
 *   ASCII rendering is browser-side until the engine is MPP-converted.)
 */
plugins {
    // No version qualifiers: the Kotlin Gradle plugin is already on the build classpath
    // (the root project applies kotlin("js") version "1.8.20"), so re-declaring a version
    // here fails with "plugin is already on the classpath with an unknown version".
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.potaty.backend.ApplicationKt")
}

dependencies {
    implementation(platform(libs.netty.bom))
    implementation(platform(libs.jackson.bom))

    // Canonical IR + pure code compilers, shared with the browser via Kotlin Multiplatform.
    implementation(projects.diagramIr)
    implementation(projects.rendererCodegen)

    // Kotlin / coroutines / serialization
    implementation(kotlin("stdlib"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json.jvm)

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.logging)

    // Ktor client (for provider HTTP calls)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Persistence: Postgres + pgvector via Hikari/Exposed/Flyway
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.h2)
    implementation(libs.flyway.core)

    // Logging
    implementation(libs.logback.classic)

    // Test: Ktor's MockEngine lets provider HTTP be unit-tested without network; the
    // test-host drives the full HTTP stack in-process against an embedded H2 database.
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
