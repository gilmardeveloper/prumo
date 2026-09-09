package io.prumo.mcp.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.prumo.mcp.ide.PrumoProjectContext
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.ide.SourceStampReader
import io.prumo.mcp.documentation.SupportedDocumentFormats
import io.prumo.mcp.ide.EmbeddingRuntime
import io.prumo.mcp.knowledge.freshnessOf
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.authoring.SubmissionQueue
import io.prumo.mcp.workspace.application.WorkspaceResolution

/**
 * Lê do disco o estado que a janela desenha.
 *
 * Resolver o workspace percorre todos os workspaces cadastrados e lê os arquivos de cada um, mais a
 * fila de submissões e os packs instalados. Isso não pode acontecer na thread de interface: a IDE
 * congela enquanto durar.
 */
object WorkspaceLoader {

    private val log = Logger.getInstance(WorkspaceLoader::class.java)

    /** Quantas chamadas recentes a aba de atividade mostra. A trilha inteira cresce sem limite. */
    private const val ACTIVITY_LIMIT = 200

    /**
     * Devolve o modelo da tela, ou [WorkspaceViewModel.Failed] quando o disco não pôde ser lido.
     *
     * Nunca lança: uma falha aqui deixaria a janela sem conteúdo nenhum, sem dizer por quê.
     */
    fun load(project: Project): WorkspaceViewModel = try {
        val service = PrumoWorkspaceService.getInstance()
        val resolution = PrumoProjectContext.getInstance(project).resolve()
        val workspaceId = (resolution as? WorkspaceResolution.Resolved)?.context?.workspace?.id

        WorkspaceViewModel.from(
            resolution = resolution,
            pendingPacks = workspaceId?.let { SubmissionQueue(service.storage).pending(it).size } ?: 0,
            installedPacks = workspaceId?.let { id ->
                PackStore(service.storage).list(id).map {
                    WorkspaceViewModel.PackRow(it.id, it.title.default, it.version)
                }
            }.orEmpty(),
            activity = workspaceId?.let { id ->
                service.audit.readLast(id, ACTIVITY_LIMIT).map {
                    WorkspaceViewModel.ActivityRow(
                        timestamp = it.timestamp,
                        tool = it.tool,
                        operation = it.operation,
                        result = it.result.name,
                        durationMillis = it.durationMillis,
                    )
                }
            }.orEmpty(),
            documentation = (resolution as? WorkspaceResolution.Resolved)?.context?.workspace?.documentation
                ?.map { source ->
                    val local = runCatching { java.nio.file.Path.of(source.location) }.getOrNull()
                    WorkspaceViewModel.DocumentationRow(
                        documentationId = source.id,
                        name = source.name,
                        readable = local != null &&
                            (java.nio.file.Files.isDirectory(local) || SupportedDocumentFormats.isSupported(local)),
                        indexedPassages = workspaceId?.let { service.documentIndex.countOf(it, source.id) } ?: 0,
                    )
                }
                .orEmpty(),
            semanticSearch = WorkspaceViewModel.SemanticSearchRow(
                runtimeAvailable = EmbeddingRuntime.available,
                installed = service.embeddingModels.state(service.embeddingModelDescriptor).installed,
                bytes = service.embeddingModels.state(service.embeddingModelDescriptor)
                    .takeIf { it.installed }
                    ?.sizeBytes
                    ?: service.embeddingModelDescriptor.totalBytes,
            ),
            memory = workspaceId?.let { id ->
                val context = (resolution as? WorkspaceResolution.Resolved)?.context
                service.knowledge.list(id).map { record ->
                    WorkspaceViewModel.MemoryRow(
                        knowledgeId = record.id,
                        title = record.title,
                        sourceId = record.provenance.sourceId,
                        freshness = freshnessOf(
                            record.provenance.stamp,
                            context?.let { SourceStampReader.stamp(it, record.provenance).stampOrNull },
                        ).name,
                        author = record.author,
                        updatedAt = record.updatedAt,
                        outOfReach = context?.let { SourceStampReader.outOfReach(it, record.provenance) } == true,
                    )
                }
            }.orEmpty(),
        )
    } catch (failure: Exception) {
        log.warn("Falha ao ler o estado do workspace para o projeto ${project.name}", failure)
        WorkspaceViewModel.Failed("toolwindow.loadFailed")
    }
}
