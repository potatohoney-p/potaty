/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.backend.extraction

import com.potaty.backend.persistence.repositories.StoredChunk
import com.potaty.ir.EdgeSourceType
import com.potaty.ir.EdgeType
import com.potaty.ir.NodeType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityRelationExtractorTest {

    @Test
    fun koreanPromptParagraphProducesGroundedRelations() {
        val result =
            EntityRelationExtractor.extract(
                listOf(
                    chunk(
                        "사용자가 프롬프트를 입력한다. Potaty API가 입력을 안전하게 정규화한다. " +
                            "Extractor가 Diagram IR을 만든다. Renderer가 ASCII와 Mermaid를 생성한다. " +
                            "각 결과는 evidence를 유지한다."
                    )
                )
            )

        assertEquals(5, result.relations.size)
        assertTrue(
            result.entities.map { it.label }.containsAll(listOf("사용자", "프롬프트", "Potaty API"))
        )
        assertTrue(
            result.relations.any { it.type == EdgeType.REQUEST && it.label?.contains("입력") == true }
        )
        assertTrue(
            result.relations.any { it.type == EdgeType.WRITES && it.label?.contains("생성") == true }
        )
        assertTrue(
            result.relations.all { relation ->
                relation.evidence.singleOrNull()?.quote?.isNotBlank() == true
            }
        )
    }

    @Test
    fun koreanMultiClauseArchitectureProducesConciseTypedNodesAndEdges() {
        val result =
            EntityRelationExtractor.extract(
                listOf(
                    chunk(
                        "사용자가 웹 앱에 로그인한다. " +
                            "웹 앱은 API 게이트웨이에 세션 검증을 요청한다. " +
                            "API는 PostgreSQL에서 계정을 조회하고 Redis 캐시를 갱신한 뒤 " +
                            "Kafka에 감사 이벤트를 발행한다."
                    )
                )
            )

        val byLabel = result.entities.associateBy { it.label }
        assertTrue(
            byLabel.keys.containsAll(
                listOf(
                    "사용자",
                    "웹 앱",
                    "API 게이트웨이",
                    "API",
                    "PostgreSQL",
                    "Redis 캐시",
                    "Kafka"
                )
            )
        )
        assertEquals(NodeType.USER, byLabel.getValue("사용자").type)
        assertEquals(NodeType.FRONTEND, byLabel.getValue("웹 앱").type)
        assertEquals(NodeType.GATEWAY, byLabel.getValue("API 게이트웨이").type)
        assertEquals(NodeType.DATABASE, byLabel.getValue("PostgreSQL").type)
        assertEquals(NodeType.CACHE, byLabel.getValue("Redis 캐시").type)
        assertEquals(NodeType.QUEUE, byLabel.getValue("Kafka").type)
        assertTrue(result.relations.any { it.label == "로그인" })
        assertTrue(result.relations.any { it.label == "세션 검증 요청" })
        assertTrue(result.relations.any { it.label == "계정 조회" })
        assertTrue(result.relations.any { it.label == "갱신" })
        assertTrue(result.relations.any { it.label == "감사 이벤트 발행" })
        assertTrue(result.relations.all { it.label.orEmpty().length <= 40 })
    }

    @Test
    fun koreanTranscriptTopicsDoNotMergeActorsWithDestinations() {
        val result =
            EntityRelationExtractor.extract(
                listOf(
                    chunk(
                        "웹 앱은 API 게이트웨이에 로그인 요청을 보냅니다. " +
                            "인증 실패는 감사 서비스가 Kafka로 보안 이벤트를 발행하도록 합시다. " +
                            "운영팀은 실패율과 처리 지연을 대시보드에서 확인합니다."
                    )
                )
            )

        val labels = result.entities.map { it.label }.toSet()
        assertTrue(
            labels.containsAll(
                setOf("웹 앱", "API 게이트웨이", "감사 서비스", "Kafka", "운영팀", "대시보드")
            )
        )
        assertTrue("감사 서비스가 Kafka" !in labels)
        assertTrue("실패율과 처리 지연" !in labels)
        assertTrue(
            result.relations.any {
                it.fromKey == "감사 서비스" &&
                    it.toKey == "kafka" &&
                    it.label == "보안 이벤트 발행"
            }
        )
        assertTrue(
            result.relations.any {
                it.fromKey == "운영팀" &&
                    it.toKey == "대시보드" &&
                    it.label == "실패율과 처리 지연 확인"
            }
        )
    }

    @Test
    fun longUnstructuredParagraphStillProducesABoundedGroundedNode() {
        val text = "구조화 표식이 없는 긴 설명도 빈 성공 결과가 되어서는 안 됩니다. ".repeat(8)
        val result = EntityRelationExtractor.extract(listOf(chunk(text)))

        assertTrue(result.entities.isNotEmpty())
        assertTrue(result.entities.all { it.label.length <= 80 })
        assertTrue(result.entities.all { it.evidence.isNotEmpty() })
    }

    @Test
    fun transcriptEvidenceRetainsSourceSpeakerAndTimeline() {
        val sourceVersionId = UUID.randomUUID()
        val source =
            StoredChunk(
                id = UUID.randomUUID(),
                sourceVersionId = sourceVersionId,
                chunkIndex = 0,
                path = "meeting.txt",
                startLine = 7,
                endLine = 7,
                text = "Alice calls Billing API",
                startMs = 12_000,
                endMs = 18_500,
                speaker = "Alice"
            )

        val relation = EntityRelationExtractor.extract(listOf(source)).relations.single()
        val evidence = relation.evidence.single()

        assertEquals(EdgeSourceType.TRANSCRIPT_STATEMENT, relation.edgeSourceType)
        assertEquals(sourceVersionId.toString(), evidence.sourceVersionId)
        assertEquals("Alice", evidence.speaker)
        assertEquals(12_000, evidence.startMs)
        assertEquals(18_500, evidence.endMs)
        assertEquals(7, evidence.startLine)
    }

    private fun chunk(text: String) =
        StoredChunk(
            id = UUID.randomUUID(),
            sourceVersionId = UUID.randomUUID(),
            chunkIndex = 0,
            path = null,
            startLine = 1,
            endLine = 1,
            text = text
        )
}
