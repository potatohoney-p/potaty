/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ui.theme

import com.potaty.livedata.LiveData
import com.potaty.livedata.MutableLiveData
import com.potaty.livedata.distinctUntilChange

/**
 * A class for managing the theme of the app.
 */
class ThemeManager private constructor(initialThemeMode: ThemeMode) {
    private val themeModeMutableLiveData: MutableLiveData<ThemeMode> =
        MutableLiveData(initialThemeMode)

    val themeModeLiveData: LiveData<ThemeMode> = themeModeMutableLiveData.distinctUntilChange()

    /**
     * Gets color from [color] based on the current theme.
     * The return is the hex code of RGB or RGBA, the same to the hex code used in CSS.
     */
    fun getColorCode(color: ThemeColor): String = when (themeModeLiveData.value) {
        ThemeMode.LIGHT -> color.lightColorCode
        ThemeMode.DARK -> color.darkColorCode
    }

    fun setTheme(themeMode: ThemeMode) {
        themeModeMutableLiveData.value = themeMode
    }

    companion object {
        private var instance: ThemeManager? = null

        fun getInstance(): ThemeManager {
            val nonNullInstance = instance ?: ThemeManager(ThemeMode.DARK)
            instance = nonNullInstance
            return nonNullInstance
        }
    }
}
