/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.browser.manager

import com.potaty.lifecycle.LifecycleOwner
import com.potaty.livedata.LiveData
import com.potaty.livedata.distinctUntilChange
import com.potaty.store.dao.workspace.WorkspaceDao
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/**
 * A class for managing the states related to the browser such as the title, address bar, etc.
 */
class BrowserManager(
    private val onUrlUpdate: (String) -> Unit
) {
    private val urlSearchParams: URLSearchParams
        get() = URLSearchParams(window.location.search)

    val rootIdFromUrl: String
        get() {
            // Replace space char with + since '+' will be converted to ' ' when reading value from
            // URLSearchParams.
            // The space character is not allowed in the URL.
            return urlSearchParams.get(URL_PARAM_ID).orEmpty().replace(' ', '+')
        }

    private var willChangedByUrlPopStateEvent = false

    init {
        onUrlUpdate(rootIdFromUrl)
        window.onpopstate = {
            willChangedByUrlPopStateEvent = true
            onUrlUpdate(rootIdFromUrl)
        }
    }

    fun startObserveStateChange(
        workingProjectIdLiveData: LiveData<String>,
        lifecycleOwner: LifecycleOwner,
        workspaceDao: WorkspaceDao = WorkspaceDao.instance
    ) {
        workingProjectIdLiveData.observe(lifecycleOwner) {
            val workspaceName = workspaceDao.getObject(it).name
                .trim()
                .takeUnless { name ->
                    name.isBlank() || name.equals("undefined", ignoreCase = true) ||
                        name.equals("null", ignoreCase = true)
                }
                ?: "Untitled"
            document.title = "$workspaceName - Potaty"
        }

        workingProjectIdLiveData.distinctUntilChange().observe(lifecycleOwner) {
            if (it == rootIdFromUrl || willChangedByUrlPopStateEvent) {
                willChangedByUrlPopStateEvent = false
                return@observe
            }
            val searchParams = urlSearchParams
            searchParams.set(URL_PARAM_ID, it)
            val newUrl = "${window.location.origin}${window.location.pathname}?$searchParams"
            window.history.pushState(mapOf("path" to newUrl).asDynamic(), "", newUrl)
        }
    }

    companion object {
        private const val URL_PARAM_ID = "id"

        fun openInNewTab(projectId: String) {
            window.open("?$URL_PARAM_ID=$projectId")
        }
    }
}
