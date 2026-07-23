/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

@file:Suppress("FunctionName")

package com.potaty.html.toolbar.view.shapetool2

import androidx.compose.runtime.Composable
import com.potaty.actionmanager.OneTimeActionType
import com.potaty.ui.compose.ext.classes
import org.jetbrains.compose.web.dom.Div

@Composable
internal fun ShapeToolContentView(
    viewModel: ShapeToolViewModel,
    setOneTimeAction: (OneTimeActionType) -> Unit
) {
    Div(
        attrs = { classes("h-full", "flex", "flex-col") }
    ) {
        Div(
            attrs = { classes("flex-1", "overflow-y-auto", "flex", "flex-col") }
        ) {
            ReorderSectionView(
                isVisible = viewModel.reorderToolVisibilityState.value,
                setOneTimeAction
            )
            TransformationToolView(
                viewModel.singleShapeBoundState.value,
                viewModel.singleShapeResizeableState.value,
                setOneTimeAction
            )
            AppearanceToolView(
                viewModel,
                setOneTimeAction
            )
            TextToolView(
                viewModel.textAlignState.value,
                setOneTimeAction
            )
            IndicatorView(isVisible = !viewModel.hasAnyToolState.value)
        }
        FooterView()
    }
}
