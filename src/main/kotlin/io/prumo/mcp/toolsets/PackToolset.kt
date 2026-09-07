package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.pack.application.KnowledgeMatch
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.pack.execution.PackQueryRunner
import io.prumo.mcp.pack.execution.PackToolRunner
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.datasource.security.ObfuscationGuidance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class PackResponse(
    val packId: String,
    val version: String,
    val title: String,
    val description: String,
    val author: String? = null,
    val capabilities: List<String>,
    val knowledgeCount: Int,
    /** Sempre verdadeiro: um pack é recurso do usuário, não do produto. */
    val thirdParty: Boolean = true,
)

@Serializable
data class PackListResponse(
    val workspaceId: String,
    val packs: List<PackResponse>,
)

@Serializable
data class KnowledgeMatchResponse(
    val packId: String,
    val itemId: String,
    val title: String,
    val tags: List<String>,
    val line: Int,
    val excerpt: String,
)

@Serializable
data class KnowledgeSearchResponse(
    val workspaceId: String,
    val query: String,
    val matches: List<KnowledgeMatchResponse>,
    val truncated: Boolean,
)

@Serializable
data class PackToolRunResponse(
    val packId: String,
    val toolId: String,
    val kind: String,
    /** Sempre verdadeiro: quem responde por esta ferramenta é quem a instalou, não o Prumo. */
    val thirdParty: Boolean = true,
    val exitCode: Int? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val timedOut: Boolean = false,
    val columns: List<QueryColumnResponse> = emptyList(),
    val rows: List<List<String?>> = emptyList(),
    val rowCount: Int = 0,
    val truncated: Boolean = false,
    val durationMillis: Long = 0,
    /** Como trabalhar com o que veio. Ausente quando o resultado não tocou dado pessoal. */
    val guidance: String? = null,
)

@Serializable
data class KnowledgeContentResponse(
    val packId: String,
    val itemId: String,
    val title: String,
    val text: String,
    val truncated: Boolean,
)

/**
 * Respostas das tools de pack.
 *
 * O texto localizado é resolvido para o idioma que o cliente pediu, com queda para o inglês. O
 * cliente endereça conhecimento por `packId` e `itemId`, nunca por caminho de arquivo.
 */
object PackReports {

    const val MAX_MATCHES = 20
    const val MAX_KNOWLEDGE_LENGTH = 20_000

    fun list(workspaceId: String, packs: List<PackManifest>, language: String?): PackListResponse =
        PackListResponse(
            workspaceId = workspaceId,
            packs = packs.map { manifest ->
                PackResponse(
                    packId = manifest.id,
                    version = manifest.version,
                    title = manifest.title.forLanguage(language),
                    description = manifest.description.forLanguage(language),
                    author = manifest.author,
                    capabilities = manifest.capabilities.map { it.name }.sorted(),
                    knowledgeCount = manifest.knowledge.size,
                )
            },
        )

    fun search(workspaceId: String, query: String, matches: List<KnowledgeMatch>): KnowledgeSearchResponse =
        KnowledgeSearchResponse(
            workspaceId = workspaceId,
            query = query,
            matches = matches.map {
                KnowledgeMatchResponse(it.packId, it.itemId, it.title, it.tags, it.line, it.excerpt)
            },
            truncated = matches.size >= MAX_MATCHES,
        )

    fun content(packId: String, itemId: String, title: String, text: String): KnowledgeContentResponse =
        KnowledgeContentResponse(
            packId = packId,
            itemId = itemId,
            title = title,
            text = text.take(MAX_KNOWLEDGE_LENGTH),
            truncated = text.length > MAX_KNOWLEDGE_LENGTH,
        )
}

/**
 * Superfície MCP dos Prumo Packs instalados no workspace corrente.
 *
 * As respostas marcam `thirdParty`. A busca é literal — texto, título e etiquetas — sem embedding
 * nem ranqueamento por modelo.
 */
class PackToolset : McpToolset {

    @McpTool(name = LIST_TOOL)
    @McpDescription(
        "Lists the Prumo Packs installed in the current workspace, with the capabilities each one " +
            "declared. Packs are user-provided resources, not part of the product. A pack you have " +
            "submitted does not appear here until the developer accepts it in the IDE, so an empty " +
            "list right after a submission is expected — submitting again only queues a duplicate. " +
            "To write one, start from prumo_pack_get_authoring_spec.",
    )
    suspend fun list(
        @McpDescription("BCP-47 language tag for the pack titles, for example pt-BR. Defaults to English.")
        language: String? = null,
    ): PackListResponse =
        prumoToolCall(LIST_TOOL, "pack.list", PolicyAction.READ_DOCUMENTATION) { call ->
            val store = PackStore(PrumoWorkspaceService.getInstance().storage)
            val packs = withContext(Dispatchers.IO) { store.list(call.context.workspace.id) }
            PackReports.list(call.context.workspace.id, packs, language)
        }

