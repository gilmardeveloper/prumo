package io.prumo.mcp.toolsets

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A descrição de cada tool é a única superfície pela qual o Prumo se apresenta ao cliente de IA.
 *
 * O servidor MCP da IDE não transmite texto de apresentação, e as tools do Prumo chegam ao cliente
 * no fim de uma lista longa, disputando a mesma necessidade com as tools nativas. Uma descrição que
 * apenas declara o que a tool faz perde para uma que diz quando usá-la.
 */
@Tag("security")
class ToolDescriptionTest {

    private data class Tool(val name: String, val description: String)

    private val tools: List<Tool> = listOf(
        WorkspaceToolset::class.java,
        RepositoryToolset::class.java,
        IdeToolset::class.java,
        DatabaseToolset::class.java,
        PackToolset::class.java,
        PackAuthoringToolset::class.java,
    ).flatMap { toolset ->
        toolset.declaredMethods.mapNotNull { method ->
            val name = method.getAnnotation(McpTool::class.java)?.name ?: return@mapNotNull null
            Tool(name, method.getAnnotation(McpDescription::class.java)?.description.orEmpty())
        }
    }

    @Test
    fun `nenhuma descricao entrega o cliente a outra familia de ferramentas`() {
        val cedendo = tools.filter { tool ->
            CESSAO.any { tool.description.contains(it, ignoreCase = true) }
        }

        assertTrue(cedendo.isEmpty(), "descrição cedendo a preferência: ${cedendo.map { it.name }}")
    }

    @Test
    fun `as tools de leitura dizem quando devem ser usadas`() {
        val semInstrucao = tools
            .filter { it.name in INSTRUCTIVE }
            .filterNot { tool -> INSTRUCAO.any { tool.description.contains(it) } }

        assertTrue(semInstrucao.isEmpty(), "descrição sem instrução de uso: ${semInstrucao.map { it.name }}")
    }

    @Test
    fun `toda tool tem descricao`() {
        val vazias = tools.filter { it.description.isBlank() }

        assertTrue(vazias.isEmpty(), "tool sem descrição: ${vazias.map { it.name }}")
    }

    private companion object {
        /** Formulações que mandam o cliente procurar a resposta fora do Prumo. */
        val CESSAO = listOf(
            "already answer",
            "use the IDE's own",
            "the IDE's own project tools",
        )

        val INSTRUCAO = listOf("Use this tool", "Prefer it over", "Call it")

        /**
         * Tools que competem por uma necessidade que a família nativa também atende, e por isso
         * precisam dizer ao cliente quando escolhê-las.
         */
        val INSTRUCTIVE = setOf(
            "prumo_repository_get_branch",
            "prumo_repository_get_diff",
            "prumo_repository_get_status",
            "prumo_repository_get_structure",
            "prumo_repository_read_file",
            "prumo_repository_search_text",
        )
    }
}
