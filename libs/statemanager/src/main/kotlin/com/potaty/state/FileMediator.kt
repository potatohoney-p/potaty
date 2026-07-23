/*
 * Copyright (c) 2023, tuanchauict
 * Copyright (c) 2026, Potaty
 */

package com.potaty.state

import com.potaty.html.A
import com.potaty.html.Input
import com.potaty.html.InputType
import com.potaty.html.setAttributes
import kotlinx.browser.document
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.FileList
import org.w3c.files.FileReader
import org.w3c.files.get

/**
 * A mediator class for file interactions.
 */
internal class FileMediator {
    fun saveFile(filename: String, jsonString: String) {
        document.body?.run {
            val fileBlob = Blob(arrayOf(jsonString))
            val node = A(classes = "hidden") {
                href = URL.Companion.createObjectURL(fileBlob)
                setAttributes("download" to "$filename.$EXTENSION")
            }
            node.click()
            node.remove()
        }
    }

    fun openFile(onFileLoadedAction: (String) -> Unit) {
        document.body?.run {
            val fileInput = Input(InputType.FILE, classes = "hidden") {
                // Keep importing MonoSketch backups after the Potaty rename. New saves use the
                // Potaty extension, while both schemas remain JSON-compatible.
                setAttributes("accept" to ".$EXTENSION,.$LEGACY_EXTENSION")
            }
            fileInput.onchange = {
                readFile(fileInput.files, onFileLoadedAction)
                fileInput.remove()
            }
            fileInput.click()
        }
    }

    private fun readFile(fileList: FileList?, onFileLoadedAction: (String) -> Unit) {
        val selectedFile = fileList?.get(0) ?: return
        val reader = FileReader()
        reader.onload = {
            val text = reader.result.toString()
            onFileLoadedAction(text)
        }
        reader.readAsText(selectedFile)
    }

    companion object {
        private const val EXTENSION = "potaty"
        private const val LEGACY_EXTENSION = "mono"
    }
}
