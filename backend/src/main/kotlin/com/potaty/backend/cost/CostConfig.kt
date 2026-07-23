/*
 * Copyright (c) 2026, Potaty
 *
 * Cost/quota configuration (WS6; plan 3.4 + 20 cost control). Kept in its own type so the
 * existing AppConfig does not have to grow for this workstream; AppGraph builds it from the
 * environment alongside the rest of the graph. Model IDs and limits are configuration, never
 * source constants (plan 3.4).
 */

package com.potaty.backend.cost

import com.potaty.backend.config.EnvConfig

/**
 * @param monthlyCapUsd per-workspace month-to-date spend cap, in USD. A non-positive value
 *        disables enforcement (no cap), which the [QuotaGuard] treats as "unlimited".
 */
data class CostConfig(
    val monthlyCapUsd: Double,
    /** Configurable provider-rate assumption; this is an estimate, not a billing contract. */
    val transcriptionUsdPerMinute: Double = DEFAULT_TRANSCRIPTION_USD_PER_MINUTE,
    /** Conservative bitrate floor used to reserve an upper bound before duration is known. */
    val transcriptionReservationBitrateBps: Int = DEFAULT_TRANSCRIPTION_BITRATE_BPS
) {
    init {
        require(monthlyCapUsd.isFinite()) { "monthlyCapUsd must be finite" }
        require(transcriptionUsdPerMinute.isFinite() && transcriptionUsdPerMinute >= 0.0) {
            "transcriptionUsdPerMinute must be finite and non-negative"
        }
        require(transcriptionReservationBitrateBps > 0) {
            "transcriptionReservationBitrateBps must be positive"
        }
    }

    /** True when a finite cap is configured and should be enforced. */
    val capEnabled: Boolean get() = monthlyCapUsd > 0.0

    companion object {
        /** Default per-workspace monthly cap (USD) when unconfigured. */
        const val DEFAULT_MONTHLY_CAP_USD: Double = 50.0
        const val DEFAULT_TRANSCRIPTION_USD_PER_MINUTE: Double = 0.006
        const val DEFAULT_TRANSCRIPTION_BITRATE_BPS: Int = 4_000

        fun fromEnv(env: EnvConfig = EnvConfig.system()): CostConfig {
            val raw = env.string(
                "POTATY_WORKSPACE_MONTHLY_COST_CAP_USD",
                DEFAULT_MONTHLY_CAP_USD.toString()
            )
            val cap =
                raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: DEFAULT_MONTHLY_CAP_USD
            val transcriptionRate =
                env.string(
                    "POTATY_TRANSCRIPTION_USD_PER_MINUTE",
                    DEFAULT_TRANSCRIPTION_USD_PER_MINUTE.toString()
                ).toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: DEFAULT_TRANSCRIPTION_USD_PER_MINUTE
            val bitrate =
                env.int(
                    "POTATY_TRANSCRIPTION_RESERVATION_BITRATE_BPS",
                    DEFAULT_TRANSCRIPTION_BITRATE_BPS
                ).takeIf { it > 0 }
                    ?: DEFAULT_TRANSCRIPTION_BITRATE_BPS
            return CostConfig(
                monthlyCapUsd = cap,
                transcriptionUsdPerMinute = transcriptionRate,
                transcriptionReservationBitrateBps = bitrate
            )
        }
    }
}
