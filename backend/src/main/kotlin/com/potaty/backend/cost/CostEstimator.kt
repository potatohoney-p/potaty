/*
 * Copyright (c) 2026, Potaty
 *
 * Pure, unit-testable cost estimation (WS6; plan 3.4 cost tracking, 22.5 generation request).
 *
 * Given an approximate count of source tokens that will be fed through the generation pipeline,
 * estimate a (lowUsd .. highUsd) range. The estimate is deliberately conservative and provider-
 * agnostic: it prices the input the pipeline must read, plus a bounded amount of generated output,
 * against a per-[ModelTier] USD-per-1K-token price table. The range brackets a "cheap path"
 * (input read once, small output) against a "worst path" (input re-read across multiple grounded
 * stages + larger output), so the quota guard has a real upper bound to reason about.
 *
 * Nothing here touches the database, the network, or wall-clock time, so it is trivially testable.
 */

package com.potaty.backend.cost

import com.potaty.backend.config.ModelTier

/** USD price per 1,000 tokens for a single [ModelTier], split by input vs. output. */
data class TierPrice(
    val inputPerKUsd: Double,
    val outputPerKUsd: Double
) {
    init {
        require(inputPerKUsd.isFinite() && inputPerKUsd >= 0.0) {
            "inputPerKUsd must be finite and non-negative"
        }
        require(outputPerKUsd.isFinite() && outputPerKUsd >= 0.0) {
            "outputPerKUsd must be finite and non-negative"
        }
    }
}

/**
 * A USD cost interval. [lowUsd] <= [highUsd] always holds for values produced by [CostEstimator].
 */
data class CostEstimate(
    val lowUsd: Double,
    val highUsd: Double
) {
    init {
        require(lowUsd.isFinite() && highUsd.isFinite()) { "cost estimate must be finite" }
        require(lowUsd >= 0.0) { "cost estimate must not be negative" }
        require(lowUsd <= highUsd) { "lowUsd ($lowUsd) must be <= highUsd ($highUsd)" }
    }

    /** Midpoint of the interval; what UsageRecorder books as the "expected" charge. */
    val midUsd: Double
        get() = (lowUsd + highUsd) / 2.0
}

/**
 * Estimates LLM spend for a generation job from approximate source token counts.
 *
 * @param prices per-tier price table (defaults to [DEFAULT_PRICES]; override from config/tests).
 * @param generationTier the tier the diagram-generation stages run at (default MID_STRUCTURED).
 */
class CostEstimator(
    private val prices: Map<ModelTier, TierPrice> = DEFAULT_PRICES,
    private val generationTier: ModelTier = ModelTier.MID_STRUCTURED
) {

    /**
     * Estimate the cost range for a job whose grounding input is [sourceTokens] tokens.
     *
     * Low end: the input is read once and produces a small structured IR. High end: the input is
     * re-read across [MAX_GROUNDED_PASSES] grounded stages (extract, plan, assemble, critic/repair)
     * and produces a larger output.
     */
    fun estimate(sourceTokens: Int): CostEstimate {
        val price = priceFor(generationTier)
        val inTokens = sourceTokens.coerceAtLeast(0)

        // Output is modeled as a fraction of the input, clamped to a sane floor/ceiling so tiny
        // inputs still carry a minimal generation cost and huge inputs do not explode the estimate.
        val lowOut =
            (inTokens * LOW_OUTPUT_RATIO)
                .toInt()
                .coerceIn(
                    MIN_OUTPUT_TOKENS,
                    MAX_OUTPUT_TOKENS
                )
        val highOut =
            (inTokens * HIGH_OUTPUT_RATIO)
                .toInt()
                .coerceIn(
                    MIN_OUTPUT_TOKENS,
                    MAX_OUTPUT_TOKENS
                )

        val low = cost(price, inputTokens = inTokens, outputTokens = lowOut, passes = 1)
        val high =
            cost(
                price,
                inputTokens = inTokens,
                outputTokens = highOut,
                passes = MAX_GROUNDED_PASSES
            )

        // Guard against rounding making low > high for degenerate inputs.
        return CostEstimate(lowUsd = minOf(low, high), highUsd = maxOf(low, high))
    }

    /**
     * Estimate the cost of a single recorded LLM call given concrete token counts and a tier. Used
     * by callers that already know the real usage (e.g. recording after a provider returns).
     */
    fun costOf(tier: ModelTier, inputTokens: Int, outputTokens: Int): Double =
        cost(
            priceFor(tier),
            inputTokens.coerceAtLeast(0),
            outputTokens.coerceAtLeast(0),
            passes = 1
        )

    private fun cost(price: TierPrice, inputTokens: Int, outputTokens: Int, passes: Int): Double {
        val inCost = (inputTokens.toDouble() / 1000.0) * price.inputPerKUsd * passes
        val outCost = (outputTokens.toDouble() / 1000.0) * price.outputPerKUsd
        return inCost + outCost
    }

    private fun priceFor(tier: ModelTier): TierPrice =
        prices[tier] ?: prices[ModelTier.MID_STRUCTURED] ?: DEFAULT_MID_PRICE

    companion object {
        /** Rough characters-per-token used to approximate token counts from raw text length. */
        const val CHARS_PER_TOKEN = 4

        /** Approximate the token count of a block of text (matches Chunker's heuristic). */
        fun approximateTokens(text: String): Int =
            (text.length / CHARS_PER_TOKEN).coerceAtLeast(if (text.isEmpty()) 0 else 1)

        /** Sum approximate tokens across several source texts. */
        fun approximateTokens(texts: List<String>): Int = texts.sumOf { approximateTokens(it) }

        private const val LOW_OUTPUT_RATIO = 0.10
        private const val HIGH_OUTPUT_RATIO = 0.40
        private const val MIN_OUTPUT_TOKENS = 256
        private const val MAX_OUTPUT_TOKENS = 8192

        /**
         * extract -> plan -> assemble -> critic/repair: the input may be re-read up to this many
         * times.
         */
        private const val MAX_GROUNDED_PASSES = 4

        private val DEFAULT_MID_PRICE = TierPrice(inputPerKUsd = 0.0030, outputPerKUsd = 0.0150)

        /**
         * Conservative default price table (USD / 1K tokens). These are configuration-shaped
         * defaults, not contractual provider prices; production overrides them from config.
         */
        val DEFAULT_PRICES: Map<ModelTier, TierPrice> =
            mapOf(
                ModelTier.CHEAP_STRUCTURED to
                    TierPrice(inputPerKUsd = 0.00015, outputPerKUsd = 0.00060),
                ModelTier.MID_STRUCTURED to DEFAULT_MID_PRICE,
                ModelTier.HIGH_REASONING to
                    TierPrice(inputPerKUsd = 0.0150, outputPerKUsd = 0.0600),
                ModelTier.EMBEDDINGS to TierPrice(inputPerKUsd = 0.00002, outputPerKUsd = 0.0),
                ModelTier.TRANSCRIPTION to TierPrice(inputPerKUsd = 0.0001, outputPerKUsd = 0.0)
            )
    }
}
