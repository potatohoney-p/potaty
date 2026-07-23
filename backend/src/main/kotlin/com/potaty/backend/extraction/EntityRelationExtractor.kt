/*
 * Copyright (c) 2026, Potaty
 *
 * Deterministic entity/relation extraction for text & documents (plan section 2.3: static
 * extraction before LLM interpretation). This is the GROUNDED baseline: it recognises explicit
 * structure in the prose — arrow edges ("A -> B", "A --> B: label") and verb edges
 * ("A calls B", "A depends on B", "A writes C") — and cites the exact source line as evidence.
 *
 * The LLM layer (see DiagramPipeline) may later enrich this graph with grouping, summaries and
 * inferred edges, but the diagram is renderable and publishable from this deterministic pass
 * alone, which is what makes the pipeline testable without a live model.
 */

package com.potaty.backend.extraction

import com.potaty.backend.persistence.repositories.StoredChunk
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.EvidenceRef
import com.potaty.ir.NodeType

data class ExtractedEntity(
    val key: String,
    val label: String,
    val type: NodeType,
    val evidence: List<EvidenceRef>,
    val confidence: Double = 1.0
)

data class ExtractedRelation(
    val fromKey: String,
    val toKey: String,
    val type: EdgeType,
    val label: String?,
    val evidence: List<EvidenceRef>,
    val confidence: Double = 0.9,
    val edgeSourceType: EdgeSourceType = EdgeSourceType.DOCUMENT_STATEMENT
)

data class ExtractionResult(
    val entities: List<ExtractedEntity>,
    val relations: List<ExtractedRelation>
)

object EntityRelationExtractor {

