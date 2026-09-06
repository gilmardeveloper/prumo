package io.prumo.mcp.toolsets

import io.prumo.mcp.repository.GitFileDelta
import io.prumo.mcp.repository.RepositoryReader
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * O diff entrega conteúdo por diferença, e por isso precisa honrar a mesma exclusão que a leitura.
 *
 * Foi a única das quatro tools de repositório a nascer sem essa imposição.
 */
@Tag("security")
class DiffExclusionTest {

    private val binding = RepositoryBinding(
        id = "folha",
        name = "folha",
        localPath = "C:/repos/folha",
        role = RepositoryRole.REFERENCE,
        accessMode = AccessMode.READ_ONLY,
        excludedPaths = listOf("BANCO_DE_DADOS", "FONTES/curl", ".claude"),
    )

    private fun delta(path: String) = GitFileDelta(path = path, addedLines = 3, deletedLines = 1, binary = false)

    @Test
    fun `arquivo alterado dentro de caminho excluido nao aparece no diff`() {
        val resposta = RepositoryReports.diff(
            binding,
            staged = false,
            deltas = listOf(delta("FONTES/curl/chamada.sh"), delta("FONTES/pom.xml")),
        )

        assertEquals(listOf("FONTES/pom.xml"), resposta.files.map { it.path })
    }

    @Test
    fun `a pasta excluida inteira desaparece do diff`() {
        val resposta = RepositoryReports.diff(
            binding,
            staged = false,
            deltas = listOf(delta("BANCO_DE_DADOS/schema.sql"), delta("README.md")),
        )

        assertEquals(listOf("README.md"), resposta.files.map { it.path })
    }

    @Test
    fun `trocar a caixa do nome nao contorna a exclusao no diff`() {
        val resposta = RepositoryReports.diff(
            binding,
            staged = false,
            deltas = listOf(delta("fontes/CURL/chamada.sh"), delta("banco_de_dados/x.sql")),
        )

        assertTrue(resposta.files.isEmpty(), "a exclusão caiu com a troca de caixa: ${resposta.files}")
    }

    @Test
    fun `o diretorio do Git nunca aparece no diff`() {
        val resposta = RepositoryReports.diff(binding, staged = false, deltas = listOf(delta(".git/config")))

        assertTrue(resposta.files.isEmpty())
    }

    @Test
    fun `arquivo fora dos caminhos excluidos continua visivel`() {
        val resposta = RepositoryReports.diff(
            binding,
            staged = false,
            deltas = listOf(delta("FONTES/folha-ejb/pom.xml")),
        )

        assertEquals(1, resposta.files.size)
        assertEquals(3, resposta.files.single().addedLines)
    }

    @Test
    fun `a regra de casamento e a mesma da leitura`() {
        listOf("FONTES/curl", "FONTES/curl/x.sh", "fontes/CURL", "BANCO_DE_DADOS/a/b.sql").forEach { path ->
            assertTrue(
                RepositoryReader.isExcludedPath(path, binding.excludedPaths),
                "'$path' deveria ser excluído",
            )
        }
        listOf("FONTES/pom.xml", "README.md", "FONTES/curly/x.sh").forEach { path ->
            assertFalse(
                RepositoryReader.isExcludedPath(path, binding.excludedPaths),
                "'$path' não deveria ser excluído",
            )
        }
    }
}
