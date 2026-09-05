package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.projectOrNull
import com.intellij.openapi.project.Project
import kotlin.coroutines.CoroutineContext

/**
 * Resolve o projeto ao qual uma chamada MCP se refere.
 *
 * O servidor MCP da IDE é único para toda a aplicação e atende vários projetos abertos ao mesmo
 * tempo, identificando o alvo por um parâmetro da chamada. Um projeto escolhido por conveniência
 * quando essa identificação falha significaria expor um workspace no lugar de outro, então a
 * ausência de projeto é sempre erro, nunca escolha implícita.
 */
internal object McpProjectResolver {

    fun resolve(callContext: CoroutineContext): Project {
        // Fora de uma chamada MCP o contexto nao carrega McpCallInfo, e projectOrNull lanca em vez
        // de devolver null: os dois casos precisam virar o mesmo erro esperado.
        if (callContext.mcpCallInfoOrNull == null) {
            throw McpExpectedError(NO_PROJECT_RESOLVED)
        }
        return callContext.projectOrNull ?: throw McpExpectedError(NO_PROJECT_RESOLVED)
    }

    const val NO_PROJECT_RESOLVED: String =
        "Prumo MCP could not determine which open project this call refers to. " +
            "Open the project in the IDE and retry, naming the project path explicitly " +
            "when more than one project is open."
}
