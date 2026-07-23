/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.app

import com.potaty.actionmanager.ActionManager
import com.potaty.actionmanager.OneTimeActionType
import com.potaty.app.generate.GenerateController
import com.potaty.bitmap.manager.PotatyBitmapManager
import com.potaty.browser.manager.BrowserManager
import com.potaty.graphics.board.PotatyBoard
import com.potaty.graphics.geo.Size
import com.potaty.html.canvas.CanvasViewController
import com.potaty.html.toolbar.view.NavBarViewController
import com.potaty.html.toolbar.view.ShapeToolViewController2
import com.potaty.ir.DiagramIR
import com.potaty.keycommand.KeyCommand
import com.potaty.keycommand.KeyCommandController
import com.potaty.layout.LayoutEngineFactory
import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.map
import com.potaty.render.IrShapeMapper
import com.potaty.render.StyleProfile
import com.potaty.shape.ShapeManager
import com.potaty.shape.add
import com.potaty.shape.clipboard.ShapeClipboardManager
import com.potaty.shape.selection.SelectedShapeManager
import com.potaty.state.MainStateManager
import com.potaty.ui.appstate.AppUiStateManager
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.get

/**
 * Main class of the app to handle all kinds of events, UI, actions, etc.
 */
class PotatyApplication : LifecycleOwner() {
    private val model = PotatyAppModel()

    private val mainBoard = PotatyBoard()
    private val shapeManager = ShapeManager()
    private val selectedShapeManager = SelectedShapeManager()
    private val bitmapManager = PotatyBitmapManager()

    // Init AppUiStateManager here to apply theme as soon as possible.
    private val appUiStateManager = AppUiStateManager(this)

    private var mainStateManager: MainStateManager? = null

    /**
     * The entry point for all actions. This is called after window is loaded (`window.onload`)
     */
    override fun onStartInternal() {
        val body = document.body ?: return

        val boardCanvasContainer =
            document.getElementById(CONTAINER_ID) as? HTMLDivElement ?: return
        val axisCanvasContainer =
            document.getElementById(AXIS_CONTAINER_ID) as? HTMLDivElement ?: return

        val keyCommandController = KeyCommandController(body)

        val canvasViewController = CanvasViewController(
            this,
            boardCanvasContainer,
            axisCanvasContainer,
            mainBoard,
            model.windowSizeLiveData,
            keyCommandController.keyCommandLiveData.map { it == KeyCommand.SHIFT_KEY },
            appUiStateManager.scrollModeLiveData
        )

        val actionManager = ActionManager(this, keyCommandController.keyCommandLiveData)
        actionManager.installDebugCommand()

        val browserManager = BrowserManager {
            actionManager.setOneTimeAction(OneTimeActionType.ProjectAction.SwitchProject(it))
        }

        val mainStateManager = MainStateManager(
            this,
            mainBoard,
            shapeManager,
            selectedShapeManager,
            bitmapManager,
            canvasViewController,
            ShapeClipboardManager(body),
            canvasViewController.mousePointerLiveData,
            model.applicationActiveStateLiveData,
            actionManager,
            appUiStateManager,
            initialRootId = browserManager.rootIdFromUrl
        )
        this.mainStateManager = mainStateManager

        NavBarViewController(
            this,
            model.applicationActiveStateLiveData,
            shapeManager.rootLiveData.map { it.id },
            appUiStateManager,
            actionManager
        )
        ShapeToolViewController2(
            this,
            document.getElementById("shape-tools") as HTMLElement,
            actionManager,
            selectedShapeManager.selectedShapesLiveData,
            shapeManager.versionLiveData,
            appUiStateManager.shapeToolVisibilityLiveData
        )
        onResize()
        observeAppActivateState()

        appUiStateManager.observeTheme(
            document.documentElement!!,
            mainStateManager::forceFullyRedrawWorkspace
        )

        browserManager.startObserveStateChange(shapeManager.rootLiveData.map { it.id }, this)
        appUiStateManager.fontSizeLiveData.observe(this, listener = canvasViewController::setFont)

        // Source-grounded Studio: prompt / transcript / GitHub -> evidence-linked ASCII.
        GenerateController(::insertGeneratedDiagram).mount()
    }

    /** Converts the approved IR into the editor's native shapes so every element stays editable. */
    private fun insertGeneratedDiagram(ir: DiagramIR) {
        val layout = LayoutEngineFactory.forIr(ir).layout(ir)
        val profile = StyleProfile.byId(ir.styleHints.styleProfile)
        IrShapeMapper.toShapes(layout, profile).forEach(shapeManager::add)
        mainStateManager?.forceFullyRedrawWorkspace()
    }

    fun onResize() {
        val body = document.body ?: return
        val newSize = Size(body.clientWidth, body.clientHeight)
        model.setWindowSize(newSize)
    }

    private fun observeAppActivateState() {
        val callback = object : EventListener {
            override fun handleEvent(event: Event) {
                val isAppActive = document["visibilityState"] == "visible"
                model.setApplicationActiveState(isAppActive)
            }
        }
        document.addEventListener("visibilitychange", callback)
        window.onfocus = {
            model.setApplicationActiveState(true)
        }
        window.onblur = {
            model.setApplicationActiveState(false)
        }
    }

    companion object {
        private const val CONTAINER_ID = "potaty-canvas-container"
        private const val AXIS_CONTAINER_ID = "potaty-axis-container"
    }
}
