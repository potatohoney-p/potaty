/*
 * Copyright (c) 2026, Potaty
 *
 * Transcript-aware chunker (plan sections 12.2 + 14.2). A meeting / call transcript carries two
 * extra evidence dimensions over plain prose: WHO spoke (speaker) and WHEN (start/end ms). This
 * chunker recognises both so downstream IR evidence can cite a speaker turn and a time range.
 *
 * Recognised shapes (case/spacing tolerant), evaluated per line:
 *   - Speaker turns:   "Alice: we should ship on Friday"           -> speaker="Alice"
 *                      "[Bob] the API is flaky"                     -> speaker="Bob"
 *   - Timestamps:      "[00:12:30] Alice: ..."  / "(01:05) ..."     -> startMs from hh:mm:ss / mm:ss
 *                      "12:30 Alice: ..."                           -> startMs from mm:ss (bare)
 *
 * A chunk is one speaker turn (consecutive lines until the speaker changes, a new timestamp turn
 * begins, or a blank line). Each chunk's [startMs] is the first timestamp seen in the turn and
 * [endMs] is the NEXT turn's startMs (so turns tile the timeline); the final turn's endMs is null.
 *
 * When no speaker/timestamp structure is detected at all, this falls back to the plain paragraph
 * [Chunker] so arbitrary text still chunks sensibly (speaker/startMs/endMs left null).
 */

package com.potaty.backend.source

import kotlinx.serialization.Serializable

@Serializable
data class TranscriptChunk(
    val chunkIndex: Int,
    val path: String?,
    val startLine: Int,
    val endLine: Int,
    val speaker: String?,
    val startMs: Int?,
    val endMs: Int?,
    val text: String,
    val tokenCount: Int
) {
    val textHash: String
        get() = sha256(text)
}

object TranscriptChunker {

    // [00:12:30] / [12:30] / (01:05) / (1:05:00) — leading bracketed/parenthesized timestamp.
    private val BRACKETED_TS =
        Regex("""^\s*[\[(]\s*(\d{1,2}:)?(\d{1,2}):(\d{2})(?:\.\d{1,3})?\s*[\])]\s*""")

    // Bare leading "12:30 " or "01:02:03 " timestamp (no brackets), followed by content.
    private val BARE_TS = Regex("""^\s*(\d{1,2}:)?(\d{1,2}):(\d{2})(?:\.\d{1,3})?\s+(?=\S)""")

    // "Speaker: rest" — speaker is a short-ish name/label with no internal colon, then ": ".
    private val SPEAKER_COLON =
        Regex("""^\s*([\p{L}\p{N}][\p{L}\p{N}\p{M}_ .,'\-/&]{0,48}?)\s*:\s+(.*\S.*)$""")

    // "[Speaker] rest" — bracketed speaker (distinct from a bracketed timestamp).
    private val SPEAKER_BRACKET =
        Regex("""^\s*\[([\p{L}\p{N}][\p{L}\p{N}\p{M}_ .'\-/&]{0,48}?)]\s*(.*\S.*)$""")

