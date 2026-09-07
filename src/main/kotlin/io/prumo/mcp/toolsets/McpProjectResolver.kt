package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import io.prumo.mcp.client.ClientIdentity
import kotlin.coroutines.CoroutineContext

/**
 * Resolve o projeto ao qual uma chamada MCP se refere.
 *
 * O servidor MCP da IDE é único para toda a aplicação e atende vários projetos abertos, indicando o
 * alvo por um parâmetro da chamada. Ausência de projeto é erro, nunca escolha implícita.
 */
internal object McpProjectResolver {

    fun resolve(callContext: CoroutineContext): Project {
        // Fora de uma chamada MCP não há McpCallInfo, e projectOrNull lança em vez de devolver null.
        if (callContext.mcpCallInfoOrNull == null) {
            throw McpExpectedError(NO_PROJECT_RESOLVED)
        }
        return callContext.projectOrNull ?: throw McpExpectedError(NO_PROJECT_RESOLVED)
    }

    /**
     * Quem fez a chamada, conforme o cliente se declarou no `initialize`.
     *
     * Fora de uma chamada MCP, ou quando o cliente não se identifica, devolve
     * [ClientIdentity.UNKNOWN] — a ausência de identidade nunca impede a chamada, porque a
     * identidade serve para registrar, não para autorizar.
     */
    fun client(callContext: CoroutineContext): ClientIdentity {
        val info = callContext.mcpCallInfoOrNull?.clientInfo ?: return ClientIdentity.UNKNOWN
        return ClientIdentity.of(info.name, info.version)
    }

    const val NO_PROJECT_RESOLVED: String =
        "Prumo MCP could not determine which open project this call refers to. " +
            "Open the project in the IDE and retry, naming the project path explicitly " +
            "when more than one project is open."
}
