package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
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
        "Reports whether Prumo MCP is active and which open project the current call resolves to. " +
            "Use it to confirm connectivity before calling any other Prumo tool.",
    )
    suspend fun prumo_diagnostics(): PrumoDiagnostics {
        val project = McpProjectResolver.resolve(coroutineContext)
        val resolution = PrumoWorkspaceService.getInstance().resolve(project)
        return PrumoDiagnostics(
            pluginVersion = PLUGIN_VERSION,
            projectName = project.name,
            workspaceConfigured = resolution is WorkspaceResolution.Resolved,
        )
    }

    private companion object {
        const val PLUGIN_VERSION = "0.1.0"
    }
}

@Serializable
data class PrumoDiagnostics(
    val pluginVersion: String,
    val projectName: String,
    /** Verdadeiro apenas quando o projeto aberto resolve para exatamente um workspace do Prumo. */
    val workspaceConfigured: Boolean,
)
