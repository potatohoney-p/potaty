/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.ui.appstate

import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.livedata.MutableLiveData
import com.potaty.livedata.distinctUntilChange
import com.potaty.store.manager.StorageDocument
import com.potaty.store.manager.StoreKeys
import com.potaty.ui.appstate.state.ScrollMode
import com.potaty.ui.theme.ThemeManager
import org.w3c.dom.Element

/**
 * A class for managing the overall UI state of the app, including theme, visibility of a main
 * component, etc.
 */
class AppUiStateManager(
    private val appLifecycleOwner: LifecycleOwner,
    private val settingDocument: StorageDocument = StorageDocument.get(StoreKeys.SETTINGS),
    themeManager: ThemeManager = ThemeManager.getInstance()
) {
    private val appThemeManager = AppThemeManager(themeManager)

    private val shapeToolVisibilityMutableLiveData = MutableLiveData(true)
    val shapeToolVisibilityLiveData = shapeToolVisibilityMutableLiveData.distinctUntilChange()

    private val scrollModeMutableLiveData = MutableLiveData(ScrollMode.BOTH)
    val scrollModeLiveData: LiveData<ScrollMode> = scrollModeMutableLiveData.distinctUntilChange()

    private val fontSizeMutableLiveData =
        MutableLiveData(settingDocument.get(StoreKeys.FONT_SIZE, "13")!!.toInt())
    val fontSizeLiveData: LiveData<Int> = fontSizeMutableLiveData

    fun observeTheme(
        documentElement: Element,
        forceUiUpdate: () -> Unit
    ) {
        appThemeManager.observeTheme(appLifecycleOwner, documentElement, forceUiUpdate)
    }

    fun updateUiState(payload: UiStatePayload) {
        when (payload) {
            is UiStatePayload.ShapeToolVisibility ->
                shapeToolVisibilityMutableLiveData.value = payload.isVisible

            is UiStatePayload.ChangeScrollMode ->
                scrollModeMutableLiveData.value = payload.scrollMode

            is UiStatePayload.ChangeFontSize -> {
                val offset = if (payload.isIncreased) 2 else -2
                fontSizeMutableLiveData.value = (fontSizeLiveData.value + offset).coerceIn(13, 25)

                settingDocument.set(StoreKeys.FONT_SIZE, fontSizeLiveData.value.toString())
            }
        }
    }

    /**
     * An interface for containing the payload of updating UI State.
     */
    sealed interface UiStatePayload {
        class ShapeToolVisibility(val isVisible: Boolean) : UiStatePayload

        class ChangeScrollMode(val scrollMode: ScrollMode) : UiStatePayload

        class ChangeFontSize(val isIncreased: Boolean) : UiStatePayload
    }
}
