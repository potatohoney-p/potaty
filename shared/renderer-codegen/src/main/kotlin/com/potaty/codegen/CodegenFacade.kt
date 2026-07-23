/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.codegen

import com.potaty.ir.DiagramIR

/**
 * The supported diagram-as-code output formats.
 */
enum class CodegenFormat {
    MERMAID,
    D2,
    PLANTUML,
    DOT,
    MARKDOWN;

    /** Conventional file extension for this format. */
    val fileExtension: String
        get() = when (this) {
            MERMAID -> "mmd"
            D2 -> "d2"
            PLANTUML -> "puml"
            DOT -> "dot"
            MARKDOWN -> "md"
        }
}

/**
 * Single entry-point for compiling a [DiagramIR] to any supported diagram-as-code [CodegenFormat].
 *
 * Each compiler is a pure function; this facade just dispatches. Use it from the backend export
 * pipeline and the Workbench "copy as" actions.
 */
object CodegenFacade {

    fun compile(ir: DiagramIR, format: CodegenFormat): String = when (format) {
        CodegenFormat.MERMAID -> MermaidCompiler.compile(ir)
        CodegenFormat.D2 -> D2Compiler.compile(ir)
        CodegenFormat.PLANTUML -> PlantUmlCompiler.compile(ir)
        CodegenFormat.DOT -> GraphvizCompiler.compile(ir)
        CodegenFormat.MARKDOWN -> MarkdownExporter.export(ir)
    }
}
