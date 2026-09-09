package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import io.prumo.mcp.ide.EmbeddingRuntime
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.workspace.application.WorkspaceResolution
import kotlinx.serialization.Serializable
import kotlin.coroutines.coroutineContext

/**
 * Primeira tool do Prumo, usada para confirmar que a integração com o MCP Server nativo está de pé
 * e que a resolução de projeto funciona antes de qualquer tool que exponha conteúdo.
 */
class PrumoDiagnosticsToolset : McpToolset {

    @Suppress("FunctionName")
    @McpTool
    @McpDescription(
        "Use this tool to check whether Prumo MCP is active and which open project the current " +
            "call resolves to. Prumo gives you the workspace this project belongs to — its " +
            "repositories, documentation and databases — and enforces its boundary. When it is " +
            "active, call prumo_workspace_prepare next.",
    )
    suspend fun prumo_diagnostics(): PrumoDiagnostics {
        val project = McpProjectResolver.resolve(coroutineContext)
        val resolution = PrumoWorkspaceService.getInstance().resolve(project)
        return PrumoDiagnostics(
            pluginVersion = installedVersion(),
            projectName = project.name,
            workspaceConfigured = resolution is WorkspaceResolution.Resolved,
            embeddingRuntimeAvailable = EmbeddingRuntime.available,
        )
    }

    /**
     * Versão lida do descritor instalado, e não de uma constante no código.
     *
     * Esta tool existe para dizer o que está rodando. Uma constante escrita à mão envelhece na
     * primeira release e passa a mentir justamente para quem tenta descobrir se a correção chegou.
     */
    private fun installedVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: "unknown"

    private companion object {
        const val PLUGIN_ID = "io.prumo.mcp"
    }
}

@Serializable
data class PrumoDiagnostics(
    val pluginVersion: String,
    val projectName: String,
    /** Verdadeiro apenas quando o projeto aberto resolve para exatamente um workspace do Prumo. */
    val workspaceConfigured: Boolean,
    /**
     * Se o motor de inferência local carregou nesta instalação.
     *
     * Falso não impede nada: a busca na documentação continua por palavra, e a resposta dela declara
     * que a parte semântica não está disponível.
     */
    val embeddingRuntimeAvailable: Boolean = false,
)
