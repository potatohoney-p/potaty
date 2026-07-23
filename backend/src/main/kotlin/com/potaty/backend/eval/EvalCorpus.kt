/*
 * Copyright (c) 2026, Potaty
 *
 * WS14 evaluation corpus (plan section 18 / 21.3). A small set of in-code fixtures used to gate
 * the GROUNDED text -> DiagramIR pipeline. Each fixture pairs a source text with:
 *
 *  - the node labels that MUST appear (faithfulness: "important node included"),
 *  - the directed edges that MUST appear (relation accuracy: "important edge included"),
 *  - "forbidden claims": entity/relation substrings that the grounded extractor must NOT invent
 *    (hallucination guard: the deterministic pass never fabricates structure not in the text).
 *
 * Labels here are written so they survive the extractor's normalization (trim, whitespace-collapse,
 * lowercase canonicalization) -- see [EvalMetrics.canonical]. The corpus is intentionally tiny and
 * deterministic so [EvalRunner] can run it offline against H2 with no LLM (plan 18.2 "Golden tests").
 */

package com.potaty.backend.eval

import com.potaty.ir.DiagramType

/**
 * A single directed relation expectation. [label] is optional and not matched (the grounded
 * extractor's edge label is best-effort); only the (from, to) direction is required.
 */
data class ExpectedEdge(
    val from: String,
    val to: String
)

/**
 * One evaluation fixture: a source document plus the ground-truth structure the pipeline must
 * recover from it, and the claims it must never invent.
 */
data class EvalFixture(
    val id: String,
    val description: String,
    val diagramType: DiagramType,
    val objective: String,
    /** Raw source text fed through the normalizer + chunker + extractor. */
    val sourceText: String,
    /** Node labels that MUST be present in the produced IR (canonicalized match). */
    val requiredNodeLabels: List<String>,
    /** Directed edges that MUST be present in the produced IR (canonicalized endpoint match). */
    val requiredEdges: List<ExpectedEdge>,
    /**
     * Substrings that must NOT appear as any node label nor as any edge endpoint label. These are
     * plausible-but-absent entities/relations; the grounded pass must not hallucinate them.
     */
    val forbiddenClaims: List<String>
)

/**
 * The in-code eval corpus. Three fixtures spanning the supported grounded syntaxes:
 * arrow edges, verb edges, and a mixed document.
 */
object EvalCorpus {

    /** Fixture 1 -- a request/architecture flow expressed with explicit arrow edges. */
    val LOGIN_BILLING_FLOW = EvalFixture(
        id = "login-billing-flow",
        description = "Login + billing request flow stated with explicit arrows.",
        diagramType = DiagramType.ARCHITECTURE,
        objective = "Explain the login and billing request flow",
        sourceText = """
            User -> API Gateway: login request
            API Gateway -> Auth Service: verify credentials
            Auth Service -> Postgres: read user row
            API Gateway -> Billing Service: charge customer
            Billing Service -> Postgres: write invoice
            Billing Service -> Events Queue: publish invoice.created
        """.trimIndent(),
        requiredNodeLabels = listOf(
            "User",
            "API Gateway",
            "Auth Service",
            "Postgres",
            "Billing Service",
            "Events Queue"
        ),
        requiredEdges = listOf(
            ExpectedEdge("User", "API Gateway"),
            ExpectedEdge("API Gateway", "Auth Service"),
            ExpectedEdge("Auth Service", "Postgres"),
            ExpectedEdge("API Gateway", "Billing Service"),
            ExpectedEdge("Billing Service", "Postgres"),
            ExpectedEdge("Billing Service", "Events Queue")
        ),
        forbiddenClaims = listOf(
            // Never stated in the text -- common neighbours that must not be invented.
            "Redis",
            "Stripe",
            "Notification Service",
            "Kubernetes"
        )
    )

    /** Fixture 2 -- service dependencies stated with verb edges ("calls", "reads from", ...). */
    val SERVICE_DEPENDENCIES = EvalFixture(
        id = "service-dependencies",
        description = "Service-to-service dependencies stated with verb relations.",
        diagramType = DiagramType.DEPENDENCY,
        objective = "Show how the order service depends on its collaborators",
        sourceText = """
            Order Service calls Payment Service
            Order Service reads from Inventory Database
            Order Service writes to Order Database
            Payment Service depends on Ledger Service
            Worker consumes Order Queue
        """.trimIndent(),
        requiredNodeLabels = listOf(
            "Order Service",
            "Payment Service",
            "Inventory Database",
            "Order Database",
            "Ledger Service",
            "Worker",
            "Order Queue"
        ),
        requiredEdges = listOf(
            ExpectedEdge("Order Service", "Payment Service"),
            ExpectedEdge("Order Service", "Inventory Database"),
            ExpectedEdge("Order Service", "Order Database"),
            ExpectedEdge("Payment Service", "Ledger Service"),
            ExpectedEdge("Worker", "Order Queue")
        ),
        forbiddenClaims = listOf(
            "Shipping Service",
            "Email Service",
            "GraphQL",
            "Kafka Connect"
        )
    )

    /** Fixture 3 -- a mixed dataflow document combining arrow and verb edges. */
    val INGESTION_PIPELINE = EvalFixture(
        id = "ingestion-pipeline",
        description = "Data ingestion pipeline mixing arrow and verb edges.",
        diagramType = DiagramType.DATAFLOW,
        objective = "Describe the ingestion pipeline end to end",
        sourceText = """
            Client -> Ingest API: upload document
            Ingest API writes to Object Storage
            Ingest API -> Processing Queue: enqueue job
            Processing Worker consumes Processing Queue
            Processing Worker reads from Object Storage
            Processing Worker writes to Search Index
        """.trimIndent(),
        requiredNodeLabels = listOf(
            "Client",
            "Ingest API",
            "Object Storage",
            "Processing Queue",
            "Processing Worker",
            "Search Index"
        ),
        requiredEdges = listOf(
            ExpectedEdge("Client", "Ingest API"),
            ExpectedEdge("Ingest API", "Object Storage"),
            ExpectedEdge("Ingest API", "Processing Queue"),
            ExpectedEdge("Processing Worker", "Processing Queue"),
            ExpectedEdge("Processing Worker", "Object Storage"),
            ExpectedEdge("Processing Worker", "Search Index")
        ),
        forbiddenClaims = listOf(
            "Elasticsearch cluster",
            "Lambda",
            "CDN",
            "Data Warehouse"
        )
    )

    /** All fixtures evaluated by [EvalRunner]. */
    val ALL: List<EvalFixture> = listOf(
        LOGIN_BILLING_FLOW,
        SERVICE_DEPENDENCIES,
        INGESTION_PIPELINE
    )
}
