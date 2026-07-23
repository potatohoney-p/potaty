/*
 * Copyright (c) 2026, Potaty
 *
 * Production-facing source-grounded Studio controller. The HTML owns semantics and accessibility;
 * this class owns state, validation, file IO, API orchestration, rendering, and the transition into
 * the original manual editor. No source-derived value is inserted with innerHTML.
 */

package com.potaty.app.generate

import com.potaty.common.DisplayCells
import com.potaty.ir.DiagramIR
import com.potaty.ir.EvidenceRef
import com.potaty.ir.IrJson
import com.potaty.render.ascii.AsciiRenderer
import com.potaty.workbench.FetchTransport
import com.potaty.workbench.PotatyApiClient
import com.potaty.workbench.WorkbenchController
import com.potaty.workbench.WorkbenchProgress
import com.potaty.workbench.WorkbenchResult
import com.potaty.workbench.WorkbenchRetryJobKey
import com.potaty.workbench.WorkbenchRetryKeys
import com.potaty.workbench.WorkbenchRetrySourceKey
import com.potaty.workbench.WorkbenchRetryState
import com.potaty.workbench.WorkbenchRetryStateStore
import com.potaty.workbench.WorkbenchSourceSummary
import com.potaty.workbench.consumesRetryAttempt
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Date
import kotlin.js.Promise
import kotlin.js.jsTypeOf
import kotlin.math.roundToInt
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLPreElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FileReader
import org.w3c.files.get

class GenerateController(private val onInsertDiagram: ((DiagramIR) -> Unit)? = null) {
    private enum class SourceMode {
        PROMPT,
        TRANSCRIPT,
        GITHUB
    }

    private data class RuntimeSettings(
        val apiBaseUrl: String,
        val accessToken: String,
        val projectId: String,
        val githubInstallUrl: String
    )

    private var mode = SourceMode.PROMPT
    private var transcriptText: String? = null
    private var transcriptName: String? = null
    private var transcriptSize: Double = 0.0
    private val transcriptReads = TranscriptReadGate()
    private var activeTranscriptReader: FileReader? = null
    private var generationId = 0
    private var generationStartedAt = 0.0
    private var elapsedTimerId: Int? = null
    private var toastTimerId: Int? = null
    private var zoomPercent = 100
    private var activeFormat = "ascii"
    private var asciiText = ""
    private var mermaidText = ""
    private var lastIr: DiagramIR? = null
    private var lastFocusedElement: HTMLElement? = null
    private val retryKeys =
        WorkbenchRetryKeys(::newIdempotencyKey, SessionWorkbenchRetryStore())
    private val generationRuns = GenerationRunGate()
    private var activeTransport: FetchTransport? = null

    /** Credential is deliberately memory-only; reload-safe retry state contains opaque keys only. */
    private var runtimeAccessToken = ""

    private lateinit var studio: HTMLElement
    private lateinit var editorShell: HTMLElement
    private lateinit var inputView: HTMLElement
    private lateinit var workbench: HTMLElement
    private lateinit var promptInput: HTMLTextAreaElement
    private lateinit var transcriptInput: HTMLInputElement
    private lateinit var githubInput: HTMLInputElement
    private lateinit var githubRefInput: HTMLInputElement
    private lateinit var diagramType: HTMLSelectElement
    private lateinit var detailLevel: HTMLSelectElement
    private lateinit var styleProfile: HTMLSelectElement
    private lateinit var generateButton: HTMLButtonElement
    private lateinit var diagramOutput: HTMLPreElement

    fun mount() {
        studio = elementOrNull("potaty-studio") ?: return
        editorShell = element("editor-shell")
        inputView = element("studio-input-view")
        workbench = element("studio-workbench")
        promptInput = textArea("prompt-input")
        transcriptInput = input("transcript-file")
        githubInput = input("github-url")
        githubRefInput = input("github-ref")
        diagramType = select("diagram-type")
        detailLevel = select("detail-level")
        styleProfile = select("style-profile")
        generateButton = button("generate-button")
        diagramOutput = pre("diagram-output")

        bindTabs()
        bindActions()
        bindPrompt()
        bindTranscriptDrop()
        bindSettingsDialog()
        bindFormatSwitch()
        bindKeyboardShortcuts()
        purgeLegacyPersistedCredential()
        clearRestoredSourceControls()
        window.setTimeout({ clearRestoredSourceControls() }, 0)
        window.addEventListener(
            "pageshow",
            { event ->
                if (event.asDynamic().persisted == true) clearRestoredSourceControls()
            }
        )
        loadSettingsIntoForm()
        updateConnectionState()
        updatePromptCounter()

        val query = window.location.search.lowercase()
        if (query.contains("editor=1")) showStudio(false) else showStudio(true)
    }

    private fun bindTabs() {
        val tabs = document.querySelectorAll(".source-tab[data-mode]")
        for (index in 0 until tabs.length) {
            val tab = tabs.item(index) as? HTMLElement ?: continue
            tab.addEventListener("click", { switchMode(modeOf(tab)) })
            tab.addEventListener(
                "keydown",
                { event ->
                    val key = (event as? KeyboardEvent)?.key ?: return@addEventListener
                    if (
                        key != "ArrowLeft" && key != "ArrowRight" && key != "Home" && key != "End"
                    ) {
                        return@addEventListener
                    }
                    event.preventDefault()
                    val next =
                        when (key) {
                            "Home" -> 0
                            "End" -> tabs.length - 1
                            "ArrowLeft" -> (index - 1 + tabs.length) % tabs.length
                            else -> (index + 1) % tabs.length
                        }
                    val nextTab = tabs.item(next) as? HTMLElement ?: return@addEventListener
                    switchMode(modeOf(nextTab))
                    nextTab.focus()
                }
            )
        }
    }

    private fun bindActions() {
        // Delegate from the stable document root. The legacy editor can reconcile parts of the
        // static shell after startup; listeners attached directly to replaced nodes would be lost.
        document.addEventListener(
            "click",
            { event ->
                var target = event.target as? Element
                while (target != null) {
                    val action = target.getAttribute("data-action")
                    if (action != null) {
                        handleAction(action)
                        return@addEventListener
                    }
                    target = target.parentElement
                }
            }
        )

        elementOrNull("studio-open-button")?.addEventListener("click", { showStudio(true) })
        generateButton.addEventListener("click", { startGeneration() })
    }

    private fun handleAction(action: String) {
        when (action) {
            "open-editor" -> showStudio(false)
            "back-to-input" -> backToInput()
            "open-settings" -> openSettings()
            "close-settings" -> closeSettings()
            "save-settings" -> saveSettings()
            "reset-settings" -> resetSettings()
            "remove-transcript" -> clearTranscript()
            "connect-github" -> connectGitHub()
            "copy-output" -> copyOutput()
            "download-output" -> downloadOutput()
            "insert-editor" -> insertIntoEditor()
            "zoom-in" -> setZoom(zoomPercent + ZOOM_STEP)
            "zoom-out" -> setZoom(zoomPercent - ZOOM_STEP)
        }
    }

    private fun bindPrompt() {
        promptInput.addEventListener("input", { updatePromptCounter() })
    }

    private fun bindTranscriptDrop() {
        transcriptInput.addEventListener(
            "change",
            {
                handleTranscriptFile(transcriptInput.files?.get(0))
            }
        )

        val dropzone = element("transcript-dropzone")
        listOf("dragenter", "dragover").forEach { type ->
            dropzone.addEventListener(
                type,
                { event ->
                    event.preventDefault()
                    dropzone.classList.add("is-dragging")
                }
            )
        }
        listOf("dragleave", "dragend").forEach { type ->
            dropzone.addEventListener(type, { dropzone.classList.remove("is-dragging") })
        }
        dropzone.addEventListener(
            "drop",
            { event ->
                event.preventDefault()
                dropzone.classList.remove("is-dragging")
                val file = event.asDynamic().dataTransfer?.files?.item(0) as? File
                handleTranscriptFile(file)
            }
        )
    }

