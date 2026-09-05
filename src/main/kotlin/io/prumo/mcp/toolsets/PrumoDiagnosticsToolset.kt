package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
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
        return PrumoDiagnostics(
            pluginVersion = PLUGIN_VERSION,
            projectName = project.name,
            workspaceConfigured = false,
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
    /** Falso enquanto a configuração de workspace não existir; passa a refletir o estado real em W7. */
    val workspaceConfigured: Boolean,
)
