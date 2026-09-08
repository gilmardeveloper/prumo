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
        role = RepositoryRole.PRIMARY,
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

    /**
     * A base de conhecimento é a única superfície em que a IA grava sem passar pelo desenvolvedor.
     * A tela é onde ele vê o que foi gravado, por quem, de que fonte — e se aquilo ainda vale.
     */
    @Test
    fun `a memoria da IA chega a tela com autor, fonte e frescor`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
            memory = listOf(
                WorkspaceViewModel.MemoryRow(
                    knowledgeId = "s-1210-prazo",
                    title = "Prazo do S-1210",
                    sourceId = "eventos-esocial",
                    freshness = "STALE",
                    author = "claude-code/2.1",
                    updatedAt = "2026-09-07T12:00:00Z",
                ),
            ),
        ) as WorkspaceViewModel.Configured

        assertEquals(1, model.memory.size)
        assertEquals("claude-code/2.1", model.memory.single().author, "a tela não diz qual cliente gravou")
        assertEquals("STALE", model.memory.single().freshness, "a tela não diz se a fonte mudou")
        assertEquals("eventos-esocial", model.memory.single().sourceId)
    }

    /**
     * Registro fora de alcance carimba `ORPHAN`, que diz "a fonte sumiu". Quem excluiu o caminho foi
     * o próprio desenvolvedor, e é isso que a linha precisa dizer a ele.
     */
    @Test
    fun `a linha diz fora de alcance antes de dizer orfao`() {
        val fora = linha(freshness = "ORPHAN", outOfReach = true)
        val orfa = linha(freshness = "ORPHAN", outOfReach = false)
        val velha = linha(freshness = "STALE", outOfReach = false)

        assertEquals("OUT_OF_REACH", fora.state)
        assertEquals("ORPHAN", orfa.state)
        assertEquals("STALE", velha.state)
    }

    @Test
    fun `linha ao alcance e o padrao`() {
        assertEquals("FRESH", linha(freshness = "FRESH").state)
    }

    private fun linha(freshness: String, outOfReach: Boolean = false) = WorkspaceViewModel.MemoryRow(
        knowledgeId = "s-1210-prazo",
        title = "Prazo do S-1210",
        sourceId = "eventos-esocial",
        freshness = freshness,
        author = "claude-code/2.1",
        updatedAt = "2026-09-07T12:00:00Z",
        outOfReach = outOfReach,
    )

    @Test
    fun `workspace sem memoria nao inventa linha`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured

        assertTrue(model.memory.isEmpty())
    }

    @Test
    fun `mostra o workspace corrente com repositorios e politicas`() {
        val model = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured

        assertEquals("Modernização Folha", model.workspaceName)
        assertEquals("workspace.type.modernization", model.workspaceTypeKey)
        assertEquals(listOf("folha-calculadora-consumidor", "folha"), model.repositories.map { it.name })
        assertEquals(listOf(true, false), model.repositories.map { it.current })
        assertEquals("access.mode.readOnly", model.repositories.last().accessModeKey)
        assertTrue(model.policies.none { it.allowed })
        assertTrue(
            model.policies.all { it.labelKey.startsWith("policy.") },
            "o modelo da tela carrega chave, nao frase pronta",
        )
        assertTrue(
            model.repositories.all { it.roleKey.startsWith("repository.role.") },
            "papel tambem viaja como chave, nao como texto traduzido",
        )
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
    fun `os packs instalados chegam a tela, e a ausencia deles tambem`() {
        val semPack = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured
        val comPack = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
            installedPacks = listOf(WorkspaceViewModel.PackRow("folha-tools", "Payroll tools", "1.0.0")),
        ) as WorkspaceViewModel.Configured

        assertEquals(emptyList<WorkspaceViewModel.PackRow>(), semPack.installedPacks)
        assertEquals("folha-tools", comPack.installedPacks.single().packId)
        assertEquals("1.0.0", comPack.installedPacks.single().version)
    }

    @Test
    fun `a fila de aprovacao so aparece quando ha proposta esperando`() {
        val vazio = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
        ) as WorkspaceViewModel.Configured
        val comProposta = WorkspaceViewModel.from(
            WorkspaceResolution.Resolved(WorkspaceContext(workspace, target)),
            pendingPacks = 2,
        ) as WorkspaceViewModel.Configured

        assertEquals(0, vazio.pendingPacks)
        assertEquals(2, comProposta.pendingPacks)
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
