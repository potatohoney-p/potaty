/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.toolbar.view.shapetool2

import androidx.compose.runtime.State
import com.potaty.actionmanager.ActionManager
import com.potaty.actionmanager.RetainableActionType
import com.potaty.graphics.geo.Rect
import com.potaty.html.toolbar.view.shapetool2.viewdata.LineAppearanceDataController
import com.potaty.html.toolbar.view.shapetool2.viewdata.RectangleAppearanceDataController
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.livedata.combineLiveData
import com.potaty.livedata.map
import com.potaty.shape.ShapeExtraManager
import com.potaty.shape.extra.style.StraightStrokeDashPattern
import com.potaty.shape.extra.style.TextAlign
import com.potaty.shape.shape.AbstractShape
import com.potaty.shape.shape.Rectangle
import com.potaty.shape.shape.Text
import com.potaty.ui.compose.ext.toState

internal class ShapeToolViewModel(
    lifecycleOwner: LifecycleOwner,
    selectedShapesLiveData: LiveData<Set<AbstractShape>>,
    shapeManagerVersionLiveData: LiveData<Int>,
    actionManager: ActionManager
) {
    private val shapesLiveData: LiveData<Set<AbstractShape>> =
        combineLiveData(
            selectedShapesLiveData,
            shapeManagerVersionLiveData
        ) { selected, _ -> selected }

    private val retainableActionTypeLiveData: LiveData<RetainableActionType> =
        combineLiveData(
            actionManager.retainableActionLiveData,
            ShapeExtraManager.defaultExtraStateUpdateLiveData
        ) { action, _ -> action }

    private val rectangleAppearanceDataController = RectangleAppearanceDataController(
        shapesLiveData,
        retainableActionTypeLiveData
    )

    private val lineAppearanceDataController = LineAppearanceDataController(
        shapesLiveData,
        retainableActionTypeLiveData
    )

    private val singleShapeLiveData: LiveData<AbstractShape?> = combineLiveData(
        selectedShapesLiveData,
        shapeManagerVersionLiveData
    ) { selected, _ -> selected.singleOrNull() }

    private val retainableActionLiveData: LiveData<RetainableActionType> = combineLiveData(
        actionManager.retainableActionLiveData,
        ShapeExtraManager.defaultExtraStateUpdateLiveData
    ) { action, _ -> action }

    private val reorderToolVisibilityLiveData: LiveData<Boolean> = singleShapeLiveData.map {
        it != null
    }
    val reorderToolVisibilityState: State<Boolean> =
        reorderToolVisibilityLiveData.toState(lifecycleOwner)

    val singleShapeBoundState: State<Rect?> =
        singleShapeLiveData.map { it?.bound }.toState(lifecycleOwner)

    val singleShapeResizeableState: State<Boolean> =
        singleShapeLiveData.map { it is Rectangle || it is Text }.toState(lifecycleOwner)

    val shapeFillTypeState: State<CloudItemSelectionState?> =
        rectangleAppearanceDataController.fillToolStateLiveData.toState(lifecycleOwner)

    val shapeBorderTypeState: State<CloudItemSelectionState?> =
        rectangleAppearanceDataController.borderToolStateLiveData.toState(lifecycleOwner)

    val shapeBorderDashTypeState: State<StraightStrokeDashPattern?> =
        rectangleAppearanceDataController.borderDashPatternLiveData.toState(lifecycleOwner)

    val shapeBorderRoundedCornerState: State<Boolean?> =
        rectangleAppearanceDataController.borderRoundedCornerLiveData.toState(lifecycleOwner)

    val lineStrokeTypeState: State<CloudItemSelectionState?> =
        lineAppearanceDataController.strokeToolStateLiveData.toState(lifecycleOwner)

    val lineStrokeDashTypeState: State<StraightStrokeDashPattern?> =
        lineAppearanceDataController.strokeDashPatternLiveData.toState(lifecycleOwner)

    val lineStrokeRoundedCornerState: State<Boolean?> =
        lineAppearanceDataController.strokeRoundedCornerLiveData.toState(lifecycleOwner)

    val lineStartHeadState: State<CloudItemSelectionState?> =
        lineAppearanceDataController.startHeadToolStateLiveData.toState(lifecycleOwner)

    val lineEndHeadState: State<CloudItemSelectionState?> =
        lineAppearanceDataController.endHeadToolStateLiveData.toState(lifecycleOwner)

    val appearanceVisibilityState: State<Boolean> = combineLiveData(
        rectangleAppearanceDataController.hasAnyVisibleToolLiveData,
        lineAppearanceDataController.hasAnyVisibleTollLiveData
    ) { isRectAvailable, isLineAvailable -> isRectAvailable || isLineAvailable }
        .toState(lifecycleOwner)

    private val textAlignLiveData: LiveData<TextAlign?> =
        createTextAlignLiveData(singleShapeLiveData, retainableActionLiveData)
    val textAlignState: State<TextAlign?> = textAlignLiveData.toState(lifecycleOwner)

    val hasAnyToolState: State<Boolean> = combineLiveData(
        singleShapeLiveData.map { it != null },
        rectangleAppearanceDataController.hasAnyVisibleToolLiveData,
        lineAppearanceDataController.hasAnyVisibleTollLiveData,
        textAlignLiveData.map { it != null }
    ) { states -> states.any { it == true } }
        .toState(lifecycleOwner)

    val fillOptions: List<AppearanceOptionItem> =
        ShapeExtraManager.getAllPredefinedRectangleFillStyles()
            .map { AppearanceOptionItem(it.id, it.displayName) }

    val strokeOptions: List<AppearanceOptionItem> =
        ShapeExtraManager.getAllPredefinedStrokeStyles()
            .map { AppearanceOptionItem(it.id, it.displayName) }

    val headOptions: List<AppearanceOptionItem> =
        ShapeExtraManager.getAllPredefinedAnchorChars()
            .map { AppearanceOptionItem(it.id, it.displayName) }

    private fun createTextAlignLiveData(
        selectedShapeLiveData: LiveData<AbstractShape?>,
        retainableActionTypeLiveData: LiveData<RetainableActionType>
    ): LiveData<TextAlign?> {
        val selectedTextAlignLiveData: LiveData<TextAlign?> = selectedShapeLiveData.map {
            val text = it as? Text
            val editableText = text?.takeIf(Text::isTextEditable)
            editableText?.extra?.textAlign
        }
        val defaultTextAlignLiveData: LiveData<TextAlign?> = retainableActionTypeLiveData.map {
            if (it == RetainableActionType.ADD_TEXT) {
                ShapeExtraManager.defaultTextAlign
            } else {
                null
            }
        }
        return combineLiveData(
            selectedTextAlignLiveData,
            defaultTextAlignLiveData
        ) { selected, default -> selected ?: default }
    }
}

internal data class AppearanceOptionItem(val id: String, val name: String)

internal data class CloudItemSelectionState(val isChecked: Boolean, val selectedId: String?)
