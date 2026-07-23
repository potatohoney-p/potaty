/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ui.appstate

import com.potaty.lifecycle.LifecycleOwner
import com.potaty.store.manager.StorageDocument
import com.potaty.store.manager.StoreKeys
import com.potaty.ui.theme.ThemeManager
import com.potaty.ui.theme.ThemeMode
import org.w3c.dom.Element

/**
 * A class for managing theme
 */
internal class AppThemeManager(
    private val themeManager: ThemeManager,
    private val settingsDocument: StorageDocument = StorageDocument.get(StoreKeys.SETTINGS)
) {

    init {
        val themeMode = settingsDocument.get(StoreKeys.THEME_MODE)
            ?.let(ThemeMode::valueOf)
            ?: ThemeMode.DARK
        val themeManager = ThemeManager.getInstance()
        themeManager.setTheme(themeMode)
    }

    fun observeTheme(
        appLifecycleOwner: LifecycleOwner,
        documentElement: Element,
        forceUiUpdate: () -> Unit
    ) {
        themeManager.themeModeLiveData.observe(appLifecycleOwner) {
            documentElement.className = when (it) {
                ThemeMode.LIGHT -> THEME_LIGHT
                ThemeMode.DARK -> THEME_DARK
            }
            forceUiUpdate()
            settingsDocument.set(StoreKeys.THEME_MODE, it.name)
        }

        settingsDocument.setObserver(StoreKeys.THEME_MODE) { _, _, newValue ->
            val themeMode = newValue?.let(ThemeMode::valueOf) ?: ThemeMode.DARK
            themeManager.setTheme(themeMode)
        }
    }

    companion object {
        private const val THEME_LIGHT = "light"
        private const val THEME_DARK = "dark"
    }
}
