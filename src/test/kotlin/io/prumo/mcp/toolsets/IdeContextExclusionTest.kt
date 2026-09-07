package io.prumo.mcp.toolsets

import io.prumo.mcp.ide.EditorSnapshot
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * O contexto do editor não é uma porta lateral para o caminho excluído.
 *
 * A exclusão promete que o Prumo não lê nem lista aquele caminho. O arquivo aberto no editor chega
 * por um caminho absoluto que não passa pelo leitor de repositório, e a promessa vale para ele
 * também: nem o caminho, nem a linguagem, nem a cadeia de símbolos.
 */
@Tag("security")
class IdeContextExclusionTest {

    private val consumidor = RepositoryBinding(
        id = "consumidor",
        name = "folha-calculadora-consumidor",
        localPath = "C:/repos/consumidor",
        role = RepositoryRole.PRIMARY,
        accessMode = AccessMode.READ_WRITE,
        excludedPaths = listOf("segredos", "target"),
    )

    private val context = context(consumidor)

    @Test
    fun `arquivo dentro de pasta excluida nao aparece no contexto do editor`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/repos/consumidor/segredos/Chaves.kt"))

        assertFalse(response.insideWorkspace, "arquivo excluído não pode ser localizado")
        assertNull(response.repositoryId)
        assertNull(response.path)
        assertNull(response.language)
        assertTrue(response.symbolPath.isEmpty(), "a cadeia de símbolos revela nome de classe e de função")
    }

    @Test
    fun `trocar a caixa do nome nao contorna a exclusao no contexto do editor`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/repos/consumidor/SEGREDOS/Chaves.kt"))

        assertFalse(response.insideWorkspace)
        assertNull(response.path)
    }

    @Test
    fun `arquivo dentro do diretorio do Git nunca aparece`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/repos/consumidor/.git/config"))

        assertFalse(response.insideWorkspace)
        assertNull(response.path)
    }

    @Test
    fun `pasta excluida em qualquer profundidade nao aparece`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/repos/consumidor/app/target/classes/A.class"))

        assertFalse(response.insideWorkspace)
        assertNull(response.path)
    }

    @Test
    fun `o arquivo legitimo continua respondendo por inteiro`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/repos/consumidor/src/Prumo.kt"))

        assertTrue(response.insideWorkspace)
        assertEquals("consumidor", response.repositoryId)
        assertEquals("src/Prumo.kt", response.path)
        assertEquals("Kotlin", response.language)
        assertEquals(listOf("Prumo", "calcula"), response.symbolPath)
    }

    /**
     * Vínculos aninhados: o repositório mais abrangente não pode revelar o que o mais próximo
     * recusou.
     */
    @Test
    fun `vinculo mais abrangente nao desfaz a exclusao do mais proximo`() {
        val modulo = RepositoryBinding(
            id = "modulo",
            name = "modulo-interno",
            localPath = "C:/repos/consumidor/modulos/interno",
            role = RepositoryRole.RELATED_COMPONENT,
            accessMode = AccessMode.READ_ONLY,
            excludedPaths = listOf("privado"),
        )
        val comAninhado = context(consumidor, modulo)

        val response = RepositoryReports.ideContext(
            comAninhado,
            snapshot("C:/repos/consumidor/modulos/interno/privado/Chave.kt"),
        )

        assertFalse(response.insideWorkspace, "o vínculo pai não pode revelar o que o filho excluiu")
        assertNull(response.path)
    }

    @Test
    fun `arquivo fora de todo repositorio continua fora`() {
        val response = RepositoryReports.ideContext(context, snapshot("C:/outro/lugar/A.kt"))

        assertFalse(response.insideWorkspace)
    }

    private fun context(vararg repositories: RepositoryBinding) = WorkspaceContext(
        Workspace(
            id = "modernizacao-folha",
            name = "Modernização Folha",
            type = WorkspaceType.MODERNIZATION,
            repositories = repositories.toList(),
            policies = WorkspacePolicies.DENY_ALL,
            createdAt = "2026-09-04T00:00:00Z",
            updatedAt = "2026-09-04T00:00:00Z",
        ),
        repositories.first(),
    )

    private fun snapshot(absolutePath: String) = EditorSnapshot(
        absolutePath = absolutePath,
        line = 12,
        column = 4,
        selectionStartLine = null,
        selectionEndLine = null,
        selectionLength = null,
        symbolPath = listOf("Prumo", "calcula"),
        language = "Kotlin",
        moduleName = "app",
    )
}
