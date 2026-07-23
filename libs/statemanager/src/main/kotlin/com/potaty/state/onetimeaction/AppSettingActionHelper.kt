/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.onetimeaction

import com.potaty.actionmanager.OneTimeActionType
import com.potaty.html.toolbar.view.keyboardshortcut.KeyboardShortcuts
import com.potaty.ui.appstate.AppUiStateManager
import com.potaty.ui.appstate.AppUiStateManager.UiStatePayload

internal class AppSettingActionHelper(
    private val uiStateManager: AppUiStateManager
) {
    fun handleAppSettingAction(action: OneTimeActionType.AppSettingAction) {
        when (action) {
            OneTimeActionType.AppSettingAction.ShowFormatPanel ->
                uiStateManager.updateUiState(UiStatePayload.ShapeToolVisibility(true))

            OneTimeActionType.AppSettingAction.HideFormatPanel ->
                uiStateManager.updateUiState(UiStatePayload.ShapeToolVisibility(false))

            OneTimeActionType.AppSettingAction.ShowKeyboardShortcuts ->
                KeyboardShortcuts.showHint()

            is OneTimeActionType.AppSettingAction.ChangeFontSize ->
                uiStateManager.updateUiState(UiStatePayload.ChangeFontSize(action.isIncreased))
        }
    }
}
