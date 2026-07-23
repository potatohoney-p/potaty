/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The canonical Diagram Intermediate Representation (v1.1).
 *
 * This is the **source of truth** for every diagram in Potaty. LLMs and importers
 * produce IR; renderers (ASCII / Mermaid / D2 / PlantUML / DOT) are pure compilers
 * from IR. The IR is intentionally:
 *
 *  - coordinate-free (layout is computed deterministically by the layout engine),
 *  - renderer-independent,
 *  - evidence-linked (every node/edge can cite exact source chunks),
 *  - patchable & diffable (stable keys survive regeneration),
 *  - validatable (see [IrValidator]).
 *
 * Mirrors section 9.2 of the production plan.
 */
@Serializable
data class DiagramIR(
    @SerialName("schema_version")
    val schemaVersion: String = SCHEMA_VERSION,
    @SerialName("diagram_id")
    val diagramId: String,
    val title: String,
    val objective: String? = null,
    @SerialName("diagram_type")
    val diagramType: DiagramType,
    @SerialName("source_snapshot_ids")
    val sourceSnapshotIds: List<String> = emptyList(),
    val scope: DiagramScope = DiagramScope(),
    val nodes: List<IrNode> = emptyList(),
    val edges: List<IrEdge> = emptyList(),
    val groups: List<IrGroup> = emptyList(),
    @SerialName("layout_hints")
    val layoutHints: LayoutHints = LayoutHints(),
    @SerialName("style_hints")
    val styleHints: StyleHints = StyleHints(),
    val warnings: List<IrWarning> = emptyList(),
    @SerialName("unsupported_claims")
    val unsupportedClaims: List<UnsupportedClaim> = emptyList(),
    val provenance: IrProvenance = IrProvenance()
) {
    companion object {
        const val SCHEMA_VERSION: String = "1.1"

        /**
         * Schema versions this build can decode. `1.0` is retained for backward compatibility:
         * it is wire-compatible with `1.1` (1.1 only *added* optional fields with defaults, so a
         * 1.0 payload deserializes cleanly and a 1.0 producer simply omits the newer fields). No
         * lossy migration step is required; [IrValidator] accepts both versions (R001). If a future
         * schema bump introduces a breaking change, remove the obsolete version from this set and
         * add an explicit migration in [IrJson].
         */
        val SUPPORTED_SCHEMA_VERSIONS: Set<String> = setOf("1.0", "1.1")
    }
}

@Serializable
data class DiagramScope(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    @SerialName("abstraction_level")
    val abstractionLevel: AbstractionLevel = AbstractionLevel.MEDIUM
)

@Serializable
enum class AbstractionLevel {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW
}

@Serializable
enum class DiagramType {
    @SerialName("architecture")
    ARCHITECTURE,

    @SerialName("flowchart")
    FLOWCHART,

    @SerialName("sequence")
    SEQUENCE,

    @SerialName("er")
    ER,

    @SerialName("dependency")
    DEPENDENCY,

    @SerialName("dataflow")
    DATAFLOW,

    @SerialName("deployment")
    DEPLOYMENT,

    @SerialName("state")
    STATE,

    @SerialName("class")
    CLASS,

    @SerialName("mindmap")
    MINDMAP,

    @SerialName("container")
    CONTAINER,

    @SerialName("timeline")
    TIMELINE,

    @SerialName("decision")
    DECISION,

    @SerialName("action_map")
    ACTION_MAP;

    /** Diagram types whose edges express a strict ordering and must stay acyclic for layout. */
    val isAcyclic: Boolean
        get() = this in ACYCLIC_TYPES

    companion object {
        val ACYCLIC_TYPES: Set<DiagramType> =
            setOf(FLOWCHART, STATE, MINDMAP, DECISION, TIMELINE)
    }
}

@Serializable
data class IrNode(
    val id: String,
    @SerialName("stable_key")
    val stableKey: String = id,
    val label: String,
    val type: NodeType = NodeType.GENERIC,
    val summary: String? = null,
    val confidence: Double = 1.0,
    val evidence: List<EvidenceRef> = emptyList(),
    /** Optional sub-instances rendered as a "stack" (e.g. multiple replicas). 0/1 = single box. */
    @SerialName("instance_count")
    val instanceCount: Int = 1,
    val metadata: Map<String, JsonElement> = emptyMap(),
    /**
     * The user *edited* this node (e.g. relabelled it). Set by [IrPatcher] on update operations so
     * regeneration can preserve manual edits.
     */
    @SerialName("user_modified")
    val userModified: Boolean = false,
    /**
     * The user *explicitly confirmed* this node, deliberately bypassing the evidence gate (R009).
     * Distinct from [userModified]: confirmation is an affirmative "yes, this belongs" decision and
     * is the clean grounding path for nodes that have no machine-extractable evidence. Defaults to
     * false; both flags satisfy R009 (see [IrValidator]) for backward compatibility.
     */
    @SerialName("user_confirmed")
    val userConfirmed: Boolean = false
)

