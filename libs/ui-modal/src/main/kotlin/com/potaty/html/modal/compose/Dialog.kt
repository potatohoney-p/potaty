/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

@file:Suppress("FunctionName")

package com.potaty.html.modal.compose

import androidx.compose.runtime.Composable
import com.potaty.ui.compose.ext.classes
import com.potaty.ui.compose.ext.onConsumeClick
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.Element
import com.potaty.html.Div as PotatyDiv

class DialogAction(val name: String, val isDanger: Boolean = false, val action: () -> Unit)

/**
 * Creates a dialog modal looks like
 * ```
 * ┌─────────────────────────────────────────────────┐
 * │ Title                                           │
 * │                                                 │
 * │ Message                                         │
 * │                      ┌───────────┐ ┌───────────┐│
 * │                      │ Secondary │ │  Primary  ││
 * │                      └───────────┘ └───────────┘│
 * └─────────────────────────────────────────────────┘
 * ```
 */
fun Dialog(
    title: String = "",
    message: String = "",
    primaryAction: DialogAction? = null,
    secondaryAction: DialogAction? = null
) {
    check(title.isNotBlank() || message.isNotBlank()) {
        "Either title or message must not be blank"
    }
    Dialog(
        title,
        { Div(attrs = { classes("dialog-text-content") }) { Text(message) } },
        primaryAction,
        secondaryAction
    )
}

/**
 * Creates a dialog modal looks like
 * ```
 * ┌─────────────────────────────────────────────────┐
 * │ Title                                           │
 * │                                                 │
 * │ Content                                         │
 * │                      ┌───────────┐ ┌───────────┐│
 * │                      │ Secondary │ │  Primary  ││
 * │                      └───────────┘ └───────────┘│
 * └─────────────────────────────────────────────────┘
 * ```
 */
fun Dialog(
    title: String,
    content: ContentBuilder<Element>,
    primaryAction: DialogAction? = null,
    secondaryAction: DialogAction? = null
) {
    val body = document.body ?: return

    val container = body.PotatyDiv {}
    val composition = renderComposable(container) {
    }

    val dismiss = {
        composition.dispose()
        container.remove()
    }

    composition.setContent {
        Div(
            attrs = {
                classes("dialog-bg")
                onConsumeClick { dismiss() }
            }
        ) {
            DialogContainer(title, content, primaryAction, secondaryAction, dismiss)
        }
    }
}

@Composable
private fun DialogContainer(
    title: String,
    content: ContentBuilder<Element>,
    primaryAction: DialogAction? = null,
    secondaryAction: DialogAction? = null,
    dismiss: () -> Unit
) {
    Div(
        attrs = {
            classes("dialog-container")
            onConsumeClick {
                // Do nothing, just block the dismiss event
            }
        }
    ) {
        Title(title)
        Div(
            attrs = {
                classes("dialog-content", "no-title" to title.isBlank())
            }
        ) { content() }

        if (primaryAction != null || secondaryAction != null) {
            Div(
                attrs = { classes("dialog-actions") }
            ) {
                ActionButton(secondaryAction, "secondary", dismiss)
                ActionButton(primaryAction, "primary", dismiss)
            }
        }
    }
}

@Composable
private fun Title(title: String) {
    if (title.isBlank()) {
        return
    }
    H2(attrs = { classes("dialog-title") }) {
        Text(title)
    }
}

@Composable
private fun ActionButton(action: DialogAction?, className: String, dismiss: () -> Unit) {
    if (action == null) {
        return
    }
    Div(
        attrs = {
            classes("dialog-action", className, "danger" to action.isDanger)
            onClick {
                action.action()
                dismiss()
            }
        }
    ) {
        Text(action.name)
    }
}
