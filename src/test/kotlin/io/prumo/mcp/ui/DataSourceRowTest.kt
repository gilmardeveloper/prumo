package io.prumo.mcp.ui

import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolution
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Os bancos na tela.
 *
 * O que decide o alcance da IA é o modo de acesso e a ofuscação de dado pessoal. Host, porta,
 * usuário e nome do banco não são necessários para o dono reconhecer a fonte — e o que não chega à
 * tela não vaza por ela.
 */
class DataSourceRowTest {

    @Test
    fun `workspace sem banco nao produz linha nenhuma`() {
        val model = configured(datasources = emptyList())
        assertTrue(model.dataSources.isEmpty())
    }

    @Test
    fun `cada banco vira uma linha com acesso e ofuscacao`() {
        val model = configured(datasources = listOf(profile(), profile(id = "outro", name = "Outro", obfuscate = false)))

        assertEquals(2, model.dataSources.size)
        assertEquals("Folha", model.dataSources[0].name)
        assertTrue(model.dataSources[0].personalDataObfuscated)
        assertFalse(model.dataSources[1].personalDataObfuscated)
    }

    @Test
    fun `o modo de acesso viaja como chave, nunca como frase`() {
        val model = configured(datasources = listOf(profile()))

        assertTrue(
            model.dataSources[0].accessModeKey.startsWith("access.mode."),
            "esperava chave, veio '${model.dataSources[0].accessModeKey}'",
        )
    }

    @Test
    fun `host, usuario e nome do banco nao chegam a tela`() {
        val model = configured(datasources = listOf(profile()))
        val texto = model.toString()

        listOf("db.interno.gov", "postgres_user", "folha_prod").forEach { segredo ->
            assertFalse(texto.contains(segredo), "'$segredo' vazou para o modelo da tela")
        }
    }

    private fun profile(
        id: String = "folha",
        name: String = "Folha",
        obfuscate: Boolean = true,
    ) = DataSourceProfile(
        id = id,
        name = name,
        host = "db.interno.gov",
        database = "folha_prod",
        user = "postgres_user",
        accessMode = AccessMode.READ_ONLY,
        defaultSchema = "public",
        obfuscatePersonalData = obfuscate,
    )

    private fun configured(datasources: List<DataSourceProfile>): WorkspaceViewModel.Configured {
        val repository = RepositoryBinding(
            id = "primary",
            name = "app",
            localPath = "C:/repos/app",
            role = RepositoryRole.PRIMARY,
            accessMode = AccessMode.READ_ONLY,
        )
        val workspace = Workspace(
            id = "ws",
            name = "Workspace",
            type = WorkspaceType.STANDALONE,
            repositories = listOf(repository),
            datasources = datasources,
            createdAt = "2026-09-06T00:00:00Z",
            updatedAt = "2026-09-06T00:00:00Z",
        )
        val resolution = WorkspaceResolution.Resolved(WorkspaceContext(workspace, repository))
        return WorkspaceViewModel.from(resolution) as WorkspaceViewModel.Configured
    }
}