    private val ARROW = Regex("""^\s*(.+?)\s*(?:-{1,2}>|=>|→)\s*(.+?)\s*$""")
    private val VERB =
        Regex(
            """^\s*(.+?)\s+(calls|invokes|depends on|reads from|reads|writes to|writes|""" +
                """sends to|sends|publishes to|publishes|consumes|subscribes to|queries|uses|""" +
                """notifies|authenticates|returns to|responds to)\s+(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )
    private val BULLET = Regex("""^\s*[-*•]\s+(.+?)\s*$""")
    private val STATEMENT_SPLIT = Regex("""(?<=[.!?。！？])\s*|[;；]\s*""")
    private val KOREAN_SUBJECT_OBJECT_VERB =
        Regex("""^\s*(.{1,80}?)(?:이|가|은|는)\s+(.{1,80}?)(?:을|를)\s+(.{1,80}?)(?:다|요)\s*$""")
    private val KOREAN_CLAUSE_SPLIT =
        Regex("""(?:하고|하며|한\s+뒤|한\s+후|한\s+다음(?:에)?|하고\s+나서)\s+|[,，]\s*""")
    private val KOREAN_SUBJECT_PREFIX =
        Regex("""^\s*(.{1,60}?)(?:이|가|은|는)\s+(.+?)\s*$""")
    private val KOREAN_SOURCE_OBJECT_ACTION =
        Regex("""^(.{1,60}?)(?:에서|에게서)\s+(.{1,60}?)(?:을|를)\s+(.+?)\s*$""")
    private val KOREAN_OBJECT_DESTINATION_ACTION =
        Regex("""^(.{1,60}?)(?:을|를)\s+(.{1,60}?)(?:에서|에게서)\s+(.+?)\s*$""")
    private val KOREAN_DESTINATION_OBJECT_ACTION =
        Regex("""^(.{1,60}?)(?:에게|에|으로|로)\s+(.{1,60}?)(?:을|를)\s+(.+?)\s*$""")
    private val KOREAN_OBJECT_ACTION =
        Regex("""^(.{1,60}?)(?:을|를)\s+(.+?)\s*$""")
    private val KOREAN_DESTINATION_ACTION =
        Regex("""^(.{1,60}?)(?:에게|에|으로|로)\s+(.+?)\s*$""")

    fun extract(chunks: List<StoredChunk>): ExtractionResult {
        val entities = LinkedHashMap<String, ExtractedEntity>()
        val relations = LinkedHashSet<RelKey>()
        val relationList = mutableListOf<ExtractedRelation>()

        fun upsertEntity(rawLabel: String, evidence: EvidenceRef) {
            val label = cleanLabel(rawLabel)
            if (label.isEmpty()) return
            val key = canonicalKey(label)
            val existing = entities[key]
            if (existing == null) {
                entities[key] = ExtractedEntity(key, label, inferNodeType(label), listOf(evidence))
            } else if (
                existing.evidence.none {
                    it.sourceChunkId == evidence.sourceChunkId && it.startLine == evidence.startLine
                }
            ) {
                entities[key] = existing.copy(evidence = existing.evidence + evidence)
            }
        }

        for (chunk in chunks) {
            val lines = chunk.text.split("\n")
            lines.forEachIndexed { offset, rawLine ->
                val lineNo = (chunk.startLine ?: 1) + offset
                rawLine.split(STATEMENT_SPLIT).forEach statement@{ rawStatement ->
                    val line = rawStatement.trim()
                    if (line.isEmpty()) return@statement
                    val evidence = evidenceFor(chunk, lineNo, line)

                    val arrow = ARROW.matchEntire(line)
                    val verb = if (arrow == null) VERB.matchEntire(line) else null
                    val koreanRelations =
                        if (arrow == null && verb == null) {
                            parseKoreanRelations(line)
                        } else {
                            emptyList()
                        }
                    val korean =
                        if (koreanRelations.isEmpty() && arrow == null && verb == null) {
                            KOREAN_SUBJECT_OBJECT_VERB.matchEntire(
                                line.trimEnd('.', '!', '?', '。', '！', '？')
                            )
                        } else {
                            null
                        }
                    when {
                        arrow != null -> {
                            val (lhs, rhsRaw) = arrow.destructured
                            val (rhs, label) = splitLabel(rhsRaw)
                            upsertEntity(lhs, evidence)
                            upsertEntity(rhs, evidence)
                            addRelation(
                                relations,
                                relationList,
                                lhs,
                                rhs,
                                EdgeType.RELATES_TO,
                                label,
                                evidence
                            )
                        }
                        verb != null -> {
                            val (lhs, verbWord, rhsRaw) = verb.destructured
                            val (rhs, label) = splitLabel(rhsRaw)
                            upsertEntity(lhs, evidence)
                            upsertEntity(rhs, evidence)
                            addRelation(
                                relations,
                                relationList,
                                lhs,
                                rhs,
                                edgeTypeFor(verbWord),
                                label ?: verbWord.lowercase(),
                                evidence
                            )
                        }
                        koreanRelations.isNotEmpty() -> {
                            koreanRelations.forEach { relation ->
                                upsertEntity(relation.subject, evidence)
                                upsertEntity(relation.target, evidence)
                                addRelation(
                                    relations,
                                    relationList,
                                    relation.subject,
                                    relation.target,
                                    edgeTypeForKorean(relation.action),
                                    relation.action,
                                    evidence
                                )
                            }
                        }
                        korean != null -> {
                            val (subject, target, action) = korean.destructured
                            upsertEntity(subject, evidence)
                            upsertEntity(target, evidence)
                            addRelation(
                                relations,
                                relationList,
                                subject,
                                target,
                                edgeTypeForKorean(action),
                                action.trim().take(40),
                                evidence
                            )
                        }
                        else -> {
                            // A sentence, bullet, or "Name: description" entry becomes a grounded
                            // standalone node. Labels are bounded in cleanLabel; rejecting a whole
                            // paragraph merely because it exceeded 80 characters caused successful
                            // jobs with an empty diagram.
                            val bullet = BULLET.matchEntire(line)
                            val nodeLine = bullet?.groupValues?.get(1) ?: line
                            val (name, _) = splitLabel(nodeLine)
                            if (name.isNotBlank()) upsertEntity(name, evidence)
                        }
                    }
                }
            }
        }

        return ExtractionResult(entities.values.toList(), relationList)
    }

    private data class RelKey(val from: String, val to: String, val type: EdgeType)

    private data class KoreanRelation(
        val subject: String,
        val target: String,
        val action: String
    )

    private fun parseKoreanRelations(raw: String): List<KoreanRelation> {
        val line = raw.trimEnd('.', '!', '?', '。', '！', '？')
        var subject: String? = null
        val relations = mutableListOf<KoreanRelation>()
        for (rawClause in line.split(KOREAN_CLAUSE_SPLIT)) {
            val clause = rawClause.trim()
            if (clause.isEmpty()) continue
            val subjectMatch = KOREAN_SUBJECT_PREFIX.matchEntire(clause)
            val body =
                if (subjectMatch != null) {
                    subject = cleanLabel(subjectMatch.groupValues[1])
                    subjectMatch.groupValues[2]
                } else {
                    clause
                }
            val activeSubject = subject?.takeIf { it.isNotBlank() } ?: continue
            parseKoreanBody(activeSubject, body)?.let(relations::add)
        }
        return relations
    }

    private fun parseKoreanBody(subject: String, body: String): KoreanRelation? {
        // Topic-first Korean often embeds the real actor after a condition:
        // "인증 실패는 감사 서비스가 Kafka로 ...". Treat the explicit nested subject as the
        // actor instead of merging it into the destination label.
        val nestedSubject = KOREAN_SUBJECT_PREFIX.matchEntire(body)
        val activeSubject: String
        val activeBody: String
        if (nestedSubject != null) {
            activeSubject = cleanLabel(nestedSubject.groupValues[1])
            activeBody = nestedSubject.groupValues[2]
        } else {
            activeSubject = subject
            activeBody = body
        }

        val source = KOREAN_SOURCE_OBJECT_ACTION.matchEntire(activeBody)
        if (source != null) {
            return koreanRelation(
                activeSubject,
                source.groupValues[1],
                source.groupValues[2],
                source.groupValues[3]
            )
        }
        // Korean commonly puts the object before a location:
        // "실패율을 대시보드에서 확인한다". The location is the diagram target and the object
        // remains part of the edge label.
        val objectDestination = KOREAN_OBJECT_DESTINATION_ACTION.matchEntire(activeBody)
        if (objectDestination != null) {
            return koreanRelation(
                activeSubject,
                objectDestination.groupValues[2],
                objectDestination.groupValues[1],
                objectDestination.groupValues[3]
            )
        }
        val destinationObject = KOREAN_DESTINATION_OBJECT_ACTION.matchEntire(activeBody)
        if (destinationObject != null) {
            return koreanRelation(
                activeSubject,
                destinationObject.groupValues[1],
                destinationObject.groupValues[2],
                destinationObject.groupValues[3]
            )
        }
        val objectAction = KOREAN_OBJECT_ACTION.matchEntire(activeBody)
        if (objectAction != null) {
            return koreanRelation(
                activeSubject,
                objectAction.groupValues[1],
                "",
                objectAction.groupValues[2]
            )
        }
        val destination = KOREAN_DESTINATION_ACTION.matchEntire(activeBody) ?: return null
        return koreanRelation(
            activeSubject,
            destination.groupValues[1],
            "",
            destination.groupValues[2]
        )
    }

    private fun koreanRelation(
        subject: String,
        target: String,
        objectLabel: String,
        rawAction: String
    ): KoreanRelation? {
        val cleanSubject = cleanLabel(subject)
        val cleanTarget = cleanLabel(target)
        val action = compactKoreanAction(objectLabel, rawAction)
        if (cleanSubject.isEmpty() || cleanTarget.isEmpty() || action.isEmpty()) return null
        return KoreanRelation(cleanSubject, cleanTarget, action)
    }

    private fun compactKoreanAction(objectLabel: String, rawAction: String): String {
        val normalizedAction =
            rawAction
                .replace(Regex("""보냅니다$"""), "보냄")
                .replace(Regex("""받습니다$"""), "받음")
        val action =
            normalizedAction
                .trim()
                .trimEnd('.', '!', '?', '。', '！', '？')
                .replace(
                    Regex(
                        """(?:하도록\s*)?(?:해야\s*)?(?:합시다|합니다|하였다|했다|한다|""" +
                            """해요|하다|됩니다|된다|시킵니다|시킨다|요|다)$"""
                    ),
                    ""
                )
                .trim()
        return listOf(cleanLabel(objectLabel), action)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(40)
    }

    private fun addRelation(
        seen: MutableSet<RelKey>,
        out: MutableList<ExtractedRelation>,
        fromRaw: String,
        toRaw: String,
        type: EdgeType,
        label: String?,
        evidence: EvidenceRef
    ) {
        val from = canonicalKey(cleanLabel(fromRaw))
        val to = canonicalKey(cleanLabel(toRaw))
        if (from.isEmpty() || to.isEmpty() || from == to) return
        val rk = RelKey(from, to, type)
        if (seen.add(rk)) {
            out.add(
                ExtractedRelation(
                    from,
                    to,
                    type,
                    label?.takeIf { it.isNotBlank() },
                    listOf(evidence),
                    edgeSourceType =
                    if (
                        evidence.startMs != null ||
                        evidence.endMs != null ||
                        evidence.speaker != null
                    ) {
                        EdgeSourceType.TRANSCRIPT_STATEMENT
                    } else {
                        EdgeSourceType.DOCUMENT_STATEMENT
                    }
                )
            )
        }
    }

    private fun evidenceFor(chunk: StoredChunk, lineNo: Int, line: String) =
        EvidenceRef(
            sourceChunkId = chunk.id.toString(),
            sourceVersionId = chunk.sourceVersionId.toString(),
            path = chunk.path,
            startLine = lineNo,
            endLine = lineNo,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            speaker = chunk.speaker,
            quote = line.take(240),
            quoteHash = sha256(line)
        )

    private fun splitLabel(raw: String): Pair<String, String?> {
        // "B: does X" / "B [does X]" -> (B, "does X")
        val colon = raw.indexOf(':')
        if (colon in 1 until raw.length - 1) {
            return raw.substring(0, colon).trim() to raw.substring(colon + 1).trim()
        }
        val br = Regex("""^(.+?)\s*\[(.+?)]\s*$""").matchEntire(raw.trim())
        if (br != null) return br.groupValues[1].trim() to br.groupValues[2].trim()
        return raw.trim() to null
    }

    private fun cleanLabel(s: String): String =
        s.trim().trim('"', '\'', '`', '.', ',', ';').replace(Regex("""\s+"""), " ").take(80)

    private fun canonicalKey(label: String): String =
        label
            .lowercase()
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

    private fun edgeTypeFor(verb: String): EdgeType =
        when (verb.lowercase().trim()) {
            "calls",
            "invokes" -> EdgeType.CALLS
            "depends on",
            "uses" -> EdgeType.DEPENDS_ON
            "reads",
            "reads from",
            "queries" -> EdgeType.READS
            "writes",
            "writes to" -> EdgeType.WRITES
            "publishes",
            "publishes to" -> EdgeType.PUBLISHES
            "consumes",
            "subscribes to" -> EdgeType.CONSUMES
            "authenticates" -> EdgeType.AUTHENTICATES
            "responds to",
            "returns to" -> EdgeType.RESPONSE
            "sends",
            "sends to",
            "notifies" -> EdgeType.REQUEST
            else -> EdgeType.RELATES_TO
        }

    private fun edgeTypeForKorean(action: String): EdgeType =
        when {
            action.contains("호출") -> EdgeType.CALLS
            action.contains("의존") || action.contains("사용") -> EdgeType.DEPENDS_ON
            action.contains("읽") || action.contains("조회") -> EdgeType.READS
            action.contains("쓰") ||
                action.contains("저장") ||
                action.contains("기록") ||
                action.contains("생성") ||
                action.contains("만들") -> EdgeType.WRITES
            action.contains("발행") || action.contains("게시") -> EdgeType.PUBLISHES
            action.contains("소비") || action.contains("구독") -> EdgeType.CONSUMES
            action.contains("인증") || action.contains("검증") -> EdgeType.AUTHENTICATES
            action.contains("반환") || action.contains("응답") -> EdgeType.RESPONSE
            action.contains("전송") ||
                action.contains("보내") ||
                action.contains("요청") ||
                action.contains("입력") ||
                action.contains("알림") -> EdgeType.REQUEST
            action.contains("배포") -> EdgeType.DEPLOYS_TO
            else -> EdgeType.RELATES_TO
        }

    private fun inferNodeType(label: String): NodeType {
        val l = label.lowercase()
        return when {
            listOf("database", "데이터베이스", "db", "postgres", "mysql", "mongo", "sql").any {
                l.contains(it)
            } -> NodeType.DATABASE
            listOf("cache", "캐시", "redis", "memcached").any { l.contains(it) } -> NodeType.CACHE
            listOf("queue", "큐", "kafka", "rabbit", "sqs", "topic", "stream").any {
                l.contains(it)
            } -> NodeType.QUEUE
            listOf("gateway", "게이트웨이", "api gateway", "ingress", "load balancer").any {
                l.contains(it)
            } -> NodeType.GATEWAY
            listOf("frontend", "프론트엔드", "web app", "웹 앱", "ui", "browser", "client app").any {
                l.contains(it)
            } -> NodeType.FRONTEND
            listOf("user", "사용자", "customer", "고객", "actor", "admin", "관리자").any {
                l == it || l.contains("$it ")
            } ->
                NodeType.USER
            listOf("worker", "consumer", "cron", "scheduler", "job").any { l.contains(it) } ->
                NodeType.WORKER
            listOf("external", "third-party", "3rd party", "stripe", "twilio").any {
                l.contains(it)
            } -> NodeType.EXTERNAL_SERVICE
            listOf("service", "서비스", "api", "server", "서버", "backend", "백엔드", "microservice").any {
                l.contains(it)
            } -> NodeType.SERVICE
            else -> NodeType.GENERIC
        }
    }
}

private fun sha256(value: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