@Serializable
enum class NodeType {
    @SerialName("user")
    USER,

    @SerialName("actor")
    ACTOR,

    @SerialName("frontend")
    FRONTEND,

    @SerialName("backend")
    BACKEND,

    @SerialName("service")
    SERVICE,

    @SerialName("worker")
    WORKER,

    @SerialName("database")
    DATABASE,

    @SerialName("queue")
    QUEUE,

    @SerialName("cache")
    CACHE,

    @SerialName("storage")
    STORAGE,

    @SerialName("external_service")
    EXTERNAL_SERVICE,

    @SerialName("gateway")
    GATEWAY,

    @SerialName("module")
    MODULE,

    @SerialName("component")
    COMPONENT,

    @SerialName("package")
    PACKAGE,

    @SerialName("table")
    TABLE,

    @SerialName("entity")
    ENTITY,

    @SerialName("route")
    ROUTE,

    @SerialName("event")
    EVENT,

    @SerialName("decision")
    DECISION,

    @SerialName("action")
    ACTION,

    @SerialName("topic")
    TOPIC,

    @SerialName("requirement")
    REQUIREMENT,

    @SerialName("risk")
    RISK,

    @SerialName("person")
    PERSON,

    @SerialName("milestone")
    MILESTONE,

    @SerialName("generic")
    GENERIC
}

@Serializable
data class IrEdge(
    val id: String,
    @SerialName("stable_key")
    val stableKey: String = id,
    val from: String,
    val to: String,
    val type: EdgeType = EdgeType.RELATES_TO,
    val label: String? = null,
    val confidence: Double = 1.0,
    val evidence: List<EvidenceRef> = emptyList(),
    @SerialName("edge_source_type")
    val edgeSourceType: EdgeSourceType = EdgeSourceType.LLM_INFERRED,
    val metadata: Map<String, JsonElement> = emptyMap(),
    @SerialName("user_modified")
    val userModified: Boolean = false
)

@Serializable
enum class EdgeType {
    @SerialName("request")
    REQUEST,

    @SerialName("response")
    RESPONSE,

    @SerialName("calls")
    CALLS,

    @SerialName("imports")
    IMPORTS,

    @SerialName("depends_on")
    DEPENDS_ON,

    @SerialName("publishes")
    PUBLISHES,

    @SerialName("consumes")
    CONSUMES,

    @SerialName("reads")
    READS,

    @SerialName("writes")
    WRITES,

    @SerialName("reads_writes")
    READS_WRITES,

    @SerialName("authenticates")
    AUTHENTICATES,

    @SerialName("deploys_to")
    DEPLOYS_TO,

    @SerialName("navigates_to")
    NAVIGATES_TO,

    @SerialName("relates_to")
    RELATES_TO,

    @SerialName("contains")
    CONTAINS,

    @SerialName("precedes")
    PRECEDES,

    @SerialName("blocks")
    BLOCKS,

    @SerialName("decides")
    DECIDES,

    @SerialName("references")
    REFERENCES,

    @SerialName("has_many")
    HAS_MANY,

    @SerialName("has_one")
    HAS_ONE
}

/**
 * How an edge was established. This drives the publish gate (see [IrValidator]) and the
 * UI's confidence presentation. Mirrors plan section 9.4.
 */
@Serializable
enum class EdgeSourceType {
    @SerialName("static_import")
    STATIC_IMPORT,

    @SerialName("explicit_call")
    EXPLICIT_CALL,

    @SerialName("framework_convention")
    FRAMEWORK_CONVENTION,

    @SerialName("config_declaration")
    CONFIG_DECLARATION,

    @SerialName("database_relation")
    DATABASE_RELATION,

    @SerialName("transcript_statement")
    TRANSCRIPT_STATEMENT,

    @SerialName("document_statement")
    DOCUMENT_STATEMENT,

    @SerialName("llm_inferred")
    LLM_INFERRED,

    @SerialName("user_confirmed")
    USER_CONFIRMED;

