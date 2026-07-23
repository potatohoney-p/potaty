/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.lifecycle

interface LifecycleObserver {
    fun onStart() = Unit

    fun onStop() = Unit
}
