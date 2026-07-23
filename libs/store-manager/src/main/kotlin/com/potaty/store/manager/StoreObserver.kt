/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.store.manager

/**
 * An interface for observing storage change.
 */
fun interface StoreObserver {
    fun onChange(key: String, oldValue: String?, newValue: String?)
}
