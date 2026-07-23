/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.html.toolbar.view

import com.potaty.actionmanager.ActionManager
import com.potaty.html.toolbar.view.shapetool2.ShapeToolContentView
import com.potaty.html.toolbar.view.shapetool2.ShapeToolViewModel
import com.potaty.html.toolbar.view.utils.CssClass
import com.potaty.html.toolbar.view.utils.bindClass
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.shape.shape.AbstractShape
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

class ShapeToolViewController2(
    lifecycleOwner: LifecycleOwner,
    container: HTMLElement,
    actionManager: ActionManager,
    selectedShapesLiveData: LiveData<Set<AbstractShape>>,
    shapeManagerVersionLiveData: LiveData<Int>,
    shapeToolVisibilityLiveData: LiveData<Boolean>
) {
    private val viewModel = ShapeToolViewModel(
        lifecycleOwner,
        selectedShapesLiveData,
        shapeManagerVersionLiveData,
        actionManager
    )

    init {
        shapeToolVisibilityLiveData.observe(lifecycleOwner) {
            container.bindClass(CssClass.HIDE, !it)
        }
        renderComposable(container) {
            ShapeToolContentView(viewModel, actionManager::setOneTimeAction)
        }
    }
}
