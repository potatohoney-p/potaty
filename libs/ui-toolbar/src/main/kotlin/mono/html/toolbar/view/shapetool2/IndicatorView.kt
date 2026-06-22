/*
 * Copyright (c) 2023, tuanchauict
 */

@file:Suppress("FunctionName")

package mono.html.toolbar.view.shapetool2

import androidx.compose.runtime.Composable
import mono.ui.compose.ext.classes
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

@Composable
internal fun IndicatorView(isVisible: Boolean) {
    if (!isVisible) {
        return
    }
    Div(
        attrs = {
            classes("h-full", "justify-center", "mb-[200px]", "mt-7", "p-2.5")
        }
    ) {
        Span(
            attrs = {
                classes(
                    "text-[13px]",
                    "font-mono",
                    "text-[var(--shapetool-indicator-color)]",
                    "font-light",
                    "block",
                    "text-center",
                    "mx-2",
                    "my-[120px]"
                )
            }
        ) {
            Text("Select a shape for updating its properties here")
        }
    }
}

@Composable
internal fun FooterView() {
    Div(
        attrs = {
            classes(
                "py-2.5",
                "px-2",
                "flex",
                "justify-center",
                "items-center"
            )
        }
    ) {
        Span(
            attrs = {
                classes(
                    "leading-[14px]",
                    "text-sm",
                    "text-[var(--shapetool-footer-color)]",
                    "flex",
                    "items-center"
                )
            }
        ) {
            Text("Potaty")
        }
    }
}
