/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.app

import com.potaty.graphics.geo.Size
import com.potaty.livedata.LiveData
import com.potaty.livedata.MutableLiveData

/**
 * A model class for the app-wide states.
 */
class PotatyAppModel {
    private val windowSizeMutableLiveData: MutableLiveData<Size> =
        MutableLiveData(Size(0, 0))
    val windowSizeLiveData: LiveData<Size> = windowSizeMutableLiveData

    private val applicationActiveStateMutableLiveData: MutableLiveData<Boolean> =
        MutableLiveData(false)
    val applicationActiveStateLiveData: LiveData<Boolean> = applicationActiveStateMutableLiveData

    fun setWindowSize(size: Size) {
        windowSizeMutableLiveData.value = size
    }

    fun setApplicationActiveState(isActive: Boolean) {
        applicationActiveStateMutableLiveData.value = isActive
    }
}