    private fun bindSettingsDialog() {
        document.addEventListener(
            "keydown",
            { raw ->
                val event = raw as? KeyboardEvent ?: return@addEventListener
                val layer = element("settings-layer")
                if (isHidden(layer)) return@addEventListener
                if (event.key == "Escape") {
                    event.preventDefault()
                    event.stopPropagation()
                    closeSettings()
                    return@addEventListener
                }
                if (event.key != "Tab") return@addEventListener

                val focusables =
                    layer.querySelectorAll(
                        "button:not([disabled]):not([tabindex='-1']), " +
                            "input:not([disabled]):not([tabindex='-1'])"
                    )
                if (focusables.length == 0) return@addEventListener
                val first = focusables.item(0) as? HTMLElement ?: return@addEventListener
                val last =
                    focusables.item(focusables.length - 1) as? HTMLElement
                        ?: return@addEventListener
                val active = document.activeElement
                if (event.shiftKey && active == first) {
                    event.preventDefault()
                    last.focus()
                } else if (!event.shiftKey && active == last) {
                    event.preventDefault()
                    first.focus()
                }
            }
        )
    }

    private fun bindFormatSwitch() {
        val formats = document.querySelectorAll("[data-format]")
        for (index in 0 until formats.length) {
            val target = formats.item(index) as? HTMLButtonElement ?: continue
            target.addEventListener(
                "click",
                {
                    setOutputFormat(target.getAttribute("data-format") ?: "ascii")
                }
            )
            target.addEventListener(
                "keydown",
                { raw ->
                    val event = raw as? KeyboardEvent ?: return@addEventListener
                    if (event.key !in setOf("ArrowLeft", "ArrowRight", "Home", "End")) {
                        return@addEventListener
                    }
                    event.preventDefault()
                    val nextIndex =
                        when (event.key) {
                            "Home" -> 0
                            "End" -> formats.length - 1
                            "ArrowLeft" -> (index - 1 + formats.length) % formats.length
                            else -> (index + 1) % formats.length
                        }
                    val next =
                        formats.item(nextIndex) as? HTMLButtonElement ?: return@addEventListener
                    setOutputFormat(next.getAttribute("data-format") ?: "ascii")
                    next.focus()
                }
            )
        }
    }

    private fun bindKeyboardShortcuts() {
        window.addEventListener(
            "keydown",
            { raw ->
                val event = raw as? KeyboardEvent ?: return@addEventListener
                if (isHidden(studio) || isHidden(workbench)) return@addEventListener
                if (event.key == "Escape") {
                    backToInput()
                    return@addEventListener
                }
                val target = event.target as? HTMLElement
                val isEditable =
                    target?.tagName?.lowercase() in setOf("input", "textarea", "select")
                if (
                    !isEditable &&
                    (event.ctrlKey || event.metaKey) &&
                    event.shiftKey &&
                    event.key.lowercase() == "c"
                ) {
                    event.preventDefault()
                    copyOutput()
                }
            }
        )
    }

    private fun switchMode(newMode: SourceMode) {
        mode = newMode
        val tabs = document.querySelectorAll(".source-tab[data-mode]")
        for (index in 0 until tabs.length) {
            val tab = tabs.item(index) as? HTMLElement ?: continue
            val active = modeOf(tab) == newMode
            toggleClass(tab, "is-active", active)
            tab.setAttribute("aria-selected", active.toString())
            tab.setAttribute("tabindex", if (active) "0" else "-1")
        }
        setHidden(element("source-panel-prompt"), newMode != SourceMode.PROMPT)
        setHidden(element("source-panel-transcript"), newMode != SourceMode.TRANSCRIPT)
        setHidden(element("source-panel-github"), newMode != SourceMode.GITHUB)
        hideComposerMessage()
    }

    private fun modeOf(tab: HTMLElement): SourceMode =
        when (tab.getAttribute("data-mode")) {
            "transcript" -> SourceMode.TRANSCRIPT
            "github" -> SourceMode.GITHUB
            else -> SourceMode.PROMPT
        }

