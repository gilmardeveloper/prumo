package io.prumo.mcp.ui

import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolution
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceViewModelTest {

    private val target = RepositoryBinding(
        id = "consumidor",
        name = "folha-calculadora-consumidor",
        localPath = "C:/repos/consumidor",
        gitRemote = "git@github.com:org/consumidor.git",
        role = RepositoryRole.TARGET,
        accessMode = AccessMode.READ_WRITE,
    )

    private val legacy = RepositoryBinding(
        id = "legado",
        name = "folha",
        localPath = "C:/repos/folha",
        role = RepositoryRole.LEGACY_REFERENCE,
        accessMode = AccessMode.READ_ONLY,
    )

    private val workspace = Workspace(
        id = "modernizacao-folha",
        name = "Modernização Folha",
        type = WorkspaceType.MODERNIZATION,
        repositories = listOf(target, legacy),
        policies = WorkspacePolicies.DENY_ALL,
        createdAt = "2026-09-04T00:00:00Z",
        updatedAt = "2026-09-04T00:00:00Z",
    )

    @Test
    fun `mostra o workspace corrente com repositorios e politicas`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured

        assertEquals("Modernização Folha", model.workspaceName)
        assertEquals("MODERNIZATION", model.workspaceType)
        assertEquals(listOf("folha-calculadora-consumidor", "folha"), model.repositories.map { it.name })
        assertEquals(listOf(true, false), model.repositories.map { it.current })
        assertEquals("READ_ONLY", model.repositories.last().accessMode)
        assertTrue(model.policies.none { it.allowed })
    }

    @Test
    fun `o painel nunca revela caminho de disco`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured

        val rendered = model.toString()
        assertTrue(!rendered.contains("C:/repos"), "o modelo da tela nao deve carregar caminho local")
    }

    @Test
    fun `ambiguidade informa a quantidade, nunca os outros workspaces`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Ambiguous("consumidor", listOf("alpha", "beta")),
        ) as WorkspaceViewModel.Ambiguous

        assertEquals(2, model.workspaceCount)
        val rendered = model.toString()
        assertTrue(!rendered.contains("alpha") && !rendered.contains("beta"))
    }

    @Test
    fun `projeto nao configurado leva a acao de configuracao`() {
        val model = WorkspaceViewModel.from(WorkspaceResolution.NotConfigured("avulso"))

        assertEquals(WorkspaceViewModel.NotConfigured("avulso"), model)
    }
}

class ConfigureWorkspaceActionTest {

    @Test
    fun `o identificador gerado e seguro para virar diretorio`() {
        assertEquals("modernizacao-folha", ConfigureWorkspaceAction.slug("Modernização Folha"))
        assertEquals("folha-2026", ConfigureWorkspaceAction.slug("  Folha / 2026  "))
        assertEquals("a-b", ConfigureWorkspaceAction.slug("../a/../b"))
    }

    @Test
    fun `o identificador nunca contem separador nem travessia`() {
        listOf("../../etc/passwd", "C:\\Windows", "..", "///").forEach { name ->
            val slug = ConfigureWorkspaceAction.slug(name)
            assertTrue(!slug.contains("/") && !slug.contains("\\") && !slug.contains(".."), name)
        }
    }
}
