/*
 * Copyright (c) 2026, Potaty
 *
 * Unit tests for the pure cost estimator (WS6). No database, no network, no clock.
 */

package com.potaty.backend

import com.potaty.backend.config.ModelTier
import com.potaty.backend.cost.CostEstimate
import com.potaty.backend.cost.CostEstimator
import com.potaty.backend.cost.TierPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CostEstimatorTest {

    @Test
    fun lowIsNeverGreaterThanHigh() {
        val estimator = CostEstimator()
        for (tokens in listOf(0, 1, 50, 1_000, 100_000, 5_000_000)) {
            val e = estimator.estimate(tokens)
            assertTrue(
                e.lowUsd <= e.highUsd,
                "low(${e.lowUsd}) <= high(${e.highUsd}) for $tokens tokens"
            )
            assertTrue(e.lowUsd >= 0.0, "cost is non-negative")
        }
    }

    @Test
    fun costGrowsWithSourceSize() {
        val estimator = CostEstimator()
        val small = estimator.estimate(1_000)
        val large = estimator.estimate(100_000)
        assertTrue(large.highUsd > small.highUsd, "more source tokens -> higher worst-case cost")
        assertTrue(large.lowUsd > small.lowUsd, "more source tokens -> higher best-case cost")
    }

    @Test
    fun midpointIsBetweenLowAndHigh() {
        val e = CostEstimator().estimate(20_000)
        assertTrue(
            e.midUsd in e.lowUsd..e.highUsd,
            "midpoint ${e.midUsd} within [${e.lowUsd}, ${e.highUsd}]"
        )
    }

    @Test
    fun deterministicPricingFromKnownTable() {
        // A single-tier table with simple numbers makes the arithmetic checkable by hand.
        val prices =
            mapOf(ModelTier.MID_STRUCTURED to TierPrice(inputPerKUsd = 1.0, outputPerKUsd = 2.0))
        val estimator = CostEstimator(prices = prices, generationTier = ModelTier.MID_STRUCTURED)

        // 10_000 input tokens.
        // low:  1 pass input (10 * 1.0 = 10.0) + low output (0.10 * 10_000 = 1_000 => 1.0 * 2.0 = 2.0) = 12.0
        // high: 4 passes input (40 * 1.0 = 40.0) + high output (0.40 * 10_000 = 4_000 => 4.0 * 2.0 = 8.0) = 48.0
        val e = estimator.estimate(10_000)
        assertEquals(12.0, e.lowUsd, 1e-9)
        assertEquals(48.0, e.highUsd, 1e-9)
    }

    @Test
    fun cheaperTierCostsLessThanReasoningTier() {
        val cheap = CostEstimator(generationTier = ModelTier.CHEAP_STRUCTURED).estimate(50_000)
        val high = CostEstimator(generationTier = ModelTier.HIGH_REASONING).estimate(50_000)
        assertTrue(
            high.highUsd > cheap.highUsd,
            "high-reasoning tier should cost more than cheap-structured"
        )
    }

    @Test
    fun approximateTokensFromText() {
        // CHARS_PER_TOKEN = 4, with a floor of 1 for non-empty text.
        assertEquals(0, CostEstimator.approximateTokens(""))
        assertEquals(1, CostEstimator.approximateTokens("ab"))
        assertEquals(25, CostEstimator.approximateTokens("x".repeat(100)))
        assertEquals(26, CostEstimator.approximateTokens(listOf("ab", "x".repeat(100))))
    }

    @Test
    fun costOfConcreteCallMatchesTable() {
        val prices =
            mapOf(ModelTier.MID_STRUCTURED to TierPrice(inputPerKUsd = 3.0, outputPerKUsd = 6.0))
        val estimator = CostEstimator(prices = prices)
        // 2_000 input (2 * 3.0 = 6.0) + 1_000 output (1 * 6.0 = 6.0) = 12.0
        assertEquals(12.0, estimator.costOf(ModelTier.MID_STRUCTURED, 2_000, 1_000), 1e-9)
    }

    @Test
    fun rejectsNonFiniteOrNegativeMoneyValues() {
        assertFailsWith<IllegalArgumentException> { CostEstimate(Double.NaN, 1.0) }
        assertFailsWith<IllegalArgumentException> { CostEstimate(-0.01, 1.0) }
        assertFailsWith<IllegalArgumentException> { TierPrice(Double.POSITIVE_INFINITY, 1.0) }
        assertFailsWith<IllegalArgumentException> { TierPrice(1.0, -0.01) }
    }
}
