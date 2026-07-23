/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.store.dao.workspace

import com.potaty.store.manager.StorageDocument
import com.potaty.store.manager.StoreKeys.LAST_OPEN
import com.potaty.store.manager.StoreKeys.OBJECT_CONTENT
import com.potaty.store.manager.StoreKeys.PATH_SEPARATOR
import com.potaty.store.manager.StoreKeys.WORKSPACE

/**
 * A dao for workspace
 */
class WorkspaceDao private constructor(
    private val workspaceDocument: StorageDocument
) {
    var lastOpenedObjectId: String?
        get() = workspaceDocument.get(LAST_OPEN)
        set(value) {
            if (value != null) {
                workspaceDocument.set(LAST_OPEN, value)
            }
        }

    private val objectDaos: MutableMap<String, WorkspaceObjectDao> = mutableMapOf()

    fun getObject(objectId: String): WorkspaceObjectDao = objectDaos.getOrPut(objectId) {
        WorkspaceObjectDao(objectId, workspaceDocument)
    }

    fun removeObject(objectId: String) {
        getObject(objectId).removeSelf()
        // TODO:
        //  If the object is currently open, choose a latest opened object and make it active.
        //  If no object left, create a blank workspace.
        objectDaos.remove(objectId)
    }

    /**
     * Gets list of all objects in the storage, ordered by last opened time desc.
     */
    fun getObjects(): Sequence<WorkspaceObjectDao> =
        workspaceDocument.getKeys {
            it.startsWith(WORKSPACE + PATH_SEPARATOR) &&
                it.endsWith(PATH_SEPARATOR + OBJECT_CONTENT)
        }
            .map { getObject(objectId = it.split(PATH_SEPARATOR)[1]) }
            .sortedByDescending { it.lastOpened }

    companion object {
        val instance: WorkspaceDao by lazy { WorkspaceDao(StorageDocument.get(WORKSPACE)) }
    }
}
