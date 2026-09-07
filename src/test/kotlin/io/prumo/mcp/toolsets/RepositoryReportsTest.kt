package io.prumo.mcp.toolsets

import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.EditorSnapshot
import io.prumo.mcp.ide.GitCommandExecutor
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.repository.DirectoryEntry
import io.prumo.mcp.repository.DirectoryListing
import io.prumo.mcp.repository.GitStateParser
import io.prumo.mcp.repository.TextMatch
import io.prumo.mcp.repository.TextSearchOutcome
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class RepositoryReportsTest {

    private val json = Json { encodeDefaults = true }

    private val consumidor = RepositoryBinding(
        id = "consumidor",
        name = "folha-calculadora-consumidor",
        localPath = "C:/repos/consumidor",
        gitRemote = "git@github.com:org/consumidor.git",
        role = RepositoryRole.PRIMARY,
        accessMode = AccessMode.READ_WRITE,
    )

    private val modulo = RepositoryBinding(
        id = "modulo",
        name = "modulo-interno",
        localPath = "C:/repos/consumidor/modulos/interno",
        role = RepositoryRole.RELATED_COMPONENT,
        accessMode = AccessMode.READ_ONLY,
    )

    private val context = WorkspaceContext(
        Workspace(
            id = "modernizacao-folha",
            name = "Modernização Folha",
            type = WorkspaceType.MODERNIZATION,
            repositories = listOf(consumidor, modulo),
            policies = WorkspacePolicies.DENY_ALL,
            createdAt = "2026-09-04T00:00:00Z",
            updatedAt = "2026-09-04T00:00:00Z",
        ),
        consumidor,
    )

    @Test
    fun `o status entrega branch e caminhos relativos, sem caminho de disco`() {
        val snapshot = GitStateParser.parseStatus(
            listOf(
                "# branch.oid abc1234",
                "# branch.head main",
                "# branch.ab +1 -0",
                "1 M. N... 100644 100644 100644 aaa bbb src/Prumo.kt",
            ),
        )

        val response = RepositoryReports.status(consumidor, snapshot)
        val serialized = json.encodeToString(RepositoryStatusResponse.serializer(), response)

        assertEquals("consumidor", response.repositoryId)
        assertEquals("main", response.branch.branch)
        assertEquals(listOf("src/Prumo.kt"), response.changes.map { it.path })
        assertEquals(1, response.changeCount)
        assertFalse(response.truncated)
        assertFalse(serialized.contains("C:/repos"), "a resposta nao deve conter caminho local")
    }

    @Test
    fun `status com mais mudancas que o teto avisa que cortou`() {
        val lines = (1..RepositoryReports.MAX_CHANGES + 5).map { "? arquivo-$it.txt" }

        val response = RepositoryReports.status(consumidor, GitStateParser.parseStatus(lines))

        assertEquals(RepositoryReports.MAX_CHANGES, response.changes.size)
        assertEquals(RepositoryReports.MAX_CHANGES + 5, response.changeCount)
        assertTrue(response.truncated)
    }

    @Test
    fun `o patch e cortado no teto e a resposta declara o corte`() {
        val patch = (1..RepositoryReports.MAX_PATCH_LINES + 10).map { "+linha $it" }

        val response = RepositoryReports.diff(consumidor, staged = false, deltas = emptyList(), patchLines = patch)

        assertEquals(RepositoryReports.MAX_PATCH_LINES, response.patch?.lines()?.size)
        assertTrue(response.patchTruncated)
    }

    @Test
    fun `diff sem patch pedido nao inventa conteudo`() {
        val response = RepositoryReports.diff(consumidor, staged = true, deltas = emptyList())

        assertNull(response.patch)
        assertFalse(response.patchTruncated)
        assertTrue(response.staged)
    }

    @Test
    fun `busca e estrutura so falam em caminho relativo`() {
        val search = RepositoryReports.search(
            consumidor,
            "prumo",
            TextSearchOutcome(listOf(TextMatch("src/Prumo.kt", 3, "class Prumo")), truncated = true, filesScanned = 7),
        )
        val structure = RepositoryReports.structure(
            consumidor,
            DirectoryListing("src", listOf(DirectoryEntry("src/Prumo.kt", directory = false, sizeBytes = 120)), false),
        )

        val serialized = json.encodeToString(TextSearchResponse.serializer(), search) +
            json.encodeToString(RepositoryStructureResponse.serializer(), structure)

        assertTrue(search.truncated)
        assertEquals(7, search.filesScanned)
        assertEquals(listOf("src/Prumo.kt"), structure.entries.map { it.path })
        assertFalse(serialized.contains("C:/repos"))
    }

    @Test
    fun `o arquivo aberto e traduzido para o repositorio mais especifico`() {
        val response = RepositoryReports.ideContext(
            context,
            snapshot("C:/repos/consumidor/modulos/interno/src/Calculo.kt"),
        )

        assertTrue(response.insideWorkspace)
        assertEquals("modulo", response.repositoryId)
        assertEquals("src/Calculo.kt", response.path)
        assertEquals(listOf("Calculo", "somar"), response.symbolPath)
        assertEquals(12, response.line)
    }

    @Test
    fun `arquivo fora dos repositorios do workspace nao revela nada alem disso`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/outro-projeto/src/Segredo.kt"))
        val serialized = json.encodeToString(IdeContextResponse.serializer(), response)

        assertFalse(response.insideWorkspace)
        assertNull(response.path)
        assertNull(response.repositoryId)
        assertTrue(response.symbolPath.isEmpty())
        assertFalse(serialized.contains("Segredo"), "nem o nome do arquivo de fora pode aparecer")
        assertFalse(serialized.contains("outro-projeto"))
    }

    @Test
    fun `a cadeia de simbolos nao repete o nome da classe`() {
        val response = RepositoryReports.ideContext(
            context,
            snapshot("C:/repos/consumidor/src/Politicas.kt").copy(
                // O construtor primario do Kotlin responde pelo nome da propria classe.
                symbolPath = listOf("WorkspacePolicies", "WorkspacePolicies", "databaseWrite"),
            ),
        )

        assertEquals(listOf("WorkspacePolicies", "databaseWrite"), response.symbolPath)
    }

    @Test
    fun `sem editor aberto a resposta e ausencia, nao invencao`() {
        val response = RepositoryReports.ideContext(context, null)

        assertFalse(response.insideWorkspace)
        assertNull(response.line)
    }

    private fun snapshot(path: String) = EditorSnapshot(
        absolutePath = path,
        line = 12,
        column = 5,
        selectionStartLine = 12,
        selectionEndLine = 14,
        selectionLength = 40,
        symbolPath = listOf("Calculo", "somar"),
        language = "Kotlin",
        moduleName = "interno.main",
    )
}