    private fun updatePromptCounter() {
        element("prompt-counter").textContent =
            "${formatCount(promptInput.value.length)} / ${formatCount(
                MAX_SOURCE_CHARS
            )}"
    }

    private fun handleTranscriptFile(file: File?) {
        cancelTranscriptRead()
        if (file == null) return
        if (file.size.toDouble() > MAX_TRANSCRIPT_BYTES) {
            showComposerMessage(
                "That file is larger than 2 MB. Export a focused transcript " +
                    "or split it into sections.",
                true
            )
            clearTranscript()
            return
        }
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_TRANSCRIPT_EXTENSIONS) {
            showComposerMessage("Use a plain-text transcript: .txt, .md, .markdown, or .log.", true)
            clearTranscript()
            return
        }

        val reader = FileReader()
        val readId = transcriptReads.begin()
        activeTranscriptReader = reader
        reader.onload = onload@{
            if (!transcriptReads.isCurrent(readId) || activeTranscriptReader !== reader) {
                return@onload Unit
            }
            activeTranscriptReader = null
            val text = reader.result?.toString().orEmpty()
            if (text.length > MAX_SOURCE_CHARS) {
                showComposerMessage(
                    "The transcript contains more than ${formatCount(MAX_SOURCE_CHARS)} " +
                        "characters. Split it before generating.",
                    true
                )
                clearTranscript()
            } else if (text.isBlank()) {
                showComposerMessage("The selected transcript is empty.", true)
                clearTranscript()
            } else {
                transcriptText = text
                transcriptName = file.name
                transcriptSize = file.size.toDouble()
                element("transcript-name").textContent = file.name
                element("transcript-meta").textContent =
                    "${formatBytes(file.size.toDouble())} · ${formatCount(
                        text.length
                    )} characters"
                setHidden(element("transcript-selection"), false)
                setHidden(element("transcript-dropzone"), true)
                hideComposerMessage()
            }
        }
        reader.onerror = onerror@{
            if (!transcriptReads.isCurrent(readId) || activeTranscriptReader !== reader) {
                return@onerror Unit
            }
            activeTranscriptReader = null
            showComposerMessage(
                "Potaty could not read that file. Try exporting it as UTF-8 plain text.",
                true
            )
            clearTranscript()
        }
        reader.readAsText(file)
    }

    private fun clearTranscript() {
        cancelTranscriptRead()
        transcriptText = null
        transcriptName = null
        transcriptSize = 0.0
        transcriptInput.value = ""
        setHidden(element("transcript-selection"), true)
        setHidden(element("transcript-dropzone"), false)
    }

    private fun cancelTranscriptRead() {
        transcriptReads.invalidate()
        activeTranscriptReader?.let { reader -> runCatching { reader.abort() } }
        activeTranscriptReader = null
    }

    private fun clearRestoredSourceControls() {
        promptInput.value = ""
        githubInput.value = ""
        githubRefInput.value = ""
        clearTranscript()
        updatePromptCounter()
    }

    private fun startGeneration() {
        if (generationRuns.isActive) {
            showComposerMessage(
                "The previous generation is still stopping. Retry will unlock as soon as its " +
                    "network request settles.",
                false
            )
            return
        }
        val settings = currentSettings()
        if (
            settings.apiBaseUrl.isBlank() ||
            settings.accessToken.isBlank() ||
            settings.projectId.isBlank()
        ) {
            showComposerMessage(
                "Connect an API URL, access token, and project ID before generating.",
                true
            )
            openSettings()
            return
        }

        val validationError = validateSource()
        if (validationError != null) {
            showComposerMessage(validationError, true)
            return
        }

        // Freeze the exact logical request before entering the suspend flow. The fingerprints stay
        // only in memory and let an unknown network outcome reuse both idempotency keys on retry.
        val selectedMode = mode
        val selectedDiagramType = diagramType.value
        val selectedDetailLevel = detailLevel.value
        val promptText = promptInput.value.trim()
        val selectedTranscriptName = transcriptName ?: "Transcript"
        val transcriptSourceText =
            if (input("transcript-redact").checked) {
                redactLikelyPii(transcriptText.orEmpty())
            } else {
                transcriptText.orEmpty()
            }
        val repositoryUrl = normalizeGitHubUrl(githubInput.value)
        val repositoryRef = githubRefInput.value.trim().ifBlank { null }
        val objective =
            when (selectedMode) {
                SourceMode.PROMPT -> promptText.take(240)
                SourceMode.TRANSCRIPT ->
                    "Map the decisions, actions, risks, and dependencies in this transcript."
                SourceMode.GITHUB ->
                    "Explain this repository's architecture and important dependencies."
            }
        val sourceFingerprint =
            canonicalFingerprint(
                listOf(
                    "potaty-source-v1",
                    settings.projectId,
                    selectedMode.name,
                    when (selectedMode) {
                        SourceMode.PROMPT -> titleForPrompt(promptText)
                        SourceMode.TRANSCRIPT -> selectedTranscriptName
                        SourceMode.GITHUB -> repositoryUrl
                    },
                    when (selectedMode) {
                        SourceMode.PROMPT -> promptText
                        SourceMode.TRANSCRIPT -> transcriptSourceText
                        SourceMode.GITHUB -> repositoryRef.orEmpty()
                    }
                )
            )
        val generationFingerprint =
            canonicalFingerprint(
                listOf(
                    "potaty-generation-v1",
                    sourceFingerprint,
                    selectedDiagramType,
                    selectedDetailLevel,
                    objective,
                    "mermaid"
                )
            )
        hideComposerMessage()
        generationId += 1
        val thisGeneration = generationId
        val transport = FetchTransport()
        check(generationRuns.tryStart(thisGeneration)) { "generation ownership was not available" }
        activeTransport = transport
        generationStartedAt = Date.now()
        generateButton.disabled = true
        prepareWorkbench()
        startElapsedTimer()

        val controller =
            WorkbenchController(
                client =
                PotatyApiClient(
                    baseUrl = settings.apiBaseUrl.trimEnd('/'),
                    transport = transport,
                    token = settings.accessToken
                ),
                sleep = ::sleepMs
            )
        val progressCallback: (WorkbenchProgress) -> Unit = { progress ->
            if (thisGeneration == generationId) updateProgress(progress)
        }
        val cancelled = { thisGeneration != generationId }

        launch {
            try {
                val sourceDigest = sha256Fingerprint(sourceFingerprint)
                val generationDigest = sha256Fingerprint(generationFingerprint)
                if (thisGeneration != generationId) return@launch
                val idempotencyKeys = retryKeys.forAttempt(sourceDigest, generationDigest)
                val result =
                    when (selectedMode) {
                        SourceMode.PROMPT ->
                            controller.generateFromText(
                                projectId = settings.projectId,
                                text = promptText,
                                diagramType = selectedDiagramType,
                                sourceIdempotencyKey = idempotencyKeys.sourceKey,
                                jobIdempotencyKey = idempotencyKeys.jobKey,
                                objective = objective,
                                displayName = titleForPrompt(promptText),
                                outputFormats = listOf("mermaid"),
                                abstractionLevel = selectedDetailLevel,
                                onProgress = progressCallback,
                                isCancelled = cancelled
                            )
                        SourceMode.TRANSCRIPT ->
                            controller.generateFromTranscript(
                                projectId = settings.projectId,
                                text = transcriptSourceText,
                                diagramType = selectedDiagramType,
                                sourceIdempotencyKey = idempotencyKeys.sourceKey,
                                jobIdempotencyKey = idempotencyKeys.jobKey,
                                displayName = selectedTranscriptName,
                                objective = objective,
                                outputFormats = listOf("mermaid"),
                                abstractionLevel = selectedDetailLevel,
                                onProgress = progressCallback,
                                isCancelled = cancelled
                            )
                        SourceMode.GITHUB ->
                            controller.generateFromGitHub(
                                projectId = settings.projectId,
                                repoUrl = repositoryUrl,
                                ref = repositoryRef,
                                diagramType = selectedDiagramType,
                                sourceIdempotencyKey = idempotencyKeys.sourceKey,
                                jobIdempotencyKey = idempotencyKeys.jobKey,
                                objective = objective,
                                outputFormats = listOf("mermaid"),
                                abstractionLevel = selectedDetailLevel,
                                onProgress = progressCallback,
                                isCancelled = cancelled
                            )
                    }
                val retryStateError =
                    if (result.consumesRetryAttempt()) {
                        runCatching {
                            retryKeys.finishAttempt(generationDigest, idempotencyKeys.jobKey)
                        }.exceptionOrNull()
                    } else {
                        null
                    }
                if (thisGeneration != generationId) return@launch
                when (result) {
                    is WorkbenchResult.Ready -> showResult(result)
                    is WorkbenchResult.Failed -> showGenerationFailure(result)
                }
                if (retryStateError != null) {
                    showToast(
                        "Safe retry storage became unavailable. Reload this tab before " +
                            "starting another generation.",
                        error = true
                    )
                }
            } catch (error: Throwable) {
                if (thisGeneration == generationId) showGenerationFailure(error)
            } finally {
                if (generationRuns.finish(thisGeneration)) {
                    activeTransport = null
                    generateButton.disabled = false
                    stopElapsedTimer()
                    if (
                        thisGeneration != generationId &&
                        !inputView.hasAttribute("hidden")
                    ) {
                        showComposerMessage(
                            "The previous generation stopped. Retry will safely resume " +
                                "or replace its authoritative server result.",
                            false
                        )
                    }
                }
            }
        }
    }

    private fun validateSource(): String? =
        when (mode) {
            SourceMode.PROMPT ->
                when {
                    promptInput.value.isBlank() -> "Describe the system or process you want to map."
                    promptInput.value.trim().length < 12 ->
                        "Add a little more context so Potaty can identify meaningful relationships."
                    else -> null
                }
            SourceMode.TRANSCRIPT ->
                if (transcriptText.isNullOrBlank()) {
                    "Choose a transcript file before generating."
                } else {
                    null
                }
            SourceMode.GITHUB -> {
                val url = normalizeGitHubUrl(githubInput.value)
                if (!GITHUB_REPO_REGEX.matches(url)) {
                    "Enter a GitHub repository like owner/repository or a full GitHub URL."
                } else {
                    null
                }
            }
        }

    private fun prepareWorkbench() {
        setHidden(inputView, true)
        setHidden(workbench, false)
        setHidden(element("generation-progress"), false)
        diagramOutput.textContent = ""
        diagramOutput.setAttribute("aria-busy", "true")
        asciiText = ""
        mermaidText = ""
        lastIr = null
        activeFormat = "ascii"
        setOutputFormat("ascii")
        setZoom(100)
        resetQualityPanel()
        resetStages()
        renderPendingSourceSummary()
        val pendingTitle =
            when (mode) {
                SourceMode.PROMPT -> titleForPrompt(promptInput.value)
                SourceMode.TRANSCRIPT -> transcriptName ?: "Transcript map"
                SourceMode.GITHUB ->
                    githubInput.value.trim().substringAfterLast('/').ifBlank { "Repository map" }
            }
        element("workbench-title").textContent = pendingTitle
        document.title = "$pendingTitle — Potaty"
    }

    private fun showResult(result: WorkbenchResult.Ready) {
        val decoded = IrJson.decode(result.version.ir.toString())
        val styled =
            decoded.copy(styleHints = decoded.styleHints.copy(styleProfile = styleProfile.value))
        val rendering = AsciiRenderer().render(styled)
        val reviewStatus =
            artifactReviewStatus(result.version.evidenceCoverage, result.version.validationReport)
        lastIr = styled
        asciiText = rendering.text.ifBlank { "(No supported structure was found in this source.)" }
        mermaidText =
            result.mermaid.orEmpty().ifBlank {
                "%% Mermaid rendering is unavailable for this artifact."
            }
        setHidden(element("generation-progress"), true)
        diagramOutput.setAttribute("aria-busy", "false")
        setOutputFormat("ascii")
        completeAllStages()

        // The IR title may intentionally preserve a long generation objective. Keep concise goals
        // visible, but fall back to the bounded source name when the objective would dominate H1.
        val irTitle = styled.title.trim()
        val title =
            if (irTitle.length in 1..MAX_RESULT_TITLE_CHARS) {
                irTitle
            } else {
                result.source.displayName.ifBlank { irTitle }.take(MAX_RESULT_TITLE_CHARS)
            }
        element("workbench-title").textContent = title
        document.title = "$title — Potaty"
        element("diagram-dimensions").textContent = dimensionsLabel(asciiText, styled)
        element("workbench-status").textContent =
            "● ${reviewStatus.workbenchLabel()} · ${countLabel(styled.nodes.size, "node")} · " +
            countLabel(styled.edges.size, "edge")
        renderSourceSummary(result.source)
        renderQuality(result, styled, rendering.quality.isAcceptable())
        renderFacts(styled)
        renderWarnings(styled, result.version.validationReport)
        showToast(reviewStatus.completionMessage())
    }

    private fun updateProgress(progress: WorkbenchProgress) {
        val value = progress.progress.coerceIn(0.02, 1.0)
        element("progress-bar").setAttribute("style", "width:${(value * 100).roundToInt()}%")
        val stage = normalizeStage(progress.currentStage, value)
        markStage(stage)
        val (title, detail) =
            when (stage) {
                "ingest" -> "Reading the source" to "Normalizing text and applying safety checks…"
                "extract" ->
                    "Finding the structure" to
                        "Identifying actors, components, decisions, and relationships…"
                "plan" ->
                    "Building the diagram" to
                        "Creating a source-grounded intermediate representation…"
                else ->
                    "Rendering the map" to
                        "Laying out ASCII, validating claims, and checking readability…"
            }
        element("progress-title").textContent = title
        element("progress-detail").textContent = detail
        element("workbench-status").textContent =
            "● ${progress.status.replace('_', ' ')} · ${(value * 100).roundToInt()}%"
    }

    private fun normalizeStage(raw: String?, progress: Double): String {
        val value = raw.orEmpty().lowercase()
        return when {
            value.contains("ingest") || value.contains("normal") -> "ingest"
            value.contains("extract") || value.contains("chunk") -> "extract"
            value.contains("render") || value.contains("valid") || progress >= .88 -> "render"
            value.contains("plan") || value.contains("ir") || progress >= .42 -> "plan"
            progress < .15 -> "ingest"
            progress < .36 -> "extract"
            progress < .86 -> "plan"
            else -> "render"
        }
    }

    private fun showGenerationFailure(result: WorkbenchResult.Failed) {
        showGenerationFailure(RuntimeException("${result.status}: ${result.reason.orEmpty()}"))
    }

    private fun showGenerationFailure(error: Throwable) {
        val message = friendlyError(error.message.orEmpty())
        setHidden(element("generation-progress"), true)
        diagramOutput.setAttribute("aria-busy", "false")
        diagramOutput.textContent = failureDiagram(message)
        asciiText = diagramOutput.textContent.orEmpty()
        mermaidText = ""
        element("diagram-dimensions").textContent = "Generation stopped"
        element("workbench-status").textContent = "● Action required"
        element("review-status").textContent = "Failed"
        element("quality-title").textContent = "No artifact created"
        element("quality-copy").textContent = message
        showToast(message, error = true)
    }

    private fun friendlyError(raw: String): String =
        when {
            raw.contains("401") || raw.contains("unauth", ignoreCase = true) ->
                "The API rejected this session. Check the access token in Runtime settings."
            raw.contains("402") || raw.contains("quota", ignoreCase = true) ->
                "This workspace has reached its generation budget. " +
                    "Review usage or raise the configured limit."
            raw.contains("404") ->
                "Potaty could not find that project, source, or repository. " +
                    "Check the project ID and URL."
            raw.contains("413") || raw.contains("too_large", ignoreCase = true) ->
                "The source is too large for this request. Split it into a focused section."
            raw.contains("request timed out", ignoreCase = true) ->
                "The Potaty API took too long to respond. Retry will reuse the same safe request."
            raw.contains("browser cryptography", ignoreCase = true) ||
                raw.contains("request fingerprint", ignoreCase = true) ->
                "Secure browser cryptography is unavailable. Use a current browser over HTTPS " +
                    "or localhost."
            raw.contains("safe retry capacity", ignoreCase = true) ->
                "Safe retry capacity is full because earlier outcomes are unresolved. " +
                    "Retry an earlier source to reconcile it before starting a new one."
            raw.contains("safe retry state", ignoreCase = true) ->
                "Safe retry storage is unavailable. Allow session storage, then reload this tab " +
                    "before generating."
            raw.contains("github", ignoreCase = true) ->
                "GitHub could not be indexed. Confirm the repository is public " +
                    "or install the configured Potaty GitHub App."
            raw.contains("fetch failed", ignoreCase = true) ||
                raw.contains(
                    "network",
                    ignoreCase = true
                ) ->
                "The Potaty API is unreachable. Check the API URL, CORS settings, " +
                    "and that the backend is running."
            raw.contains("cancellation_unconfirmed", ignoreCase = true) ->
                "Generation stopped locally, but the server could not confirm cancellation. " +
                    "Retry to resume the same job safely."
            raw.contains("cancellation_result_pending", ignoreCase = true) ->
                "The diagram finished while cancellation was requested. Retry to load that result."
            raw.contains("cancel", ignoreCase = true) -> "Generation was cancelled."
            raw.contains("needs_input", ignoreCase = true) ->
                "Potaty needs more explicit structure. Add named steps, bullet points, " +
                    "or relationships and try again."
            else ->
                "Potaty could not finish this diagram. Try a smaller source, " +
                    "a clearer objective, or check the backend logs."
        }

    private fun failureDiagram(message: String): String {
        val concise = message.take(62)
        val width = (concise.length + 4).coerceAtLeast(30)
        return buildString {
            append('┌').append("─".repeat(width)).append("┐\n")
            append("│  Generation needs attention".padEnd(width + 1)).append("│\n")
            append('├').append("─".repeat(width)).append("┤\n")
            append("│  $concise".padEnd(width + 1)).append("│\n")
            append('└').append("─".repeat(width)).append('┘')
        }
    }

    private fun renderPendingSourceSummary() {
        val name =
            when (mode) {
                SourceMode.PROMPT -> "Prompt input"
                SourceMode.TRANSCRIPT -> transcriptName ?: "Transcript"
                SourceMode.GITHUB -> githubInput.value.trim().ifBlank { "GitHub repository" }
            }
        val meta =
            when (mode) {
                SourceMode.PROMPT -> "${formatCount(promptInput.value.length)} characters"
                SourceMode.TRANSCRIPT -> "${formatBytes(transcriptSize)} · text transcript"
                SourceMode.GITHUB ->
                    "${githubRefInput.value.ifBlank { "default branch" }} · read-only"
            }
        replaceSourceSummary(name, meta)
    }

    private fun renderSourceSummary(source: WorkbenchSourceSummary) {
        val details =
            when (source.sourceType) {
                "GITHUB_REPOSITORY" ->
                    buildString {
                        append(source.ref ?: "default")
                        append(" · ${countLabel(source.filesIndexed, "file")} indexed")
                        if (source.filesSkipped > 0) {
                            append(" · ${countLabel(source.filesSkipped, "file")} skipped")
                        }
                        append(" · ${countLabel(source.chunkCount, "chunk")}")
                        if (source.treeTruncated) append(" · GitHub tree truncated")
                    }
                "TRANSCRIPT" -> "Transcript · evidence-linked chunks"
                else -> "Prompt · normalized text"
            }
        replaceSourceSummary(source.repository ?: source.displayName, details)
        element("secrets-count").textContent = source.secretsRedacted.toString()
        element("pii-count").textContent = source.piiWarnings.toString()
    }

    private fun replaceSourceSummary(name: String, meta: String) {
        val root = element("source-summary")
        root.textContent = ""
        root.appendChild(create("strong", name))
        root.appendChild(create("span", meta))
    }

    private fun renderQuality(
        result: WorkbenchResult.Ready,
        ir: DiagramIR,
        layoutAcceptable: Boolean
    ) {
        val coverage = result.version.evidenceCoverage
        val average = ((coverage.nodeCoverage + coverage.edgeCoverage) / 2.0).coerceIn(0.0, 1.0)
        val percent = (average * 100).roundToInt()
        val validation = result.version.validationReport
        val reviewStatus = artifactReviewStatus(coverage, validation)
        val blocked = reviewStatus == ArtifactReviewStatus.BLOCKED
        val needsReview = reviewStatus == ArtifactReviewStatus.REVIEW
        element("coverage-score").textContent = "$percent%"
        setCoverage("node", coverage.nodeCoverage)
        setCoverage("edge", coverage.edgeCoverage)
        element("review-status").textContent =
            when {
                blocked -> "Blocked"
                needsReview -> "Review"
                else -> "Ready"
            }
        element("quality-title").textContent =
            when {
                blocked -> "Validation issues block publishing"
                coverage.unsupportedCriticalClaims > 0 -> "Critical claims need evidence"
                needsReview -> "Human review recommended"
                !layoutAcceptable -> "Layout needs a quick review"
                else -> "Evidence coverage looks healthy"
            }
        element("quality-copy").textContent =
            when {
                blocked ->
                    "${validation.violations.size} server validation issue(s) must be " +
                        "resolved before publishing."
                coverage.unsupportedCriticalClaims > 0 ->
                    "${coverage.unsupportedCriticalClaims} unsupported critical claim(s) " +
                        "must be resolved before publishing."
                needsReview ->
                    "Inspect inferred relationships and low-confidence facts " +
                        "before sharing this artifact."
                !layoutAcceptable ->
                    "The facts are grounded, but the automatic layout reported " +
                        "a readability warning."
                else ->
                    "${countLabel(ir.nodes.size, "node")} and " +
                        "${countLabel(ir.edges.size, "edge")} are ready " +
                        "for human confirmation."
            }
    }

    private fun setCoverage(prefix: String, value: Double) {
        val percent = (value.coerceIn(0.0, 1.0) * 100).roundToInt()
        element("$prefix-coverage-label").textContent = "$percent%"
        element("$prefix-coverage-bar").setAttribute("style", "width:$percent%")
    }

    private fun renderFacts(ir: DiagramIR) {
        val list = element("fact-list")
        list.textContent = ""
        val nodes = ir.nodes.take(MAX_INSPECTOR_ITEMS)
        element("fact-count").textContent = ir.nodes.size.toString()
        if (nodes.isEmpty()) {
            list.appendChild(create("p", "No supported facts were extracted.", "empty-copy"))
            return
        }
        nodes.forEach { node ->
            val item = create("article", cls = "fact-item")
            item.appendChild(create("strong", node.label))
            val evidence = node.evidence.firstOrNull()
            val summary =
                node.summary?.takeIf {
                    it.isNotBlank()
                } ?: node.type.name.lowercase().replace('_', ' ')
            item.appendChild(create("span", summary))
            val confidence = (node.confidence.coerceIn(0.0, 1.0) * 100).roundToInt()
            item.appendChild(
                create("small", "$confidence% confidence · ${evidenceLabel(evidence)}")
            )
            list.appendChild(item)
        }
    }

    private fun evidenceLabel(evidence: EvidenceRef?): String {
        if (evidence == null) return "inferred, no direct citation"
        val path = evidence.path
        val line = evidence.startLine
        val speaker = evidence.speaker
        val startMs = evidence.startMs
        val page = evidence.startPage
        val location =
            when {
                path != null && line != null -> "$path:$line"
                speaker != null && startMs != null -> "$speaker · ${formatTimestamp(startMs)}"
                page != null -> "page $page"
                path != null -> path
                else -> "source chunk"
            }
        return location
    }

    private fun renderWarnings(
        ir: DiagramIR,
        validation: com.potaty.workbench.ValidationReportDto
    ) {
        val list = element("warning-list")
        list.textContent = ""
        val warnings =
            (
                validation.violations.map { it.rule to it.message } +
                    validation.warnings.map { it.rule to it.message } +
                    ir.warnings.map { it.code to it.message } +
                    ir.unsupportedClaims.map { it.severity.name to it.claim }
                ).distinct()
        element("warning-count").textContent = warnings.size.toString()
        if (warnings.isEmpty()) {
            list.appendChild(create("p", "No unsupported claims were reported.", "empty-copy"))
            return
        }
        warnings.take(MAX_INSPECTOR_ITEMS).forEach { (code, message) ->
            val item = create("article", cls = "warning-item")
            item.appendChild(create("strong", code.replace('_', ' ').lowercase()))
            item.appendChild(create("span", message))
            list.appendChild(item)
        }
    }

    private fun resetQualityPanel() {
        element("coverage-score").textContent = "—"
        setCoverage("node", 0.0)
        setCoverage("edge", 0.0)
        element("node-coverage-label").textContent = "—"
        element("edge-coverage-label").textContent = "—"
        element("quality-title").textContent = "Generating…"
        element("quality-copy").textContent = "Coverage and unsupported claims will appear here."
        element("review-status").textContent = "Review"
        element("fact-count").textContent = "0"
        element("warning-count").textContent = "0"
        element("fact-list").textContent = ""
        element("warning-list").textContent = ""
        element("warning-list").appendChild(create("p", "No warnings yet.", "empty-copy"))
        element("secrets-count").textContent = "0"
        element("pii-count").textContent = "0"
    }

    private fun resetStages() {
        val stages = document.querySelectorAll("#stage-list li")
        for (index in 0 until stages.length) {
            val stage = stages.item(index) as? HTMLElement ?: continue
            stage.classList.remove("is-active", "is-complete")
        }
        markStage("ingest")
    }

    private fun markStage(current: String) {
        val order = listOf("ingest", "extract", "plan", "render")
        val activeIndex = order.indexOf(current).coerceAtLeast(0)
        order.forEachIndexed { index, name ->
            val stage =
                document.querySelector("#stage-list [data-stage='$name']") as? HTMLElement
                    ?: return@forEachIndexed
            toggleClass(stage, "is-complete", index < activeIndex)
            toggleClass(stage, "is-active", index == activeIndex)
        }
    }

    private fun completeAllStages() {
        val stages = document.querySelectorAll("#stage-list li")
        for (index in 0 until stages.length) {
            val stage = stages.item(index) as? HTMLElement ?: continue
            stage.classList.remove("is-active")
            stage.classList.add("is-complete")
        }
    }

    private fun setOutputFormat(format: String) {
        activeFormat = if (format == "mermaid") "mermaid" else "ascii"
        val buttons = document.querySelectorAll("[data-format]")
        for (index in 0 until buttons.length) {
            val button = buttons.item(index) as? HTMLElement ?: continue
            val active = button.getAttribute("data-format") == activeFormat
            toggleClass(button, "is-active", active)
            button.setAttribute("aria-selected", active.toString())
            button.setAttribute("tabindex", if (active) "0" else "-1")
        }
        diagramOutput.textContent = if (activeFormat == "ascii") asciiText else mermaidText
        diagramOutput.setAttribute(
            "aria-labelledby",
            if (activeFormat == "ascii") "format-ascii" else "format-mermaid"
        )
        element("diagram-paper").setAttribute("data-format", activeFormat)
    }

    private fun copyOutput() {
        val text = currentOutput()
        if (text.isBlank()) {
            showToast("There is no finished output to copy yet.", error = true)
            return
        }
        val clipboard = window.navigator.asDynamic().clipboard
        if (clipboard != null) {
            clipboard
                .writeText(text)
                .then(
                    { showToast("${activeFormat.uppercase()} copied to the clipboard.") },
                    { fallbackCopy(text) }
                )
        } else {
            fallbackCopy(text)
        }
    }

    private fun fallbackCopy(text: String) {
        val area = document.createElement("textarea") as HTMLTextAreaElement
        area.value = text
        area.setAttribute("readonly", "")
        area.setAttribute("style", "position:fixed;left:-9999px;top:0")
        document.body?.appendChild(area)
        area.select()
        val copied = runCatching {
            document.asDynamic().execCommand("copy") as Boolean
        }.getOrDefault(false)
        area.remove()
        showToast(
            if (copied) {
                "Output copied to the clipboard."
            } else {
                "Select the diagram and copy it manually."
            },
            !copied
        )
    }

    private fun downloadOutput() {
        val text = currentOutput()
        if (text.isBlank()) {
            showToast("There is no finished output to download yet.", error = true)
            return
        }
        val extension = if (activeFormat == "ascii") "txt" else "mmd"
        val blob = Blob(arrayOf(text))
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = URL.createObjectURL(blob)
        link.download =
            "${safeFilename(element("workbench-title").textContent ?: "potaty-diagram")}.$extension"
        link.style.display = "none"
        document.body?.appendChild(link)
        link.click()
        link.remove()
        window.setTimeout({ URL.revokeObjectURL(link.href) }, 1_000)
        showToast("Downloaded ${link.download}.")
    }

    private fun insertIntoEditor() {
        val ir = lastIr
        if (ir == null) {
            showToast("Generate a valid diagram before opening the editor.", error = true)
            return
        }
        if (onInsertDiagram != null) {
            onInsertDiagram.invoke(ir)
            showStudio(false)
            showToast("Diagram inserted into the manual editor.")
        } else {
            copyOutput()
            showStudio(false)
        }
    }

    private fun currentOutput(): String = if (activeFormat == "ascii") asciiText else mermaidText

    private fun setZoom(value: Int) {
        zoomPercent = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
        element("zoom-value").textContent = "$zoomPercent%"
        diagramOutput.style.fontSize = "${13.0 * zoomPercent / 100.0}px"
    }

    private fun backToInput() {
        val stopping = invalidateActiveGeneration()
        stopElapsedTimer()
        generateButton.disabled = generationRuns.isActive
        setHidden(workbench, true)
        setHidden(inputView, false)
        document.title = "Potaty — source-grounded ASCII diagrams"
        if (stopping) {
            showComposerMessage(
                "Stopping the current generation safely. Retry will unlock when the in-flight " +
                    "request settles.",
                false
            )
        }
        window.setTimeout(
            {
                when (mode) {
                    SourceMode.PROMPT -> promptInput.focus()
                    SourceMode.TRANSCRIPT -> transcriptInput.focus()
                    SourceMode.GITHUB -> githubInput.focus()
                }
            },
            0
        )
    }

    private fun showStudio(show: Boolean) {
        toggleClass(studio, "is-hidden", !show)
        toggleClass(editorShell, "is-active", !show)
        toggleClass(editorShell, "is-studio-covered", show)
        studio.setAttribute("aria-hidden", (!show).toString())
        editorShell.setAttribute("aria-hidden", show.toString())
        if (show) {
            editorShell.setAttribute("inert", "")
            studio.removeAttribute("inert")
            generateButton.disabled = generationRuns.isActive
            document.body?.style?.setProperty("overflow", "hidden")
        } else {
            invalidateActiveGeneration()
            stopElapsedTimer()
            // Do not let a new run adopt this attempt's key until the old coroutine has fully
            // unwound. Its abort/terminal outcome decides whether that key is retained or rotated.
            generateButton.disabled = generationRuns.isActive
            studio.setAttribute("inert", "")
            editorShell.removeAttribute("inert")
            document.body?.style?.setProperty("overflow", "hidden")
        }
    }

    private fun invalidateActiveGeneration(): Boolean {
        generationId += 1
        val wasActive = generationRuns.isActive
        if (wasActive) activeTransport?.abortInFlight()
        return wasActive
    }

    private fun openSettings() {
        lastFocusedElement = document.activeElement as? HTMLElement
        loadSettingsIntoForm()
        val layer = element("settings-layer")
        studio.setAttribute("inert", "")
        studio.setAttribute("aria-hidden", "true")
        setHidden(layer, false)
        layer.setAttribute("aria-hidden", "false")
        input("settings-api-url").focus()
    }

    private fun closeSettings() {
        val layer = element("settings-layer")
        setHidden(layer, true)
        layer.setAttribute("aria-hidden", "true")
        studio.removeAttribute("inert")
        studio.setAttribute("aria-hidden", "false")
        lastFocusedElement?.focus()
    }

    private fun loadSettingsIntoForm() {
        val settings = currentSettings()
        input("settings-api-url").value = settings.apiBaseUrl
        input("settings-token").value = settings.accessToken
        input("settings-project-id").value = settings.projectId
        input("settings-github-url").value = settings.githubInstallUrl
    }

    private fun saveSettings() {
        val apiUrl = normalizeApiBaseUrl(input("settings-api-url").value)
        val token = input("settings-token").value.trim()
        val projectId = input("settings-project-id").value.trim()
        val githubUrl = input("settings-github-url").value.trim()
        val error =
            when {
                !isAllowedApiUrl(apiUrl) ->
                    "Use an HTTPS API URL. Plain HTTP is allowed only for localhost development."
                token.isBlank() ->
                    "Enter an access token. Development and production tokens " +
                        "stay only in this page's memory."
                !isCanonicalProjectId(projectId) -> "Project ID must be a valid UUID."
                githubUrl.isNotBlank() && !githubUrl.startsWith("https://github.com/") ->
                    "GitHub App install URL must use https://github.com/."
                else -> null
            }
        if (error != null) {
            showToast(error, error = true)
            return
        }
        runtimeAccessToken = token
        val storage = window.sessionStorage
        storage.setItem(KEY_API_URL, apiUrl)
        storage.removeItem(KEY_LEGACY_ACCESS_TOKEN)
        storage.setItem(KEY_PROJECT_ID, projectId)
        storage.setItem(KEY_GITHUB_URL, githubUrl)
        updateConnectionState()
        closeSettings()
        showToast("Settings updated. The access token will be cleared on reload.")
    }

    private fun resetSettings() {
        runtimeAccessToken = ""
        val storage = window.sessionStorage
        listOf(KEY_API_URL, KEY_LEGACY_ACCESS_TOKEN, KEY_PROJECT_ID, KEY_GITHUB_URL).forEach {
            storage.removeItem(it)
        }
        loadSettingsIntoForm()
        updateConnectionState()
        showToast("Session settings reset.")
    }

    private fun currentSettings(): RuntimeSettings {
        val storage = window.sessionStorage
        return RuntimeSettings(
            apiBaseUrl = storage.getItem(KEY_API_URL) ?: defaultApiUrl(),
            accessToken = runtimeAccessToken,
            projectId =
            storage.getItem(KEY_PROJECT_ID) ?: if (isLocalHost()) DEV_PROJECT_ID else "",
            githubInstallUrl = storage.getItem(KEY_GITHUB_URL).orEmpty()
        )
    }

    private fun purgeLegacyPersistedCredential() {
        // Older builds persisted this key in sessionStorage. Never hydrate it back into memory.
        runCatching { window.sessionStorage.removeItem(KEY_LEGACY_ACCESS_TOKEN) }
    }

    private fun updateConnectionState() {
        val settings = currentSettings()
        val configured = settings.accessToken.isNotBlank() && settings.projectId.isNotBlank()
        element("connection-label").textContent =
            if (configured) "API configured" else "Connection required"
        toggleClass(element("connection-state"), "needs-attention", !configured)
    }

    private fun connectGitHub() {
        val url = currentSettings().githubInstallUrl
        if (url.isBlank()) {
            showComposerMessage(
                "Add your GitHub App install URL in Runtime settings before " +
                    "connecting private repositories.",
                true
            )
            openSettings()
            return
        }
        window.open(url, "_blank", "noopener,noreferrer")
        showToast(
            "GitHub opened in a new tab. Return after choosing the repositories Potaty may read."
        )
    }

    private fun showComposerMessage(message: String, error: Boolean) {
        val target = element("composer-message")
        target.textContent = message
        setHidden(target, false)
        toggleClass(target, "is-error", error)
    }

    private fun hideComposerMessage() {
        setHidden(element("composer-message"), true)
    }

    private fun showToast(message: String, error: Boolean = false) {
        val target = element("toast-region")
        target.textContent = message
        target.classList.add("is-visible")
        toggleClass(target, "is-error", error)
        toastTimerId?.let(window::clearTimeout)
        toastTimerId =
            window.setTimeout(
                {
                    target.classList.remove("is-visible", "is-error")
                    target.textContent = ""
                },
                4_000
            )
    }

    private fun startElapsedTimer() {
        stopElapsedTimer()
        updateElapsed()
        elapsedTimerId = window.setInterval(::updateElapsed, 1_000)
    }

    private fun stopElapsedTimer() {
        elapsedTimerId?.let(window::clearInterval)
        elapsedTimerId = null
    }

    private fun updateElapsed() {
        val seconds = ((Date.now() - generationStartedAt) / 1_000.0).toInt().coerceAtLeast(0)
        element("progress-elapsed").textContent =
            "${twoDigits(seconds / 60)}:${twoDigits(
                seconds % 60
            )}"
    }

    private fun redactLikelyPii(text: String): String =
        text
            .replace(EMAIL_REGEX, "[EMAIL REDACTED]")
            .replace(PHONE_REGEX, "[PHONE REDACTED]")
            .replace(ACCOUNT_REGEX, "[ACCOUNT REDACTED]")

    private fun normalizeGitHubUrl(raw: String): String {
        val value = raw.trim().removeSuffix(".git").trimEnd('/')
        return when {
            value.startsWith("https://github.com/", ignoreCase = true) -> value
            value.startsWith("http://github.com/", ignoreCase = true) ->
                "https://" + value.substringAfter("http://")
            else -> "https://github.com/${value.removePrefix("github.com/")}"
        }
    }

    private fun isAllowedApiUrl(value: String): Boolean {
        val url = runCatching { URL(value) }.getOrNull() ?: return false
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.protocol == "https:") return true
        return url.protocol == "http:" &&
            url.hostname.lowercase() in setOf("localhost", "127.0.0.1")
    }

    private fun normalizeApiBaseUrl(value: String): String =
        value.trim().trimEnd('/').removeSuffix("/api/v1")

    private fun titleForPrompt(text: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return first.take(54).ifBlank { "Prompt diagram" }
    }

    private fun dimensionsLabel(text: String, ir: DiagramIR): String {
        val lines = text.lines()
        val columns = diagramCellWidth(text)
        val typeLabel = ir.diagramType.name.lowercase().replace('_', ' ')
        return "$columns × ${lines.size} cells · $typeLabel"
    }

    private fun formatTimestamp(milliseconds: Int): String {
        val seconds = (milliseconds / 1_000).coerceAtLeast(0)
        return "${twoDigits(seconds / 60)}:${twoDigits(seconds % 60)}"
    }

    private fun formatBytes(bytes: Double): String =
        when {
            bytes >= 1_048_576 -> "${oneDecimal(bytes / 1_048_576.0)} MB"
            bytes >= 1_024 -> "${oneDecimal(bytes / 1_024.0)} KB"
            else -> "${bytes.toInt()} B"
        }

    private fun formatCount(value: Int): String =
        value.toString().reversed().chunked(3).joinToString(",").reversed()

    private fun countLabel(count: Int, singular: String): String =
        "$count ${if (count == 1) singular else "${singular}s"}"

    private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

    private fun oneDecimal(value: Double): String {
        val rounded = (value * 10.0).roundToInt()
        return "${rounded / 10}.${kotlin.math.abs(rounded % 10)}"
    }

    private fun safeFilename(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9가-힣]+"), "-").trim('-').take(64).ifBlank {
            "potaty-diagram"
        }

    private fun defaultApiUrl(): String {
        val hostname =
            window.location.hostname.ifBlank { "localhost" }.let {
                if (it == "::1") "localhost" else it
            }
        val host = if (':' in hostname && !hostname.startsWith("[")) "[$hostname]" else hostname
        return "${window.location.protocol}//$host:$DEFAULT_BACKEND_PORT"
    }

    private fun isLocalHost(): Boolean =
        window.location.hostname in
            setOf(
                "",
                "localhost",
                "127.0.0.1",
                "::1"
            )

    private fun canonicalFingerprint(parts: List<String>): String = buildString {
        parts.forEach { part ->
            append(part.length).append(':').append(part)
        }
    }

    private suspend fun sha256Fingerprint(value: String): String =
        suspendCoroutine { continuation ->
            try {
                val crypto = window.asDynamic().crypto
                if (crypto == null || crypto.subtle == null) {
                    continuation.resumeWithException(
                        RuntimeException("secure browser cryptography unavailable")
                    )
                    return@suspendCoroutine
                }
                val promise =
                    crypto.subtle.digest(
                        "SHA-256",
                        TextEncoder().encode(value)
                    ) as Promise<dynamic>
                promise.then<Unit>(
                    { buffer ->
                        val bytes = Uint8Array(buffer)
                        val hex = uint8ArrayToHex(bytes.asDynamic())
                        continuation.resume("sha256:$hex")
                    },
                    { error ->
                        continuation.resumeWithException(
                            RuntimeException("request fingerprint failed: $error")
                        )
                    }
                )
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    private fun newIdempotencyKey(): String {
        val crypto = window.asDynamic().crypto
        check(crypto != null && jsTypeOf(crypto.getRandomValues) == "function") {
            "secure browser cryptography unavailable"
        }
        val uuid =
            if (jsTypeOf(crypto.randomUUID) == "function") {
                crypto.randomUUID() as String
            } else {
                val bytes = Uint8Array(16)
                crypto.getRandomValues(bytes)
                uuidV4FromRandomBytes(bytes.asDynamic())
            }
        return "gen-$uuid"
    }

    private fun create(tag: String, text: String = "", cls: String = ""): HTMLElement {
        val node = document.createElement(tag) as HTMLElement
        if (text.isNotEmpty()) node.textContent = text
        if (cls.isNotEmpty()) node.className = cls
        return node
    }

    private fun toggleClass(target: Element, name: String, enabled: Boolean) {
        if (enabled) target.classList.add(name) else target.classList.remove(name)
    }

    private fun setHidden(target: HTMLElement, hidden: Boolean) {
        if (hidden) target.setAttribute("hidden", "") else target.removeAttribute("hidden")
    }

    private fun isHidden(target: HTMLElement): Boolean =
        target.hasAttribute("hidden") || target.classList.contains("is-hidden")

    private fun element(id: String): HTMLElement =
        elementOrNull(id) ?: error("Missing Studio element #$id")

    private fun elementOrNull(id: String): HTMLElement? =
        document.getElementById(id) as? HTMLElement

    private fun input(id: String): HTMLInputElement =
        document.getElementById(id) as? HTMLInputElement ?: error("Missing Studio input #$id")

    private fun textArea(id: String): HTMLTextAreaElement =
        document.getElementById(id) as? HTMLTextAreaElement ?: error("Missing Studio textarea #$id")

    private fun select(id: String): HTMLSelectElement =
        document.getElementById(id) as? HTMLSelectElement ?: error("Missing Studio select #$id")

    private fun button(id: String): HTMLButtonElement =
        document.getElementById(id) as? HTMLButtonElement ?: error("Missing Studio button #$id")

    private fun pre(id: String): HTMLPreElement =
        document.getElementById(id) as? HTMLPreElement ?: error("Missing Studio pre #$id")

    private fun launch(block: suspend () -> Unit) {
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    result.exceptionOrNull()?.let {
                        console.error("Potaty Studio coroutine failed", it)
                    }
                }
            }
        )
    }

    private suspend fun sleepMs(ms: Long): Unit = suspendCoroutine { continuation ->
        window.setTimeout({ continuation.resume(Unit) }, ms.toInt())
    }

    companion object {
        private const val DEFAULT_BACKEND_PORT = "8090"
        private const val DEV_PROJECT_ID = "00000000-0000-0000-0000-000000000010"
        private const val MAX_SOURCE_CHARS = 500_000
        private const val MAX_TRANSCRIPT_BYTES = 2.0 * 1024.0 * 1024.0
        private const val MAX_INSPECTOR_ITEMS = 12
        private const val MAX_RESULT_TITLE_CHARS = 72
        private const val MIN_ZOOM = 70
        private const val MAX_ZOOM = 180
        private const val ZOOM_STEP = 10

        private const val KEY_API_URL = "potaty.runtime.apiUrl"
        private const val KEY_LEGACY_ACCESS_TOKEN = "potaty.runtime.accessToken"
        private const val KEY_PROJECT_ID = "potaty.runtime.projectId"
        private const val KEY_GITHUB_URL = "potaty.runtime.githubInstallUrl"

        private val ALLOWED_TRANSCRIPT_EXTENSIONS = setOf("txt", "md", "markdown", "log")
        private val GITHUB_REPO_REGEX =
            Regex("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
        private val EMAIL_REGEX =
            Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val PHONE_REGEX =
            Regex("(?<!\\d)(?:\\+?82[- .]?)?0?1[0-9][- .]?\\d{3,4}[- .]?\\d{4}(?!\\d)")
        private val ACCOUNT_REGEX = Regex("(?<!\\d)\\d{2,4}[- ]\\d{2,6}[- ]\\d{3,8}(?!\\d)")
    }
}

