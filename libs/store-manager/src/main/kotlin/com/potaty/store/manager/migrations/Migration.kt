/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.store.manager.migrations

import com.potaty.store.manager.StoreManager

/**
 * An interface for migrating local storage into [targetVersion]
 */
internal sealed class Migration(val targetVersion: Int) {
    /**
     * Migrates storage to [targetVersion].
     *
     * Note: Inside [migrate], only use [storageManager] for get/set values
     */
    abstract fun migrate(storageManager: StoreManager)
}
