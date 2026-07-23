/*
 * Copyright (c) 2026, Potaty
 */

package com.potaty.app.generate

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.browser.document
import kotlinx.browser.window

class RuntimeSettingsValidationTest {
    @Test
    fun acceptsConfiguredDevelopmentProjectId() {
        assertTrue(isCanonicalProjectId("00000000-0000-0000-0000-000000000010"))
    }

    @Test
    fun acceptsVersionedUuid() {
        assertTrue(isCanonicalProjectId("4b40fa14-1b61-4d13-9cef-16b72d0ff45e"))
    }

    @Test
    fun rejectsMalformedOrInjectedValues() {
        assertFalse(isCanonicalProjectId("not-a-uuid"))
        assertFalse(isCanonicalProjectId("4b40fa14-1b61-4d13-9cef-16b72d0ff45e/other"))
        assertFalse(isCanonicalProjectId("4b40fa14-1b61-4d13-9cef-16b72d0ff45e\n"))
    }

    @Test
    fun diagramWidthUsesTerminalCellsForKorean() {
        assertEquals(5, diagramCellWidth("API\n웹 앱"))
    }

    @Test
    fun nativeUint8ArrayUsesBracketIndexingForRequestDigest() {
        val bytes: dynamic = js("new Uint8Array([0, 1, 15, 16, 127, 128, 255])")

        assertEquals("00010f107f80ff", uint8ArrayToHex(bytes))
    }

    @Test
    fun sha256ByteConversionProducesExactly64LowercaseHexCharacters() {
        val bytes: dynamic = js("new Uint8Array(32)")

        val hex = uint8ArrayToHex(bytes)

        assertEquals(64, hex.length)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(hex))
    }

    @Test
    fun uuidFallbackAppliesVersionAndVariantBits() {
        val bytes: dynamic =
            js("new Uint8Array([0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15])")

        assertEquals("00010203-0405-4607-8809-0a0b0c0d0e0f", uuidV4FromRandomBytes(bytes))
    }

    @Test
    fun generationGateSerializesOldCancellationBeforeRetry() {
        val gate = GenerationRunGate()

        assertTrue(gate.tryStart(1))
        assertTrue(gate.isActive)
        assertFalse(gate.tryStart(2))
        assertFalse(gate.finish(2))
        assertTrue(gate.finish(1))
        assertFalse(gate.isActive)
        assertTrue(gate.tryStart(2))
    }

    @Test
    fun transcriptReadGateRejectsCallbacksAfterPrivacyScrubOrReplacement() {
        val gate = TranscriptReadGate()
        val first = gate.begin()

        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first), "a scrubbed FileReader callback must stay stale")

        val replacement = gate.begin()
        assertTrue(gate.isCurrent(replacement))
        assertFalse(gate.isCurrent(first), "an older read cannot overwrite its replacement")
    }

    @Test
    fun controllerAbortsAndFencesTranscriptReadsAcrossBrowserLifecycle(): Promise<Unit> {
        val global: dynamic = js("globalThis")
        val originalFileReader = global.FileReader
        val originalBody = document.body?.innerHTML.orEmpty()
        val originalTitle = document.title
        val originalOverflow = document.body?.style?.getPropertyValue("overflow").orEmpty()
        global.__potatyControlledReaders = js("[]")
        global.FileReader =
            js(
                "(function(){function ControlledFileReader(){this.result=null;" +
                    "this.onload=null;this.onerror=null;this.aborted=false;" +
                    "globalThis.__potatyControlledReaders.push(this);}" +
                    "ControlledFileReader.prototype.readAsText=function(file){this.file=file;};" +
                    "ControlledFileReader.prototype.abort=function(){this.aborted=true;};" +
                    "return ControlledFileReader;})()"
            )
        document.body?.innerHTML = studioLifecycleFixture()
        textAreaValue("prompt-input", "restored prompt")
        inputValue("github-url", "restored/repository")
        inputValue("github-ref", "restored-ref")

        GenerateController().mount()

        return Promise { resolve, reject ->
            window.setTimeout(
                {
                    try {
                        assertEquals("", textAreaValue("prompt-input"))
                        assertEquals("", inputValue("github-url"))
                        assertEquals("", inputValue("github-ref"))

                        dispatchTranscriptDrop("first.txt")
                        dispatchTranscriptDrop("replacement.txt")
                        val readers: dynamic = global.__potatyControlledReaders
                        val first: dynamic = readers[0]
                        val replacement: dynamic = readers[1]
                        assertTrue(first.aborted as Boolean)

                        first.result = "stale first transcript"
                        first.onload(js("new Event('load')"))
                        assertTrue(hasHiddenAttribute("transcript-selection"))
                        assertEquals("", elementText("transcript-name"))

                        replacement.result = "Replacement describes service A calling service B."
                        replacement.onload(js("new Event('load')"))
                        assertFalse(hasHiddenAttribute("transcript-selection"))
                        assertEquals("replacement.txt", elementText("transcript-name"))

                        first.onerror(js("new Event('error')"))
                        assertEquals("replacement.txt", elementText("transcript-name"))
                        assertFalse(hasHiddenAttribute("transcript-selection"))
                        assertTrue(hasHiddenAttribute("composer-message"))

                        dispatchTranscriptDrop("removed.txt")
                        val removed: dynamic = readers[2]
                        clickElement("remove-transcript")
                        assertTrue(removed.aborted as Boolean)
                        removed.result = "stale removed transcript"
                        removed.onload(js("new Event('load')"))
                        assertTrue(hasHiddenAttribute("transcript-selection"))

                        dispatchTranscriptDrop("history.txt")
                        val history: dynamic = readers[3]
                        textAreaValue("prompt-input", "history-restored prompt")
                        inputValue("github-url", "history/restored")
                        dispatchPersistedPageShow()
                        assertTrue(history.aborted as Boolean)
                        history.result = "stale history-restored transcript"
                        history.onload(js("new Event('load')"))
                        assertEquals("", textAreaValue("prompt-input"))
                        assertEquals("", inputValue("github-url"))
                        assertTrue(hasHiddenAttribute("transcript-selection"))

                        restoreLifecycleFixture(
                            global,
                            originalFileReader,
                            originalBody,
                            originalTitle,
                            originalOverflow
                        )
                        resolve(Unit)
                    } catch (error: Throwable) {
                        restoreLifecycleFixture(
                            global,
                            originalFileReader,
                            originalBody,
                            originalTitle,
                            originalOverflow
                        )
                        reject(error)
                    }
                },
                0
            )
        }
    }
}