    @McpTool(name = SEARCH_KNOWLEDGE_TOOL)
    @McpDescription(
        "Searches the knowledge of the installed packs by literal text, title and tags, and returns " +
            "where each match is. Deterministic: no embedding and no model ranking.",
    )
    suspend fun searchKnowledge(
        @McpDescription("Text to look for.")
        query: String,
        @McpDescription("Restrict the search to one pack id.")
        packId: String? = null,
    ): KnowledgeSearchResponse =
        prumoToolCall(SEARCH_KNOWLEDGE_TOOL, "pack.search_knowledge", PolicyAction.READ_DOCUMENTATION) { call ->
            val store = PackStore(PrumoWorkspaceService.getInstance().storage)
            val matches = withContext(Dispatchers.IO) {
                store.searchKnowledge(call.context.workspace.id, query, packId, PackReports.MAX_MATCHES)
            }
            call.auditDetails["matches"] = matches.size.toString()
            PackReports.search(call.context.workspace.id, query, matches)
        }

    @McpTool(name = GET_KNOWLEDGE_TOOL)
    @McpDescription(
        "Returns the full text of one knowledge item of an installed pack, addressed by pack id and " +
            "item id. File paths are never accepted from the client.",
    )
    suspend fun getKnowledge(
        @McpDescription("Pack id from prumo_pack_list.")
        packId: String,
        @McpDescription("Knowledge item id from prumo_pack_search_knowledge.")
        itemId: String,
        @McpDescription("BCP-47 language tag for the item title. Defaults to English.")
        language: String? = null,
    ): KnowledgeContentResponse =
        prumoToolCall(GET_KNOWLEDGE_TOOL, "pack.get_knowledge", PolicyAction.READ_DOCUMENTATION) { call ->
            val store = PackStore(PrumoWorkspaceService.getInstance().storage)
            val workspaceId = call.context.workspace.id
            withContext(Dispatchers.IO) {
                val manifest = store.load(workspaceId, packId)
                val title = manifest?.knowledge(itemId)?.title?.forLanguage(language).orEmpty()
                PackReports.content(packId, itemId, title, store.readKnowledge(workspaceId, packId, itemId))
            }
        }

    @McpTool(name = RUN_TOOL)
    @McpDescription(
        "Runs a tool of an installed pack: a saved read-only query or a confined script. The tool " +
            "was written by a third party and installed by the developer; it obeys the same limits " +
            "as the native tools — read-only transaction for queries, confinement and timeout for " +
            "scripts, and the workspace policy above both.",
    )
    suspend fun runTool(
        @McpDescription("Pack id from prumo_pack_list.")
        packId: String,
        @McpDescription("Tool id declared by the pack.")
        toolId: String,
        @McpDescription("For queries: how many rows to return at most.")
        maxRows: Int = 100,
    ): PackToolRunResponse =
        prumoToolCall(RUN_TOOL, "pack.run_tool", PolicyAction.READ_DOCUMENTATION) { call ->
            val service = PrumoWorkspaceService.getInstance()
            val workspace = call.context.workspace
            val manifest = PackStore(service.storage).load(workspace.id, packId)
                ?: throw io.prumo.mcp.pack.application.PackAccessException(
                    "Pack '$packId' is not installed in this workspace.",
                )
            val tool = manifest.tool(toolId)
                ?: throw io.prumo.mcp.pack.application.PackAccessException(
                    "Pack '$packId' has no tool '$toolId'.",
                )
            call.auditDetails["packId"] = packId

            withContext(Dispatchers.IO) {
                when (tool.kind) {
                    PackToolKind.QUERY -> {
                        val outcome = PackQueryRunner(service.storage, service.credentials)
                            .run(workspace, packId, toolId, maxRows, service.audit)
                        PackToolRunResponse(
                            packId = packId,
                            toolId = toolId,
                            kind = tool.kind.name,
                            columns = outcome.columns.map { QueryColumnResponse(it.name, it.type, it.masked, it.obfuscatedAs.name) },
                            guidance = ObfuscationGuidance.forResult(outcome),
                            rows = outcome.rows,
                            rowCount = outcome.rowCount,
                            truncated = outcome.truncated,
                            durationMillis = outcome.durationMillis,
                        )
                    }

                    PackToolKind.SCRIPT -> {
                        val result = PackToolRunner(service.storage)
                            .run(workspace.id, workspace.policies, packId, toolId, service.audit)
                        PackToolRunResponse(
                            packId = packId,
                            toolId = toolId,
                            kind = tool.kind.name,
                            exitCode = result.exitCode,
                            stdout = result.stdout,
                            stderr = result.stderr,
                            timedOut = result.timedOut,
                            truncated = result.truncated,
                            durationMillis = result.durationMillis,
                        )
                    }
                }
            }
        }

    private companion object {
        const val RUN_TOOL = "prumo_pack_run_tool"
        const val LIST_TOOL = "prumo_pack_list"
        const val SEARCH_KNOWLEDGE_TOOL = "prumo_pack_search_knowledge"
        const val GET_KNOWLEDGE_TOOL = "prumo_pack_get_knowledge"
    }
}