    /**
     * Chunks [canonicalText] into one chunk per speaker turn. Falls back to [Chunker] (paragraph
     * chunking) when the text has no recognisable transcript structure.
     */
    fun chunk(canonicalText: String, path: String? = null): List<TranscriptChunk> {
        if (canonicalText.isBlank()) return emptyList()
        val lines = canonicalText.split("\n")

        // Parse each non-blank line into (speaker?, startMs?, content). 1-based line numbers.
        data class ParsedLine(
            val lineNo: Int,
            val speaker: String?,
            val startMs: Int?,
            val content: String
        )

        val parsed = mutableListOf<ParsedLine>()
        var sawStructure = false
        lines.forEachIndexed { idx, raw ->
            if (raw.isBlank()) return@forEachIndexed
            var rest = raw
            var startMs: Int? = null
            var speaker: String? = null

            val bts = BRACKETED_TS.find(rest)
            if (bts != null) {
                startMs = toMs(bts.groupValues[1], bts.groupValues[2], bts.groupValues[3])
                rest = rest.substring(bts.range.last + 1)
                sawStructure = true
            } else {
                val barets = BARE_TS.find(rest)
                if (barets != null) {
                    startMs =
                        toMs(
                            barets.groupValues[1],
                            barets.groupValues[2],
                            barets.groupValues[3]
                        )
                    rest = rest.substring(barets.range.last + 1)
                    sawStructure = true
                }
            }

            val sb = SPEAKER_BRACKET.matchEntire(rest)
            if (sb != null) {
                speaker = sb.groupValues[1].trim()
                rest = sb.groupValues[2].trim()
                sawStructure = true
            } else {
                val sc = SPEAKER_COLON.matchEntire(rest)
                if (sc != null && looksLikeSpeaker(sc.groupValues[1])) {
                    speaker = sc.groupValues[1].trim()
                    rest = sc.groupValues[2].trim()
                    sawStructure = true
                }
            }

            parsed.add(ParsedLine(idx + 1, speaker, startMs, rest.trim()))
        }

        if (!sawStructure || parsed.isEmpty()) {
            // No transcript structure -> reuse paragraph chunking, carrying no speaker/time.
            return Chunker.chunk(canonicalText, path).map { c ->
                TranscriptChunk(
                    chunkIndex = c.chunkIndex,
                    path = c.path,
                    startLine = c.startLine,
                    endLine = c.endLine,
                    speaker = null,
                    startMs = null,
                    endMs = null,
                    text = c.text,
                    tokenCount = c.tokenCount
                )
            }
        }

        // Group consecutive lines into turns. A new turn starts when a line names a speaker (and it
        // differs from the running speaker) or carries its own timestamp; continuation lines (no
        // speaker, no timestamp) join the current turn.
        data class Turn(
            val startLine: Int,
            var endLine: Int,
            val speaker: String?,
            val startMs: Int?,
            val body: MutableList<String>
        )

        val turns = mutableListOf<Turn>()
        for (p in parsed) {
            val current = turns.lastOrNull()
            val startsNewTurn =
                current == null ||
                    (p.speaker != null && p.speaker != current.speaker) ||
                    (p.startMs != null)
            if (startsNewTurn) {
                // A timestamp-only line with no named speaker inherits the running speaker.
                val turnSpeaker = p.speaker ?: if (p.startMs != null) current?.speaker else null
                turns.add(
                    Turn(
                        startLine = p.lineNo,
                        endLine = p.lineNo,
                        speaker = turnSpeaker,
                        startMs = p.startMs,
                        body =
                        if (p.content.isEmpty()) mutableListOf() else mutableListOf(p.content)
                    )
                )
            } else {
                current!!
                current.endLine = p.lineNo
                if (p.content.isNotEmpty()) current.body.add(p.content)
            }
        }

        // endMs of a turn = startMs of the next turn that has one (turns tile the timeline).
        return turns.mapIndexed { i, turn ->
            val nextStart = turns.drop(i + 1).firstOrNull { it.startMs != null }?.startMs
            val text = turn.body.joinToString("\n")
            TranscriptChunk(
                chunkIndex = i,
                path = path,
                startLine = turn.startLine,
                endLine = turn.endLine,
                speaker = turn.speaker,
                startMs = turn.startMs,
                endMs = if (turn.startMs != null) nextStart else null,
                text = text,
                tokenCount = (text.length / 4).coerceAtLeast(1)
            )
        }
    }

    /** Converts an optional "hh:" group plus mm + ss into milliseconds. */
    private fun toMs(hhGroup: String, mm: String, ss: String): Int {
        val hours = hhGroup.trim().trimEnd(':').toIntOrNull() ?: 0
        val minutes = mm.toIntOrNull() ?: 0
        val seconds = ss.toIntOrNull() ?: 0
        return ((hours * 3600) + (minutes * 60) + seconds) * 1000
    }

    /**
     * Guards the "Speaker:" rule against false positives like "Note: see below" or a URL. A speaker
     * label is short, mostly a name/handle, and not a sentence — at most a few words, no terminal
     * punctuation pulling it into prose.
     */
    private fun looksLikeSpeaker(candidate: String): Boolean {
        val c = candidate.trim()
        if (c.isEmpty() || c.length > 48) return false
        if (c.contains("//") || c.contains("http")) return false
        val words = c.split(Regex("""\s+"""))
        return words.size <= 4 && c.any { it.isLetter() }
    }
}
