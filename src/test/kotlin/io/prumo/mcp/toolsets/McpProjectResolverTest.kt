package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpExpectedError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext

class McpProjectResolverTest {

    @Test
    fun `falha explicitamente quando a chamada nao carrega projeto`() {
        val error = assertThrows(McpExpectedError::class.java) {
            McpProjectResolver.resolve(EmptyCoroutineContext)
        }

        assertEquals(McpProjectResolver.NO_PROJECT_RESOLVED, error.mcpErrorText)
    }

    @Test
    fun `a mensagem de erro orienta o usuario sem expor caminho de maquina`() {
        val message = McpProjectResolver.NO_PROJECT_RESOLVED

        assertEquals(false, message.contains(":\\"), "mensagem nao deve conter caminho Windows")
        assertEquals(false, message.contains("/home/"), "mensagem nao deve conter caminho Linux")
        assertEquals(true, message.contains("Open the project"), "mensagem deve dizer o que fazer")
    }
}