internal class GenerationRunGate {
    private var activeId: Int? = null

    val isActive: Boolean
        get() = activeId != null

    fun tryStart(id: Int): Boolean {
        if (activeId != null) return false
        activeId = id
        return true
    }

    fun finish(id: Int): Boolean {
        if (activeId != id) return false
        activeId = null
        return true
    }
}

internal class TranscriptReadGate {
    private var generation = 0

    fun begin(): Int = ++generation

    fun invalidate() {
        generation++
    }

    fun isCurrent(id: Int): Boolean = id == generation
}

private val CANONICAL_PROJECT_ID_REGEX =
    Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-" +
            "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

internal fun isCanonicalProjectId(value: String): Boolean =
    CANONICAL_PROJECT_ID_REGEX.matches(value)

internal fun diagramCellWidth(text: String): Int =
    text.lines().maxOfOrNull(DisplayCells::width) ?: 0

private external class TextEncoder {
    fun encode(input: String): dynamic
}

private external class Uint8Array(buffer: dynamic) {
    val length: Int
}

/**
 * Reads a native JavaScript Uint8Array through bracket indexing. Declaring an external Kotlin
 * `operator get` emits `bytes.get(index)`, but Uint8Array exposes no such method in browsers.
 */
internal fun uint8ArrayToHex(bytes: dynamic): String {
    val length = bytes.length as Int
    return buildString(length * 2) {
        for (index in 0 until length) {
            val byte = bytes[index] as Int
            append(byte.toString(16).padStart(2, '0'))
        }
    }
}