    /** Whether this edge source is a deterministic/grounded fact rather than an inference. */
    val isGrounded: Boolean
        get() = this in GROUNDED

    companion object {
        val GROUNDED: Set<EdgeSourceType> = setOf(
            STATIC_IMPORT,
            EXPLICIT_CALL,
            CONFIG_DECLARATION,
            DATABASE_RELATION,
            TRANSCRIPT_STATEMENT,
            DOCUMENT_STATEMENT
        )
    }
}

/**
 * A precise pointer back into the source material. Code uses path + line range; documents use
 * page range; transcripts use millisecond range + speaker. At least [sourceChunkId] is required.
 */
@Serializable
data class EvidenceRef(
    @SerialName("source_chunk_id")
    val sourceChunkId: String,
    @SerialName("source_version_id")
    val sourceVersionId: String? = null,
    val path: String? = null,
    @SerialName("start_line")
    val startLine: Int? = null,
    @SerialName("end_line")
    val endLine: Int? = null,
    @SerialName("start_page")
    val startPage: Int? = null,
    @SerialName("end_page")
    val endPage: Int? = null,
    @SerialName("start_ms")
    val startMs: Int? = null,
    @SerialName("end_ms")
    val endMs: Int? = null,
    val speaker: String? = null,
    val quote: String? = null,
    @SerialName("quote_hash")
    val quoteHash: String? = null
) {
    /**
     * True when every present (start, end) pair is non-inverted (start <= end). Missing endpoints
     * are treated as valid. Used by R008 to warn about inverted line/page/ms ranges that usually
     * indicate an extraction bug.
     */
    fun isValidRange(): Boolean {
        fun ok(start: Int?, end: Int?): Boolean = start == null || end == null || start <= end
        return ok(startLine, endLine) && ok(startPage, endPage) && ok(startMs, endMs)
    }
}

@Serializable
data class IrGroup(
    val id: String,
    val label: String,
    @SerialName("node_ids")
    val nodeIds: List<String> = emptyList(),
    val confidence: Double = 1.0,
    val evidence: List<EvidenceRef> = emptyList()
)

@Serializable
enum class LayoutDirection {
    @SerialName("TB")
    TB,

    @SerialName("BT")
    BT,

    @SerialName("LR")
    LR,

    @SerialName("RL")
    RL
}

@Serializable
data class LayoutHints(
    val direction: LayoutDirection = LayoutDirection.TB,
    @SerialName("max_nodes_per_group")
    val maxNodesPerGroup: Int? = null,
    @SerialName("prefer_grouping")
    val preferGrouping: Boolean = true,
    /** Horizontal gap (in cells) between sibling nodes; null = engine default. */
    @SerialName("sibling_gap")
    val siblingGap: Int? = null,
    /** Vertical gap (in cells) between layers; null = engine default. */
    @SerialName("layer_gap")
    val layerGap: Int? = null
)

@Serializable
data class StyleHints(
    /** Name of a [StyleProfile] (resolved in render-core), e.g. "potaty-clean". */
    @SerialName("style_profile")
    val styleProfile: String = "potaty-clean",
    @SerialName("rounded_corners")
    val roundedCorners: Boolean = false,
    @SerialName("dashed_groups")
    val dashedGroups: Boolean = true,
    @SerialName("show_edge_labels")
    val showEdgeLabels: Boolean = true
)

@Serializable
data class IrWarning(
    val code: String,
    val message: String,
    @SerialName("target_id")
    val targetId: String? = null
)

@Serializable
data class UnsupportedClaim(
    val id: String,
    val claim: String,
    val severity: Severity = Severity.WARNING,
    @SerialName("target_id")
    val targetId: String? = null,
    val reason: String = "",
    @SerialName("suggested_action")
    val suggestedAction: String = ""
)

@Serializable
enum class Severity {
    @SerialName("info")
    INFO,

    @SerialName("warning")
    WARNING,

    @SerialName("critical")
    CRITICAL
}

/**
 * Reproducibility metadata: which prompts/models/renderer produced this IR version.
 */
@Serializable
data class IrProvenance(
    @SerialName("generated_by")
    val generatedBy: String? = null,
    @SerialName("prompt_versions")
    val promptVersions: Map<String, String> = emptyMap(),
    @SerialName("model_trace")
    val modelTrace: List<String> = emptyList(),
    @SerialName("renderer_version")
    val rendererVersion: String? = null,
    @SerialName("layout_engine_version")
    val layoutEngineVersion: String? = null
)
