/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

import org.jetbrains.kotlin.gradle.plugin.KotlinJsCompilerType
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

plugins {
    kotlin("js") version "1.8.20"
    kotlin("plugin.serialization") version "1.8.20"
    id("org.jetbrains.compose") version "1.11.1"
    id("io.miret.etienne.sass") version "1.6.0"
}

group = "com.potaty"

allprojects {
    ext {
        set("compilerType", KotlinJsCompilerType.IR)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(projects.app)
    implementation(projects.lifecycle)

    testImplementation(libs.kotlin.test.js)
}

val compilerType: KotlinJsCompilerType by ext
kotlin {
    js(compilerType) {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }
}

apply(from = "ktlint.gradle")
apply(from = "sass.gradle")
apply(from = "tailwind.gradle")

// Samsung Sharp Sans remains deployment-provided because its binaries are proprietary. Bundle
// the OFL-licensed Pretendard fallback from the locked npm package so every OSS build still gets
// intentional Latin/Hangul typography instead of silently falling through to Arial. Pretendard's
// unicode-range subsets keep the first render small; copying the monolithic variable font would
// force every visitor to download roughly 2 MiB even when Samsung Sharp Sans is available locally.
tasks.named<Copy>("processResources") {
    from(layout.projectDirectory.file("node_modules/@kfonts/d2coding/D2Coding.woff2")) {
        into("fonts")
    }
    from(layout.projectDirectory.file("node_modules/pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css")) {
        into("fonts")
    }
    from(layout.projectDirectory.dir("node_modules/pretendard/dist/web/variable/woff2-dynamic-subset")) {
        into("fonts/woff2-dynamic-subset")
    }
}

// Webpack's default budget is based on uncompressed JavaScript and produces a noisy false signal
// for the Kotlin/JS runtime. Enforce the user-visible transfer size instead; the matching webpack
// fragment keeps its raw-size warning aligned with this explicit compressed budget.
val productionBundle = layout.buildDirectory.file("distributions/Potaty.js")
val checkProductionBundleBudget by tasks.registering {
    group = "verification"
    description = "Fails when the production JavaScript bundle exceeds its gzip transfer budget."
    inputs.file(productionBundle)

    doLast {
        val bundle = productionBundle.get().asFile
        check(bundle.isFile) {
            "Production bundle is missing: ${bundle.absolutePath}"
        }

        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { gzip ->
            bundle.inputStream().use { input -> input.copyTo(gzip) }
        }
        val gzipBytes = compressed.size()
        val budgetBytes = 320 * 1024
        check(gzipBytes <= budgetBytes) {
            "Production bundle is $gzipBytes gzip bytes; budget is $budgetBytes bytes."
        }
        logger.lifecycle("Production bundle budget: $gzipBytes / $budgetBytes gzip bytes")
    }
}

tasks.named("browserProductionWebpack") {
    finalizedBy(checkProductionBundleBudget)
}