internal fun uuidV4FromRandomBytes(bytes: dynamic): String {
    require((bytes.length as Int) == 16) { "UUID v4 requires exactly 16 random bytes" }
    bytes[6] = ((bytes[6] as Int) and 0x0f) or 0x40
    bytes[8] = ((bytes[8] as Int) and 0x3f) or 0x80
    val hex = uint8ArrayToHex(bytes)
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

/** Tab-scoped persistence stores only SHA-256 request digests and opaque random keys. */
private class SessionWorkbenchRetryStore : WorkbenchRetryStateStore {
    override fun read(): WorkbenchRetryState? {
        val raw = window.sessionStorage.getItem(STORAGE_KEY) ?: return null
        val lines = raw.lines()
        require(lines.firstOrNull() == FORMAT_VERSION) { "unsupported retry state" }
        val sources = mutableListOf<WorkbenchRetrySourceKey>()
        val jobs = mutableListOf<WorkbenchRetryJobKey>()
        lines.drop(1).forEach { line ->
            val fields = line.split('\t')
            when {
                fields.size == 3 && fields[0] == "S" && valid(fields[1], fields[2]) ->
                    sources += WorkbenchRetrySourceKey(fields[1], fields[2])
                fields.size == 4 &&
                    fields[0] == "J" &&
                    DIGEST_PATTERN.matches(fields[1]) &&
                    valid(fields[2], fields[3]) ->
                    jobs += WorkbenchRetryJobKey(fields[1], fields[2], fields[3])
                else -> error("malformed retry state")
            }
        }
        return WorkbenchRetryState(sources, jobs)
    }

    override fun write(state: WorkbenchRetryState) {
        val encoded = buildString {
            append(FORMAT_VERSION)
            state.sources.forEach { source ->
                append("\nS\t").append(source.fingerprint).append('\t').append(source.key)
            }
            state.jobs.forEach { job ->
                append("\nJ\t").append(job.fingerprint)
                    .append('\t').append(job.sourceFingerprint)
                    .append('\t').append(job.key)
            }
        }
        window.sessionStorage.setItem(STORAGE_KEY, encoded)
    }

    private fun valid(digest: String, key: String): Boolean =
        DIGEST_PATTERN.matches(digest) && IDEMPOTENCY_KEY_PATTERN.matches(key)

    private companion object {
        const val STORAGE_KEY = "potaty.retry.idempotency.v1"
        const val FORMAT_VERSION = "v1"
        val DIGEST_PATTERN = Regex("^sha256:[0-9a-f]{64}$")
        val IDEMPOTENCY_KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
    }
}
