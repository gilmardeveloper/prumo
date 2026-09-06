package io.prumo.mcp.toolsets

import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.datasource.postgres.TableSummary
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.workspace.application.WorkspaceContext
import io.prumo.mcp.workspace.application.WorkspaceResolutionException
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@Tag("security")
class DatabaseReportsTest {

    private val json = Json { encodeDefaults = true }

    private val folha = DataSourceProfile(
        id = "folha",
        name = "Folha de pagamento",
        host = "db.interno.example",
        port = 6543,
        database = "folha_prod",
        user = "prumo_leitura",
        accessMode = AccessMode.READ_ONLY,
        sslMode = SslMode.REQUIRE,
        defaultSchema = "folha",
    )

    private val context = context(listOf(folha))

    @Test
    fun `a listagem entrega identificador e modo de acesso, nunca dado de conexao`() {
        val response = DatabaseReports.available(context)
        val serialized = json.encodeToString(AvailableDataSourcesResponse.serializer(), response)

        assertEquals(listOf("folha"), response.datasources.map { it.datasourceId })
        assertEquals("READ_ONLY", response.datasources.single().accessMode)
        assertFalse(response.datasources.single().writable)

        listOf("db.interno.example", "6543", "folha_prod", "prumo_leitura").forEach {
            assertFalse(serialized.contains(it), "a resposta MCP nao pode conter '$it'")
        }
    }

    @Test
    fun `workspace sem banco algum responde lista vazia, nao erro`() {
        val response = DatabaseReports.available(context(emptyList()))

        assertTrue(response.datasources.isEmpty())
        assertEquals("modernizacao-folha", response.workspaceId)
    }

    @Test
    fun `banco de outro workspace nao e alcancado pelo identificador`() {
        val failure = assertThrows<WorkspaceResolutionException> {
            context.datasource("banco-do-outro-workspace")
        }

        assertTrue(failure.message.orEmpty().contains("not bound to the current workspace"))
    }

    @Test
    fun `o datasource do proprio workspace e resolvido pelo identificador`() {
        assertEquals(folha, context.datasource("folha"))
    }

    @Test
    fun `listagem no teto declara que cortou`() {
        val tables = (1..DatabaseReports.MAX_TABLES).map { TableSummary("folha", "tabela_$it", "TABLE") }

        val response = DatabaseReports.tables(folha, "folha", tables)

        assertTrue(response.truncated)
        assertEquals(DatabaseReports.MAX_TABLES, response.tables.size)
    }

    @Test
    fun `listagem abaixo do teto nao se declara cortada`() {
        val response = DatabaseReports.tables(folha, null, listOf(TableSummary("folha", "servidor", "TABLE")))

        assertFalse(response.truncated)
        assertEquals("servidor", response.tables.single().name)
    }

    private fun context(datasources: List<DataSourceProfile>): WorkspaceContext {
        val repository = RepositoryBinding(
            id = "consumidor",
            name = "consumidor",
            localPath = "C:/repos/consumidor",
            role = RepositoryRole.PRIMARY,
            accessMode = AccessMode.READ_WRITE,
        )
        return WorkspaceContext(
            Workspace(
                id = "modernizacao-folha",
                name = "Modernização Folha",
                type = WorkspaceType.MODERNIZATION,
                repositories = listOf(repository),
                datasources = datasources,
                policies = WorkspacePolicies.DENY_ALL,
                createdAt = "2026-09-05T00:00:00Z",
                updatedAt = "2026-09-05T00:00:00Z",
            ),
            repository,
        )
    }
}