private fun studioLifecycleFixture(): String =
    """
    <div id="potaty-studio">
      <div id="studio-input-view"></div><div id="studio-workbench" hidden></div>
      <textarea id="prompt-input"></textarea><span id="prompt-counter"></span>
      <div id="composer-message" hidden></div>
      <input id="transcript-file" type="file"><div id="transcript-dropzone"></div>
      <div id="transcript-selection" hidden><span id="transcript-name"></span>
        <span id="transcript-meta"></span>
        <button data-action="remove-transcript" id="remove-transcript"></button>
      </div>
      <input id="transcript-redact" type="checkbox" checked>
      <input id="github-url"><input id="github-ref">
      <select id="diagram-type"><option value="architecture"></option></select>
      <select id="detail-level"><option value="medium"></option></select>
      <select id="style-profile"><option value="potaty-slate"></option></select>
      <button id="generate-button"></button><pre id="diagram-output"></pre>
      <span id="connection-state"></span><span id="connection-label"></span>
    </div>
    <div id="editor-shell"></div>
    <input id="settings-api-url"><input id="settings-token">
    <input id="settings-project-id"><input id="settings-github-url">
    """.trimIndent()

private fun dispatchTranscriptDrop(name: String) {
    val options: dynamic = js("({type: 'text/plain'})")
    val file: dynamic =
        js("Reflect").construct(js("File"), arrayOf(arrayOf("fixture"), name, options))
    val files: dynamic = js("({})")
    files.item = { _: Int -> file }
    val transfer: dynamic = js("({})")
    transfer.files = files
    val event: dynamic = js("new Event('drop', {bubbles: true, cancelable: true})")
    val descriptor: dynamic = js("({})")
    descriptor.value = transfer
    js("Object").defineProperty(event, "dataTransfer", descriptor)
    document.getElementById("transcript-dropzone")?.asDynamic()?.dispatchEvent(event)
}

private fun dispatchPersistedPageShow() {
    val event: dynamic = js("new Event('pageshow')")
    val descriptor: dynamic = js("({})")
    descriptor.value = true
    js("Object").defineProperty(event, "persisted", descriptor)
    window.asDynamic().dispatchEvent(event)
}

private fun clickElement(id: String) {
    document.getElementById(id)?.asDynamic()?.click()
}

private fun textAreaValue(
    id: String,
    value: String? = null
): String {
    val element: dynamic = document.getElementById(id)
    if (value != null) element.value = value
    return element.value as String
}

private fun inputValue(
    id: String,
    value: String? = null
): String = textAreaValue(id, value)

private fun elementText(id: String): String =
    document.getElementById(id)?.textContent.orEmpty()

private fun hasHiddenAttribute(id: String): Boolean =
    document.getElementById(id)?.hasAttribute("hidden") == true

private fun restoreLifecycleFixture(
    global: dynamic,
    originalFileReader: dynamic,
    originalBody: String,
    originalTitle: String,
    originalOverflow: String
) {
    global.FileReader = originalFileReader
    js("delete globalThis.__potatyControlledReaders")
    document.body?.innerHTML = originalBody
    document.body?.style?.setProperty("overflow", originalOverflow)
    document.title = originalTitle
}
