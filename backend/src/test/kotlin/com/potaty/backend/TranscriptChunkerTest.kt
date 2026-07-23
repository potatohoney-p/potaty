/*
 * Copyright (c) 2026, Potaty
 *
 * Unit tests for TranscriptChunker (plan 12.2 / 14.2): speaker-turn detection, [hh:mm:ss] / (mm:ss)
 * timestamp parsing into startMs/endMs, turn grouping, and the paragraph fallback for unstructured
 * text. Deterministic, no DB / network.
 */

package com.potaty.backend

import com.potaty.backend.source.TranscriptChunker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptChunkerTest {

    @Test
    fun detectsSpeakerColonTurns() {
        val transcript = """
            Alice: we should ship the API on Friday
            Bob: the auth service is still flaky
            Alice: ok I'll fix auth first
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(3, chunks.size, "one chunk per speaker turn")
        assertEquals(listOf("Alice", "Bob", "Alice"), chunks.map { it.speaker })
        assertEquals("we should ship the API on Friday", chunks[0].text)
        assertTrue(chunks.all { it.startMs == null }, "no timestamps present")
        assertEquals(0, chunks.first().chunkIndex)
        assertEquals(2, chunks.last().chunkIndex)
    }

    @Test
    fun detectsKoreanSpeakerTurns() {
        val transcript = """
            지민: 인증 흐름은 이번 주에 마무리할게요
            현우: 좋아요, 배포 전에 부하 테스트도 진행하겠습니다
            [서연] 개인정보 마스킹 결과도 함께 확인해 주세요
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(3, chunks.size)
        assertEquals(listOf("지민", "현우", "서연"), chunks.map { it.speaker })
        assertEquals("인증 흐름은 이번 주에 마무리할게요", chunks[0].text)
        assertEquals("개인정보 마스킹 결과도 함께 확인해 주세요", chunks[2].text)
    }

    @Test
    fun parsesBracketedTimestampsIntoMs() {
        val transcript = """
            [00:00:05] Alice: kickoff
            [00:01:30] Bob: status update
            [01:00:00] Alice: wrap up
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(3, chunks.size)
        assertEquals(5_000, chunks[0].startMs)
        assertEquals(90_000, chunks[1].startMs)
        assertEquals(3_600_000, chunks[2].startMs)
        // Turns tile the timeline: each endMs == next turn's startMs; last is open-ended.
        assertEquals(90_000, chunks[0].endMs)
        assertEquals(3_600_000, chunks[1].endMs)
        assertNull(chunks[2].endMs)
        assertEquals("Alice", chunks[0].speaker)
        assertEquals("kickoff", chunks[0].text)
    }

    @Test
    fun parsesMinuteSecondParenTimestamps() {
        val transcript = """
            (00:10) Carol: opening remarks
            (02:05) Dave: the database keeps timing out
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(2, chunks.size)
        assertEquals(10_000, chunks[0].startMs)
        assertEquals(125_000, chunks[1].startMs)
        assertEquals(125_000, chunks[0].endMs)
        assertEquals("Carol", chunks[0].speaker)
        assertEquals("Dave", chunks[1].speaker)
    }

    @Test
    fun groupsContinuationLinesIntoSameTurn() {
        val transcript = """
            Alice: first point
            and a continuation of the same point
            Bob: a reply
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(2, chunks.size, "continuation line joins Alice's turn")
        assertEquals("Alice", chunks[0].speaker)
        assertEquals("first point\nand a continuation of the same point", chunks[0].text)
        assertEquals(1, chunks[0].startLine)
        assertEquals(2, chunks[0].endLine)
        assertEquals("Bob", chunks[1].speaker)
    }

    @Test
    fun detectsBracketedSpeakerForm() {
        val transcript = """
            [Alice] we should ship on Friday
            [Bob] sounds good
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(transcript)

        assertEquals(2, chunks.size)
        assertEquals("Alice", chunks[0].speaker)
        assertEquals("we should ship on Friday", chunks[0].text)
        assertEquals("Bob", chunks[1].speaker)
    }

    @Test
    fun fallsBackToParagraphChunkingForUnstructuredText() {
        val text = """
            The system has three main components that work together.
            They communicate over HTTP and a message queue.

            A second paragraph describes the data flow in detail
            without any speaker or timestamp markers at all.
        """.trimIndent()

        val chunks = TranscriptChunker.chunk(text)

        assertEquals(2, chunks.size, "two paragraphs -> two chunks")
        assertTrue(chunks.all { it.speaker == null }, "no speakers in plain prose")
        assertTrue(
            chunks.all { it.startMs == null && it.endMs == null },
            "no timestamps in plain prose"
        )
        // Paragraph fallback preserves 1-based line ranges.
        assertEquals(1, chunks[0].startLine)
        assertEquals(2, chunks[0].endLine)
        assertEquals(4, chunks[1].startLine)
    }

    @Test
    fun emptyInputProducesNoChunks() {
        assertTrue(TranscriptChunker.chunk("").isEmpty())
        assertTrue(TranscriptChunker.chunk("   \n  \n").isEmpty())
    }

    @Test
    fun timestampWithSpeakerColonStripsBoth() {
        val transcript = "[00:00:05] Alice: hello there"
        val chunks = TranscriptChunker.chunk(transcript)
        assertEquals(1, chunks.size)
        assertEquals("Alice", chunks[0].speaker)
        assertEquals(5_000, chunks[0].startMs)
        assertEquals("hello there", chunks[0].text)
    }
}
