/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ui.compose.ext

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData

fun <T> LiveData<T>.toState(lifecycleOwner: LifecycleOwner): State<T> {
    val mutableState = mutableStateOf(value)
    observe(lifecycleOwner) {
        mutableState.value = it
    }
    return mutableState
}
