/*
 * Copyright (c) 2026, Potaty
 *
 * Pure job-status helpers (no I/O), shared by the controller and unit-testable on their own.
 */

package com.potaty.workbench

object JobPoller {
    private val TERMINAL = setOf("succeeded", "failed", "cancelled", "needs_input")

    fun isTerminal(status: String): Boolean = status.lowercase() in TERMINAL

    fun isSuccess(status: String): Boolean = status.lowercase() == "succeeded"
}