/**
 * A superfície MCP do Prumo é somente leitura no MVP. O teste fixa os nomes registrados e a lista
 * de comandos Git disponíveis: acrescentar uma tool — ou um comando que escreva — passa a exigir
 * alterar este teste, e portanto aparece em revisão.
 */
@Tag("security")
class ToolSurfaceTest {

    /** Prefixos de operação que denotam escrita, no vocabulário das operações canônicas da casa. */
    private val ESCRITA = listOf("submit", "remember", "forget", "write", "install", "remove")

    @Test
    fun `as tools registradas sao exatamente as previstas`() {
        val registered = listOf(
            WorkspaceToolset::class.java,
            RepositoryToolset::class.java,
            IdeToolset::class.java,
            DatabaseToolset::class.java,
            PackToolset::class.java,
            PackAuthoringToolset::class.java,
            QualityToolset::class.java,
            KnowledgeToolset::class.java,
        )
            .flatMap { toolset -> toolset.declaredMethods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name } }
            .sorted()

        assertEquals(
            listOf(
                "prumo_database_describe_table",
                "prumo_database_execute_readonly",
                "prumo_database_get_schema",
                "prumo_database_list_available",
                "prumo_database_list_tables",
                "prumo_ide_get_current_context",
                "prumo_knowledge_forget",
                "prumo_knowledge_read",
                "prumo_knowledge_recall",
                "prumo_knowledge_remember",
                "prumo_pack_get_authoring_spec",
                "prumo_pack_get_knowledge",
                "prumo_pack_list",
                "prumo_pack_run_tool",
                "prumo_pack_search_knowledge",
                "prumo_pack_submit",
                "prumo_pack_validate",
                "prumo_quality_list_inspections",
                "prumo_repository_get_branch",
                "prumo_repository_get_diff",
                "prumo_repository_get_status",
                "prumo_repository_get_structure",
                "prumo_repository_read_file",
                "prumo_repository_search_text",
                "prumo_workspace_get_context",
                "prumo_workspace_get_documentation_sources",
                "prumo_workspace_get_policy",
                "prumo_workspace_get_repositories",
                "prumo_workspace_prepare",
                "prumo_workspace_read_documentation",
            ),
            registered,
        )
    }

    /**
     * Uma toolset que não está no `plugin.xml` compila, passa no verificador e simplesmente não
     * existe para o cliente. O defeito só aparece com a IDE aberta.
     */
    @Test
    fun `toda toolset com tool declarada esta registrada no descritor`() {
        val descriptor = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/META-INF/plugin.xml"),
        )
        val toolsets = listOf(
            PrumoDiagnosticsToolset::class.java,
            WorkspaceToolset::class.java,
            RepositoryToolset::class.java,
            IdeToolset::class.java,
            DatabaseToolset::class.java,
            PackToolset::class.java,
            PackAuthoringToolset::class.java,
            QualityToolset::class.java,
            KnowledgeToolset::class.java,
        ).filter { toolset -> toolset.declaredMethods.any { it.getAnnotation(McpTool::class.java) != null } }

        val ausentes = toolsets.filterNot { descriptor.contains(it.name) }

        assertTrue(toolsets.isNotEmpty(), "nenhuma toolset encontrada; o teste perdeu o alvo")
        assertTrue(ausentes.isEmpty(), "toolset fora do plugin.xml: ${ausentes.map { it.simpleName }}")
    }

    /**
     * Uma escrita que consome decisão de leitura faz `prumo_workspace_get_policy` publicar uma
     * resposta que não descreve a operação — e o painel de políticas é lido por quem decide o que a
     * IA pode fazer. A submissão de pacote viveu assim até a 0.5.0.
     */
    @Test
    fun `nenhuma tool que escreve consome uma acao de leitura`() {
        val chamada = Regex("""prumoToolCall\(\s*\w+,\s*"([^"]+)",\s*PolicyAction\.(\w+)""")
        val operacoes = java.nio.file.Files.list(java.nio.file.Path.of("src/main/kotlin/io/prumo/mcp/toolsets"))
            .use { arquivos ->
                arquivos.toList()
                    .filter { it.toString().endsWith("Toolset.kt") }
                    .flatMap { chamada.findAll(java.nio.file.Files.readString(it)).toList() }
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }

        assertTrue(operacoes.isNotEmpty(), "nenhuma chamada encontrada; o teste perdeu o alvo")

        val escritas = operacoes.filterKeys { operacao ->
            ESCRITA.any { operacao.substringAfter('.').startsWith(it) }
        }
        assertTrue(escritas.isNotEmpty(), "nenhuma operação de escrita encontrada; o teste perdeu o alvo")

        val mentindo = escritas.filterValues { it.startsWith("READ_") }
        assertTrue(mentindo.isEmpty(), "operações de escrita consumindo ação de leitura: $mentindo")
    }

    @Test
    fun `o executor de Git nao expoe comando que escreva`() {
        val commands = GitCommandExecutor::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .map { it.name }
            .sorted()

        assertEquals(listOf("branches", "changedFiles", "patch", "status"), commands)
    }
}
