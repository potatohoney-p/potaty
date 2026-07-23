/*
 * Copyright (c) 2026, Potaty
 *
 * Coroutine-friendly transaction helper. Wraps Exposed's blocking transaction on the IO
 * dispatcher so suspend repositories can call it without blocking the event loop.
 */

package com.potaty.backend.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction

class TransactionContext(private val db: Database) {

    /** Runs [block] inside an Exposed transaction on the IO dispatcher. */
    suspend fun <T> tx(block: () -> T): T = withContext(Dispatchers.IO) {
        transaction(db) { block() }
    }
}
