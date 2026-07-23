/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state.onetimeaction

import com.potaty.actionmanager.OneTimeActionType
import com.potaty.bitmap.manager.PotatyBitmapManager
import com.potaty.export.ExportShapesHelper
import com.potaty.html.modal.compose.showExitingProjectDialog
import com.potaty.shape.clipboard.ShapeClipboardManager
import com.potaty.shape.connector.ShapeConnector
import com.potaty.shape.serialization.Extra
import com.potaty.shape.serialization.PotatyFile
import com.potaty.shape.serialization.ShapeSerializationUtil
import com.potaty.shape.shape.RootGroup
import com.potaty.state.FileMediator
import com.potaty.state.command.CommandEnvironment
import com.potaty.store.dao.workspace.WorkspaceDao
import com.potaty.store.dao.workspace.WorkspaceObjectDao

/**
 * A helper class to handle file-related one-time actions in the application.
 * This class provides methods such as creating new projects, saving and loading shapes to/from
 * files, exporting selected shapes to text format, etc.
 */
internal class FileRelatedActionsHelper(
    private val environment: CommandEnvironment,
    bitmapManager: PotatyBitmapManager,
    shapeClipboardManager: ShapeClipboardManager,
    private val workspaceDao: WorkspaceDao = WorkspaceDao.instance
) {
    private val fileMediator: FileMediator = FileMediator()
    private val exportShapesHelper = ExportShapesHelper(
        bitmapManager::getBitmap,
        shapeClipboardManager::setClipboardText
    )

    fun handleProjectAction(projectAction: OneTimeActionType.ProjectAction) {
        when (projectAction) {
            OneTimeActionType.ProjectAction.NewProject ->
                newProject()

            is OneTimeActionType.ProjectAction.SwitchProject ->
                switchProject(projectAction.projectId)

            is OneTimeActionType.ProjectAction.RemoveProject ->
                removeProject(projectAction.projectId)

            is OneTimeActionType.ProjectAction.RenameCurrentProject ->
                renameProject(projectAction.newName)

            OneTimeActionType.ProjectAction.SaveShapesAs ->
                saveCurrentShapesToFile()

            OneTimeActionType.ProjectAction.OpenShapes ->
                loadShapesFromFile()

            OneTimeActionType.ProjectAction.ExportSelectedShapes ->
                exportSelectedShapes(true)
        }
    }

    private fun newProject() {
        replaceWorkspace(RootGroup(null)) // passing null to let the ID generated automatically
    }

    private fun switchProject(projectId: String) {
        val serializableRoot = workspaceDao.getObject(projectId).rootGroup ?: return
        replaceWorkspace(RootGroup(serializableRoot))
    }

    private fun removeProject(projectId: String) {
        val currentProjectId = environment.shapeManager.root.id
        workspaceDao.getObject(projectId).removeSelf()
        if (projectId != currentProjectId) {
            return
        }
        // Next active project selection when the current active project is removed.
        val nextProject =
            workspaceDao.getObjects().filter { it.objectId != currentProjectId }.firstOrNull()
        if (nextProject == null) {
            newProject()
        } else {
            switchProject(nextProject.objectId)
        }
    }

    private fun renameProject(newName: String) {
        val currentRootId = environment.shapeManager.root.id
        workspaceDao.getObject(currentRootId).name = newName
        environment.shapeManager.notifyProjectUpdate()
    }

    private fun saveCurrentShapesToFile() {
        val currentRoot = environment.shapeManager.root
        val objectDao = workspaceDao.getObject(currentRoot.id)
        val name = objectDao.name
        val offset = objectDao.offset
        val jsonString = ShapeSerializationUtil.toPotatyFileJson(
            name = name,
            serializableShape = currentRoot.toSerializableShape(true),
            connectors = environment.shapeManager.shapeConnector.toSerializable(),
            offset = offset
        )
        fileMediator.saveFile(name, jsonString)
    }

    private fun loadShapesFromFile() {
        fileMediator.openFile { jsonString ->
            val potatyFile = ShapeSerializationUtil.fromPotatyFileJson(jsonString)
            if (potatyFile == null) {
                console.warn("Failed to load shapes from file.")
                // TODO: Show error dialog
                return@openFile
            }
            applyPotatyFileToWorkspace(potatyFile)
        }
    }

    private fun applyPotatyFileToWorkspace(potatyFile: PotatyFile) {
        val rootGroup = RootGroup(potatyFile.root)
        val existingProject = workspaceDao.getObject(rootGroup.id)
        if (existingProject.rootGroup != null) {
            showExitingProjectDialog(
                existingProject.name,
                existingProject.lastModifiedTimestampMillis,
                onKeepBothClick = {
                    prepareAndApplyNewRoot(
                        RootGroup(potatyFile.root.copy(isIdTemporary = true)),
                        potatyFile.extra
                    )
                },
                onReplaceClick = {
                    prepareAndApplyNewRoot(rootGroup, potatyFile.extra)
                }
            )
            return
        }

        prepareAndApplyNewRoot(rootGroup, potatyFile.extra)
    }

    private fun prepareAndApplyNewRoot(rootGroup: RootGroup, extra: Extra) {
        // Prepare the object to be replaced since the data on the UI rely on the current root
        // id to know an update.
        // - Set name to the storage
        // - Set offset to the storage
        workspaceDao.getObject(rootGroup.id).run {
            name = extra.name.takeIf { it.isNotEmpty() } ?: WorkspaceObjectDao.DEFAULT_NAME
            offset = extra.offset
        }

        replaceWorkspace(rootGroup)
    }

    fun exportSelectedShapes(isModalRequired: Boolean) {
        val selectedShapes = environment.getSelectedShapes()
        val extractableShapes = when {
            selectedShapes.isNotEmpty() ->
                environment.workingParentGroup.items.filter { it in selectedShapes }

            isModalRequired ->
                listOf(environment.workingParentGroup)

            else ->
                emptyList()
        }

        exportShapesHelper.exportText(extractableShapes, isModalRequired)
    }

    private fun replaceWorkspace(rootGroup: RootGroup) {
        // TODO: load from storage
        val shapeConnector = ShapeConnector()
        environment.replaceRoot(rootGroup, shapeConnector)
    }
}
