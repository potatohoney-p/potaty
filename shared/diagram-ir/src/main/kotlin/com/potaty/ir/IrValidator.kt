/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

/**
 * Validates a [DiagramIR] against the production rule set (plan section 9.6, IR-R001..IR-R017).
 *
 * Validation is a *product surface*, not an exception path: [validate] never throws and always
 * returns a structured [ValidationReport]. Errors block publication; warnings are surfaced in the
 * UI. The same cycle-detection used here is reused by the layout engine to break cycles.
 */
class IrValidator(
    private val maxNodes: Int = 200,
    private val maxEdges: Int = 400,
    private val maxLabelLength: Int = 120,
    private val sensitivePatterns: List<Regex> = DEFAULT_SENSITIVE_PATTERNS,
    private val piiWarnPatterns: List<Regex> = DEFAULT_PII_WARN_PATTERNS,
    /**
     * Optional registry of source-chunk ids that actually exist. When supplied, R008 warns about
     * any evidence reference whose [EvidenceRef.sourceChunkId] is not in this set (a dangling
     * citation). When null (the default) the resolvability check is skipped, preserving existing
     * behavior — the shared module has no access to a live source store, so callers (the backend)
     * opt in.
     */
    private val knownSourceChunkIds: Set<String>? = null
) {

    fun validate(ir: DiagramIR): ValidationReport {
        val issues = mutableListOf<ValidationIssue>()

        checkSchemaVersion(ir, issues) // R001
        val nodeIds = checkUniqueNodeIds(ir, issues) // R002
        checkUniqueEdgeIds(ir, issues) // R003
        checkStableKeyUniqueness(ir, issues) // R004
        checkEdgeEndpoints(ir, nodeIds, issues) // R005
        checkGroupMembership(ir, nodeIds, issues) // R006
        checkConfidenceBounds(ir, issues) // R007
        checkEvidenceRefsResolvable(ir, issues) // R008
        checkPublishableNodes(ir, issues) // R009
        checkPublishableEdges(ir, issues) // R010
        checkDiagramConstraints(ir, issues) // R011
        checkSensitiveInfo(ir, issues) // R012
        checkLabelLimits(ir, issues) // R013
        checkSizeLimits(ir, issues) // R014
        checkUnsupportedCriticalClaims(ir, issues) // R015
        // R016 (renderer-compatibility) is enforced in renderer-codegen's SyntaxValidator.
        // R017 (stale-snapshot) is enforced at publish time by the backend against live sources.

        return ValidationReport(issues)
    }

    // R001 -----------------------------------------------------------------
    private fun checkSchemaVersion(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        if (ir.schemaVersion !in DiagramIR.SUPPORTED_SCHEMA_VERSIONS) {
            out += error("IR-R001", "Unsupported schema version '${ir.schemaVersion}'.")
        }
    }

    // R002 -----------------------------------------------------------------
    private fun checkUniqueNodeIds(ir: DiagramIR, out: MutableList<ValidationIssue>): Set<String> {
        val seen = HashSet<String>()
        for (node in ir.nodes) {
            if (!seen.add(node.id)) {
                out += error("IR-R002", "Duplicate node id '${node.id}'.", node.id)
            }
        }
        return seen
    }

    // R003 -----------------------------------------------------------------
    private fun checkUniqueEdgeIds(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        val seen = HashSet<String>()
        for (edge in ir.edges) {
            if (!seen.add(edge.id)) {
                out += error("IR-R003", "Duplicate edge id '${edge.id}'.", edge.id)
            }
        }
    }

    // R004 -----------------------------------------------------------------
    private fun checkStableKeyUniqueness(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        val nodeKeys = HashSet<String>()
        for (node in ir.nodes) {
            if (!nodeKeys.add(node.stableKey)) {
                out +=
                    warning(
                        "IR-R004",
                        "Duplicate node stableKey '${node.stableKey}'; " +
                            "regeneration/patch matching may be ambiguous.",
                        node.id
                    )
            }
        }
    }

    // R005 -----------------------------------------------------------------
    private fun checkEdgeEndpoints(
        ir: DiagramIR,
        nodeIds: Set<String>,
        out: MutableList<ValidationIssue>
    ) {
        for (edge in ir.edges) {
            if (edge.from !in nodeIds) {
                out +=
                    error(
                        "IR-R005",
                        "Edge '${edge.id}' references missing source node '${edge.from}'.",
                        edge.id
                    )
            }
            if (edge.to !in nodeIds) {
                out +=
                    error(
                        "IR-R005",
                        "Edge '${edge.id}' references missing target node '${edge.to}'.",
                        edge.id
                    )
            }
        }
    }

    // R006 -----------------------------------------------------------------
    private fun checkGroupMembership(
        ir: DiagramIR,
        nodeIds: Set<String>,
        out: MutableList<ValidationIssue>
    ) {
        val membership = HashMap<String, Int>()
        for (group in ir.groups) {
            for (nid in group.nodeIds) {
                if (nid !in nodeIds) {
                    out +=
                        error(
                            "IR-R006",
                            "Group '${group.id}' references missing node '$nid'.",
                            group.id
                        )
                }
                membership[nid] = (membership[nid] ?: 0) + 1
            }
        }
        membership
            .filterValues { it > 1 }
            .keys
            .forEach { nid ->
                out +=
                    warning(
                        "IR-R006",
                        "Node '$nid' belongs to multiple groups; container layout will pick one.",
                        nid
                    )
            }
    }

    // R007 -----------------------------------------------------------------
    private fun checkConfidenceBounds(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        for (node in ir.nodes) {
            if (node.confidence < 0.0 || node.confidence > 1.0) {
                out +=
                    error(
                        "IR-R007",
                        "Node '${node.id}' confidence ${node.confidence} out of [0,1].",
                        node.id
                    )
            }
        }
        for (edge in ir.edges) {
            if (edge.confidence < 0.0 || edge.confidence > 1.0) {
                out +=
                    error(
                        "IR-R007",
                        "Edge '${edge.id}' confidence ${edge.confidence} out of [0,1].",
                        edge.id
                    )
            }
        }
    }

    // R008 -----------------------------------------------------------------
    private fun checkEvidenceRefsResolvable(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        fun checkRefs(refs: List<EvidenceRef>, targetId: String) {
            for (ref in refs) {
                if (ref.sourceChunkId.isBlank()) {
                    out +=
                        error(
                            "IR-R008",
                            "Evidence on '$targetId' has a blank sourceChunkId.",
                            targetId
                        )
                } else if (
                    knownSourceChunkIds != null && ref.sourceChunkId !in knownSourceChunkIds
                ) {
                    // Dangling citation: the chunk id does not exist in the supplied source
                    // registry.
                    out +=
                        warning(
                            "IR-R008",
                            "Evidence on '$targetId' references unknown sourceChunkId " +
                                "'${ref.sourceChunkId}'.",
                            targetId
                        )
                }
                if (!ref.isValidRange()) {
                    out +=
                        warning(
                            "IR-R008",
                            "Evidence on '$targetId' has an inverted range (start > end); " +
                                "likely an extraction bug.",
                            targetId
                        )
                }
            }
        }
        ir.nodes.forEach { checkRefs(it.evidence, it.id) }
        ir.edges.forEach { checkRefs(it.evidence, it.id) }
    }

    // R009 -----------------------------------------------------------------
    private fun checkPublishableNodes(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        for (node in ir.nodes) {
            // A node may be published if it is evidence-grounded, explicitly user-confirmed, or
            // user-modified (an edit implies the user vouched for it). userConfirmed is the clean
            // affirmative path (see IrNode docs); userModified is kept for backward compatibility.
            val grounded = node.evidence.isNotEmpty() || node.userConfirmed || node.userModified
            if (!grounded) {
                out +=
                    publishBlock(
                        "IR-R009",
                        "Node '${node.label}' has no evidence and is not user-confirmed; " +
                            "not publishable.",
                        node.id
                    )
            }
        }
    }

    // R010 -----------------------------------------------------------------
    private fun checkPublishableEdges(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        for (edge in ir.edges) {
            val ok =
                when (edge.edgeSourceType) {
                    EdgeSourceType.USER_CONFIRMED -> true
                    EdgeSourceType.LLM_INFERRED ->
                        // allowed only when uncertainty is visible (confidence strictly < 1.0)
                        edge.confidence < 1.0 || edge.evidence.isNotEmpty()
                    EdgeSourceType.FRAMEWORK_CONVENTION -> edge.evidence.isNotEmpty()
                    else -> edge.evidence.isNotEmpty() // grounded sources require evidence
                }
            if (!ok) {
                out +=
                    publishBlock(
                        "IR-R010",
                        "Edge '${edge.id}' (${edge.edgeSourceType}) lacks " +
                            "evidence/visible uncertainty; not publishable.",
                        edge.id
                    )
            }
        }
    }

    // R011 -----------------------------------------------------------------
    private fun checkDiagramConstraints(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        if (ir.diagramType.isAcyclic) {
            val cycle =
                CycleDetector.findCycle(
                    ir.nodes.map { it.id },
                    ir.edges.map { it.from to it.to }
                )
            if (cycle != null) {
                out +=
                    error(
                        "IR-R011",
                        "Diagram type ${ir.diagramType} must be acyclic but a cycle was found: " +
                            cycle.joinToString(" -> ") +
                            ".",
                        cycle.firstOrNull()
                    )
            }
        }
        if (ir.diagramType == DiagramType.SEQUENCE) {
            // Sequence diagrams need at least two participants and ordered messages.
            if (ir.nodes.size < 2) {
                out += warning("IR-R011", "Sequence diagram has fewer than two participants.")
            }
        }
        // Self-loops render as a degenerate line and are skipped by the layout engines; warn so the
        // author knows the edge will not be drawn as a normal connector.
        ir.edges
            .filter { it.from == it.to }
            .forEach { e ->
                out +=
                    warning(
                        "IR-R011",
                        "Edge '${e.id}' is a self-loop (${e.from}); " +
                            "it will not be drawn as a connector.",
                        e.id
                    )
            }
    }

    // R012 -----------------------------------------------------------------
    private fun checkSensitiveInfo(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        val haystacks = buildList {
            // Diagram-level metadata is exported by Markdown/GitHub just like node text, so it
            // belongs inside the same publication safety boundary.
            add(ir.title to "diagram:title")
            ir.objective?.let { add(it to "diagram:objective") }
            ir.nodes.forEach { n ->
                add(n.label to n.id)
                n.summary?.let { add(it to n.id) }
                n.evidence.forEach { e -> e.quote?.let { add(it to n.id) } }
            }
            ir.edges.forEach { e ->
                e.label?.let { add(it to e.id) }
                e.evidence.forEach { ev -> ev.quote?.let { add(it to e.id) } }
            }
        }
        for ((text, targetId) in haystacks) {
            // Secrets/credentials block publication (hard fail). PII (e.g. emails) is a warning
            // that
            // can be redacted, per plan section 20.3's warn/redact-by-default policy.
            if (sensitivePatterns.any { it.containsMatchIn(text) }) {
                out +=
                    publishBlock(
                        "IR-R012",
                        "Possible secret/credential detected in IR text near '$targetId'.",
                        targetId
                    )
            } else if (piiWarnPatterns.any { it.containsMatchIn(text) }) {
                out +=
                    warning(
                        "IR-R012",
                        "Possible PII detected in IR text near '$targetId'; consider redacting.",
                        targetId
                    )
            }
        }
    }

    // R013 -----------------------------------------------------------------
    private fun checkLabelLimits(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        ir.nodes.forEach { n ->
            if (n.label.length > maxLabelLength) {
                out +=
                    warning(
                        "IR-R013",
                        "Node '${n.id}' label exceeds $maxLabelLength chars; " +
                            "it will be wrapped/truncated.",
                        n.id
                    )
            }
        }
        ir.edges.forEach { e ->
            if ((e.label?.length ?: 0) > maxLabelLength) {
                out +=
                    warning(
                        "IR-R013",
                        "Edge '${e.id}' label exceeds $maxLabelLength chars.",
                        e.id
                    )
            }
        }
    }

    // R014 -----------------------------------------------------------------
    private fun checkSizeLimits(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        if (ir.nodes.isEmpty()) {
            out += error("IR-R014", "Diagram must contain at least one node.")
        }
        if (ir.nodes.size > maxNodes) {
            out += error("IR-R014", "Node count ${ir.nodes.size} exceeds limit $maxNodes.")
        }
        if (ir.edges.size > maxEdges) {
            out += error("IR-R014", "Edge count ${ir.edges.size} exceeds limit $maxEdges.")
        }
    }

    // R015 -----------------------------------------------------------------
    private fun checkUnsupportedCriticalClaims(ir: DiagramIR, out: MutableList<ValidationIssue>) {
        ir.unsupportedClaims
            .filter { it.severity == Severity.CRITICAL }
            .forEach { claim ->
                out +=
                    publishBlock(
                        "IR-R015",
                        "Unsupported critical claim: ${claim.claim}",
                        claim.targetId
                    )
            }
    }

    private fun error(code: String, message: String, targetId: String? = null) =
        ValidationIssue(
            ValidationIssue.Severity.ERROR,
            code,
            message,
            targetId,
            blocksPublish = true
        )

    private fun publishBlock(code: String, message: String, targetId: String? = null) =
        ValidationIssue(
            ValidationIssue.Severity.ERROR,
            code,
            message,
            targetId,
            blocksPublish = true
        )

    private fun warning(code: String, message: String, targetId: String? = null) =
        ValidationIssue(
            ValidationIssue.Severity.WARNING,
            code,
            message,
            targetId,
            blocksPublish = false
        )

    companion object {
        // NOTE: these patterns are authored once in Kotlin and compiled to the host platform's
        // regex engine — java.util.regex on JVM, the native RegExp on Kotlin/JS. The character
        // classes, anchors, and IGNORE_CASE option used below are part of the common subset both
        // engines support, so behavior is identical across targets. The only Kotlin/JS pitfall is
        // inline flags such as (?i): they are NOT supported, so case-insensitivity MUST be
        // expressed
        // via RegexOption.IGNORE_CASE (as done here). Keep new patterns within this common subset.
        val DEFAULT_SENSITIVE_PATTERNS: List<Regex> =
            listOf(
                Regex("""AKIA[0-9A-Z]{16}"""), // AWS access key id
                Regex(
                    """\bsk-[a-z0-9]{20,}\b""",
                    RegexOption.IGNORE_CASE
                ), // OpenAI-style secret key
                Regex(
                    """\b(api[_-]?key|secret|password|passwd|bearer)\b\s*[:=]\s*\S+""",
                    RegexOption.IGNORE_CASE
                ),
                Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
                Regex(
                    """\beyJ[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\.[a-zA-Z0-9_-]{10,}\b"""
                ) // JWT
            )

        // PII patterns warn (and suggest redaction) but do not block publication.
        val DEFAULT_PII_WARN_PATTERNS: List<Regex> =
            listOf(
                Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""") // email
            )
    }
}

/** Result of a validation pass. */
data class ValidationReport(val issues: List<ValidationIssue>) {
    val errors: List<ValidationIssue>
        get() = issues.filter {
            it.severity == ValidationIssue.Severity.ERROR
        }

    val warnings: List<ValidationIssue>
        get() = issues.filter {
            it.severity == ValidationIssue.Severity.WARNING
        }

    /** A diagram is structurally valid (renderable) when there are no ERROR issues. */
    val isValid: Boolean
        get() = errors.isEmpty()

    /** A diagram is publishable when nothing blocks publication. */
    val isPublishable: Boolean
        get() = issues.none { it.blocksPublish }
}

data class ValidationIssue(
    val severity: Severity,
    val code: String,
    val message: String,
    val targetId: String? = null,
    val blocksPublish: Boolean = false
) {
    enum class Severity {
        ERROR,
        WARNING
    }
}
