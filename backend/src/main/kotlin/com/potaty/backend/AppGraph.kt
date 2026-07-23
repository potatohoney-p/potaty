/*
 * Copyright (c) 2026, Potaty
 *
 * Composition root. Builds the whole object graph from an [AppConfig]: persistence (H2 or
 * Postgres), tenant-scoped repositories, session store, credential store, LLM providers, job
 * queue + worker pool, the text->IR pipeline, cost/usage/quota, observability + retention, and
 * (when configured) GitHub read-only indexing + PR publishing and audio transcription. The HTTP
 * layer and tests both build the graph and read its wired collaborators — no global singletons.
 */

@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.potaty.backend

import com.potaty.backend.auth.InMemorySessionStore
import com.potaty.backend.auth.JwtSessionStore
import com.potaty.backend.auth.MembershipBoundSessionStore
import com.potaty.backend.auth.SessionStore
import com.potaty.backend.auth.TenantContext
import com.potaty.backend.auth.WorkspaceRole
import com.potaty.backend.config.AppConfig
import com.potaty.backend.config.ModelTier
import com.potaty.backend.cost.CostConfig
import com.potaty.backend.cost.CostEstimator
import com.potaty.backend.cost.QuotaGuard
import com.potaty.backend.diagram.DiagramPipeline
import com.potaty.backend.diagram.diagramJobPipeline
import com.potaty.backend.github.GitHubAppService
import com.potaty.backend.github.GitHubConnectStateRepository
import com.potaty.backend.github.GitHubConnectionRepository
import com.potaty.backend.github.GitHubConnectionService
import com.potaty.backend.github.GitHubIndexer
import com.potaty.backend.github.GitHubPublisher
import com.potaty.backend.github.WebhookVerifier
import com.potaty.backend.jobs.ExposedJobQueue
import com.potaty.backend.jobs.JobQueue
import com.potaty.backend.jobs.JobWorkerPool
import com.potaty.backend.jobs.PostgresJobQueue
import com.potaty.backend.llm.LlmDiagramEnricher
import com.potaty.backend.llm.auth.ApiKeyCredential
import com.potaty.backend.llm.auth.EnvelopeCredentialStore
import com.potaty.backend.llm.provider.ModelRouter
import com.potaty.backend.llm.provider.ProviderFactory
import com.potaty.backend.llm.provider.ProviderId
import com.potaty.backend.observability.Metrics
import com.potaty.backend.ops.RetentionService
import com.potaty.backend.persistence.Database
import com.potaty.backend.persistence.IdentityRepository
import com.potaty.backend.persistence.TransactionContext
import com.potaty.backend.persistence.bootstrapDevelopmentIdentity
import com.potaty.backend.persistence.repositories.DiagramRepository
import com.potaty.backend.persistence.repositories.ExtractionRepository
import com.potaty.backend.persistence.repositories.JobRepository
import com.potaty.backend.persistence.repositories.LlmCredentialRepository
import com.potaty.backend.persistence.repositories.SourceRepository
import com.potaty.backend.transcription.AudioTranscriptionService
import com.potaty.backend.transcription.TranscriptIngestor
import com.potaty.backend.transcription.TranscriptionCompleter
import com.potaty.backend.transcription.TranscriptionCompletionService
import com.potaty.backend.transcription.TranscriptionCredentialResolver
import com.potaty.backend.usage.UsageRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AppGraph
private constructor(
    val config: AppConfig,
    private val database: Database,
    val json: Json,
    val sessionStore: SessionStore,
    val identities: IdentityRepository,
    val sources: SourceRepository,
    val extraction: ExtractionRepository,
    val diagrams: DiagramRepository,
    val jobs: JobRepository,
    val usage: UsageRecorder,
    val costConfig: CostConfig,
    val costEstimator: CostEstimator,
    val quotaGuard: QuotaGuard,
    val metrics: Metrics,
    val retention: RetentionService,
    val jobQueue: JobQueue,
    val diagramPipeline: DiagramPipeline,
    val providerFactory: ProviderFactory,
    val workerPool: JobWorkerPool,
    // WS9/WS11 GitHub: present only when an App id + private key are configured (null on H2/dev).
    val webhookVerifier: WebhookVerifier?,
    val gitHubAppService: GitHubAppService?,
    val gitHubIndexer: GitHubIndexer?,
    val gitHubPublisher: GitHubPublisher?,
    val gitHubConnections: GitHubConnectionRepository,
    val gitHubConnectStates: GitHubConnectStateRepository,
    val gitHubConnectionService: GitHubConnectionService?,
    // WS12 audio: service null when no transcription model is configured; ingestor always present.
    val audioTranscriptionService: AudioTranscriptionService?,
    val transcriptionCredentialResolver: TranscriptionCredentialResolver,
    val transcriptIngestor: TranscriptIngestor,
    internal val transcriptionCompleter: TranscriptionCompleter,
    val transcriptionModel: String?,
    private val outboundHttpClient: HttpClient
) {
    private val log = LoggerFactory.getLogger(AppGraph::class.java)

    fun isReady(): Boolean = database.isReady()

    fun start() {
        workerPool.start()
        log.info(
            "AppGraph started (db mode='{}', dev auth={})",
            config.database.mode,
            config.auth.devAuthEnabled
        )
    }

    fun stop() {
        workerPool.stop()
        outboundHttpClient.close()
        database.close()
    }

    companion object {
        fun create(config: AppConfig): AppGraph {
            val json = Json {
                encodeDefaults = true
                explicitNulls = false
                ignoreUnknownKeys = true
            }

            val database = Database.connect(config.database)
            val txc = TransactionContext(database.exposed)

            // The deterministic local workspace/project exists only when development auth was
            // explicitly selected. JWT/production mode never seeds identities.
            if (config.auth.devAuthEnabled) {
                try {
                    kotlinx.coroutines.runBlocking {
                        bootstrapDevelopmentIdentity(txc, config.auth)
                    }
                } catch (cause: Throwable) {
                    database.close()
                    throw cause
                }
            }

            val identities = IdentityRepository(txc)
            val sources = SourceRepository(txc, identities)
            val extraction = ExtractionRepository(txc, json, identities)
            val diagrams = DiagramRepository(txc, identities)
            val jobs = JobRepository(txc, identities)
            val llmCredentials = LlmCredentialRepository(txc)
            val gitHubConnections = GitHubConnectionRepository(txc)
            val gitHubConnectStates = GitHubConnectStateRepository(txc)

            // WS6 cost/usage/quota; WS15 observability + retention.
            val usage = UsageRecorder(txc)
            val costConfig = CostConfig.fromEnv()
            val costEstimator = CostEstimator()
            val quotaGuard = QuotaGuard(txc, costConfig)
            val metrics = Metrics()
            val retention = RetentionService(txc)

            val sessionStore: SessionStore =
                if (config.auth.devAuthEnabled) {
                    InMemorySessionStore().also { store ->
                        store.seed(
                            config.auth.devToken,
                            TenantContext(
                                workspaceId = config.auth.devWorkspaceId,
                                userId = config.auth.devUserId,
                                role = WorkspaceRole.OWNER
                            )
                        )
                    }
                } else {
                    MembershipBoundSessionStore(JwtSessionStore(config.auth)) { context ->
                        val workspaceId = runCatching {
                            java.util.UUID.fromString(context.workspaceId)
                        }.getOrNull()
                        val userId = runCatching {
                            java.util.UUID.fromString(context.userId)
                        }.getOrNull()
                        workspaceId != null &&
                            userId != null &&
                            identities.activeMemberRole(workspaceId, userId) == context.role
                    }
                }

            val credentialStore = EnvelopeCredentialStore(config.security.credentialMasterKeyRef)
            val outboundHttpClient =
                HttpClient(CIO) {
                    install(HttpTimeout) {
                        connectTimeoutMillis = 10_000
                        requestTimeoutMillis = 180_000
                        socketTimeoutMillis = 180_000
                    }
                }
            val providerFactory =
                ProviderFactory(
                    config.llm,
                    credentialStore,
                    outboundHttpClient,
                    config.security.allowProviderOAuth
                )

            // H2/dev uses the dialect-agnostic queue; Postgres uses FOR UPDATE SKIP LOCKED.
            val jobQueue: JobQueue =
                if (config.database.isH2) {
                    ExposedJobQueue(txc)
                } else {
                    PostgresJobQueue(database.dataSource)
                }

            val deploymentOpenAiKey =
                System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

            // LLM summarisation (plan 2.3): enabled when OPENAI_API_KEY is set in the environment.
            // Resolve and seal the deployment key just-in-time against the authenticated
            // workspace. This works in both dev and JWT modes without persisting plaintext or
            // binding a production request to the development bootstrap tenant.
            val llmEnricher: LlmDiagramEnricher? = run {
                if (deploymentOpenAiKey == null) {
                    null
                } else {
                    val model =
                        ModelRouter(config.llm)
                            .resolve(
                                ModelTier.CHEAP_STRUCTURED,
                                ProviderId.OPENAI
                            ) ?: "gpt-4o-mini"
                    LlmDiagramEnricher(
                        provider = providerFactory.get(ProviderId.OPENAI),
                        model = model,
                        credentialResolver = { workspaceId ->
                            val ref = credentialStore.seal(workspaceId, deploymentOpenAiKey)
                            ApiKeyCredential(
                                id = "deployment-openai",
                                workspaceId = workspaceId,
                                provider = ProviderId.OPENAI,
                                encryptedApiKeyRef = ref.value,
                                label = "deployment OpenAI key",
                                createdByUserId = "deployment-operator"
                            )
                        }
                    )
                }
            }

            val diagramPipeline =
                DiagramPipeline(
                    sources = sources,
                    diagrams = diagrams,
                    identities = identities,
                    json = json,
                    enricher = llmEnricher,
                    usageRecorder = usage,
                    costEstimator = costEstimator
                )
            val workerPool =
                JobWorkerPool(
                    queue = jobQueue,
                    jobs = jobs,
                    pipelineFactory = { diagramJobPipeline(diagramPipeline, json) },
                    onTerminal = quotaGuard::releaseForJob
                )

            // WS9/WS11 GitHub — built only when credentials are present.
            val webhookVerifier: WebhookVerifier? =
                if (config.github.webhookSecret.isNotBlank()) {
                    WebhookVerifier(config.github.webhookSecret)
                } else {
                    null
                }
            val gitHubAppService: GitHubAppService? =
                if (config.github.enabled) {
                    GitHubAppService(config.github, outboundHttpClient)
                } else {
                    null
                }
            // The indexer is ALWAYS available: it can index PUBLIC repos anonymously (no App token)
            // via the /github/index-url route. The App-token /github/index route still requires
            // gitHubAppService (private repos), which stays null until an App is configured.
            val gitHubIndexer: GitHubIndexer? =
                GitHubIndexer(config.github, outboundHttpClient, sources)
            val gitHubPublisher: GitHubPublisher? =
                if (config.github.enabled) {
                    GitHubPublisher(config.github, outboundHttpClient)
                } else {
                    null
                }
            val gitHubConnectionService: GitHubConnectionService? =
                if (config.github.connectEnabled) {
                    GitHubConnectionService(
                        config = config.github,
                        connections = gitHubConnections,
                        states = gitHubConnectStates,
                        identities = identities,
                        httpClient = outboundHttpClient
                    )
                } else {
                    null
                }

            // WS12 audio transcription (OpenAI multipart) — service built only when a model is set.
            val transcriptionModel: String? =
                config.llm.routes.forTier(ModelTier.TRANSCRIPTION).openai
            val audioTranscriptionService: AudioTranscriptionService? =
                if (!transcriptionModel.isNullOrBlank()) {
                    AudioTranscriptionService(
                        config.llm.openAiBaseUrl,
                        outboundHttpClient,
                        credentialStore
                    )
                } else {
                    null
                }
            val transcriptionCredentialResolver =
                TranscriptionCredentialResolver { workspaceId, credentialId ->
                    if (
                        credentialId == DEPLOYMENT_OPENAI_CREDENTIAL_ID &&
                        deploymentOpenAiKey != null
                    ) {
                        val workspace = workspaceId.toString()
                        val ref = credentialStore.seal(workspace, deploymentOpenAiKey)
                        ApiKeyCredential(
                            id = DEPLOYMENT_OPENAI_CREDENTIAL_ID,
                            workspaceId = workspace,
                            provider = ProviderId.OPENAI,
                            encryptedApiKeyRef = ref.value,
                            label = "deployment OpenAI key",
                            createdByUserId = "deployment-operator"
                        )
                    } else {
                        credentialId.toUuidOrNull()?.let { id ->
                            llmCredentials.findActiveApiKey(
                                workspaceId,
                                id,
                                ProviderId.OPENAI
                            )
                        }
                    }
                }
            val transcriptIngestor = TranscriptIngestor(sources)
            val transcriptionCompleter =
                TranscriptionCompletionService(txc, sources, transcriptIngestor, usage, json)

            return AppGraph(
                config = config,
                database = database,
                json = json,
                sessionStore = sessionStore,
                identities = identities,
                sources = sources,
                extraction = extraction,
                diagrams = diagrams,
                jobs = jobs,
                usage = usage,
                costConfig = costConfig,
                costEstimator = costEstimator,
                quotaGuard = quotaGuard,
                metrics = metrics,
                retention = retention,
                jobQueue = jobQueue,
                diagramPipeline = diagramPipeline,
                providerFactory = providerFactory,
                workerPool = workerPool,
                webhookVerifier = webhookVerifier,
                gitHubAppService = gitHubAppService,
                gitHubIndexer = gitHubIndexer,
                gitHubPublisher = gitHubPublisher,
                gitHubConnections = gitHubConnections,
                gitHubConnectStates = gitHubConnectStates,
                gitHubConnectionService = gitHubConnectionService,
                audioTranscriptionService = audioTranscriptionService,
                transcriptionCredentialResolver = transcriptionCredentialResolver,
                transcriptIngestor = transcriptIngestor,
                transcriptionCompleter = transcriptionCompleter,
                transcriptionModel = transcriptionModel,
                outboundHttpClient = outboundHttpClient
            )
        }
    }
}

private const val DEPLOYMENT_OPENAI_CREDENTIAL_ID = "deployment-openai"

private fun String.toUuidOrNull(): java.util.UUID? =
    runCatching { java.util.UUID.fromString(this) }.getOrNull()
