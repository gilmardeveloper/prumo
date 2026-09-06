package io.prumo.mcp.pack

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.datasource.InMemoryCredentialProvider
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.pack.application.PackAccessException
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.LocalizedText
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.pack.execution.PackQueryRunner
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.policy.Capability
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path

/**
 * A ferramenta de um pack instalado, invocada de verdade.
 *
 * Verifica que a consulta do pack passa pela mesma classificação de statement, pela mesma
 * transação somente-leitura e pelo mesmo mascaramento, e que não alcança banco não vinculado a
 * este workspace.
 */
@Tag("security")
class PackQueryRunnerTest {

    private fun storage(root: Path) = FileSystemStorageProvider(
        PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
    )

    private fun credentials(datasourceId: String = "folha"): InMemoryCredentialProvider =
        InMemoryCredentialProvider().apply {
            store(CredentialKey("folha-2026", datasourceId), container.username, container.password.toCharArray())
        }

    private fun datasource(id: String = "folha") = DataSourceProfile(
        id = id,
        name = "Folha",
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        accessMode = AccessMode.READ_ONLY,
        sslMode = SslMode.DISABLE,
    )

    private fun workspace(datasources: List<DataSourceProfile>) = Workspace(
        id = "folha-2026",
        name = "Folha 2026",
        type = WorkspaceType.LEGACY_MAINTENANCE,
        repositories = listOf(
            RepositoryBinding("app", "app", "C:/repos/app", role = RepositoryRole.PRIMARY, accessMode = AccessMode.READ_ONLY),
        ),
        datasources = datasources,
        policies = WorkspacePolicies.DENY_ALL,
        createdAt = "2026-09-05T00:00:00Z",
        updatedAt = "2026-09-05T00:00:00Z",
    )

    private fun installPack(
        root: Path,
        sql: String = "SELECT nome, senha FROM servidor ORDER BY id",
        datasourceRef: String? = "folha",
        capabilities: Set<Capability> = setOf(Capability.DATASOURCE_QUERY),
    ) {
        PackStore(storage(root)).save(
            "folha-2026",
            PackManifest(
                id = "folha-tools",
                version = "1.0.0",
                title = LocalizedText("Payroll tools"),
                description = LocalizedText("Saved queries"),
                capabilities = capabilities,
                tools = listOf(
                    PackTool(
                        id = "servidores",
                        title = LocalizedText("Servidores"),
                        description = LocalizedText("Lista servidores"),
                        kind = PackToolKind.QUERY,
                        capabilities = setOf(Capability.DATASOURCE_QUERY),
                        datasourceRef = datasourceRef,
                        sql = sql,
                    ),
                ),
                createdAt = "2026-09-05T00:00:00Z",
                updatedAt = "2026-09-05T00:00:00Z",
            ),
        )
    }

    @Test
    fun `consulta salva do pack roda sob as mesmas camadas do produto`(@TempDir root: Path) {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")
        installPack(root)

        val outcome = PackQueryRunner(storage(root), credentials())
            .run(workspace(listOf(datasource())), "folha-tools", "servidores")

        assertEquals(listOf("Ana", "Bruno"), outcome.rows.map { it[0] })
        assertTrue(outcome.rows.all { it[1] == "[masked]" }, outcome.rows.toString())
    }

    @Test
    fun `banco que nao esta vinculado a este workspace nao e alcancado`(@TempDir root: Path) {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")
        installPack(root, datasourceRef = "banco-de-outro-lugar")

        val failure = assertThrows<PackAccessException> {
            PackQueryRunner(storage(root), credentials())
                .run(workspace(listOf(datasource())), "folha-tools", "servidores")
        }

        assertTrue(failure.message.orEmpty().contains("not bound to this workspace"))
    }

    @Test
    fun `pack sem a capacidade declarada nao consulta banco`(@TempDir root: Path) {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")
        installPack(root, capabilities = emptySet())

        assertThrows<Exception> {
            PackQueryRunner(storage(root), credentials())
                .run(workspace(listOf(datasource())), "folha-tools", "servidores")
        }
    }

    @Test
    fun `consulta de pack que escreve e recusada como qualquer outra`(@TempDir root: Path) {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")
        installPack(root, sql = "DELETE FROM servidor")

        assertThrows<Exception> {
            PackQueryRunner(storage(root), credentials())
                .run(workspace(listOf(datasource())), "folha-tools", "servidores")
        }
    }

    @Test
    fun `a auditoria registra o pack de origem sem os dados`(@TempDir root: Path) {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")
        installPack(root)
        val log = AuditLog(storage(root))

        PackQueryRunner(storage(root), credentials())
            .run(workspace(listOf(datasource())), "folha-tools", "servidores", audit = log)

        val entry = log.read("folha-2026").single()
        val raw = Files.readString(storage(root).workspaceRoot("folha-2026").resolve("audit").resolve("audit.jsonl"))
        assertEquals("folha-tools", entry.packId)
        assertEquals("pack.run_tool", entry.operation)
        assertEquals("2", entry.details["rowCount"])
        assertFalse(raw.contains("Ana"), "dado consultado nao entra na trilha")
    }

    companion object {

        private val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        }

        private lateinit var container: PostgreSQLContainer<*>

        @BeforeAll
        @JvmStatic
        fun startDatabase() {
            if (!dockerAvailable) {
                return
            }
            container = PostgreSQLContainer("postgres:17-alpine").apply { start() }
            container.createConnection("").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        """
                        CREATE TABLE servidor (id serial PRIMARY KEY, nome text NOT NULL, senha text NOT NULL);
                        INSERT INTO servidor (nome, senha) VALUES ('Ana', 'trocar123'), ('Bruno', 'trocar123');
                        """.trimIndent(),
                    )
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun stopDatabase() {
            if (dockerAvailable && ::container.isInitialized) {
                container.stop()
            }
        }
    }
}
