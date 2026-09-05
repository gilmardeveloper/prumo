package io.prumo.mcp.datasource.application

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.datasource.DataSourceAccessException
import io.prumo.mcp.datasource.InMemoryCredentialProvider
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.datasource.security.SqlStatementType
import io.prumo.mcp.workspace.domain.AccessMode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Consulta somente-leitura contra um PostgreSQL real.
 *
 * A classificação de statement é verificada no corpus; aqui prova-se o que só o servidor mostra:
 * a transação somente-leitura recusa escrita mesmo quando o datasource está marcado como
 * `READ_WRITE`, o teto de linhas se declara, o timeout corta a consulta e coluna com nome de
 * segredo volta mascarada.
 */
@Tag("security")
class ReadOnlyQueryExecutorTest {

    private val workspaceId = "folha-2026"

    @Test
    fun `consulta devolve colunas e linhas`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT id, nome FROM servidor ORDER BY id")

        assertEquals(SqlStatementType.SELECT, outcome.statementType)
        assertEquals(listOf("id", "nome"), outcome.columns.map { it.name })
        assertEquals(listOf("Ana", "Bruno", "Carla"), outcome.rows.map { it[1] })
        assertFalse(outcome.truncated)
    }

    @Test
    fun `o teto de linhas corta e a resposta declara`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT * FROM servidor", maxRows = 2)

        assertEquals(2, outcome.rowCount)
        assertTrue(outcome.truncated)
    }

    @Test
    fun `coluna com nome de segredo volta mascarada sem configuracao alguma`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT nome, senha FROM servidor ORDER BY id")

        assertTrue(outcome.columns.single { it.name == "senha" }.masked)
        assertTrue(outcome.rows.all { it[1] == "[masked]" }, outcome.rows.toString())
        assertFalse(outcome.rows.any { it.contains("trocar123") }, "o valor real nao pode aparecer")
    }

    @Test
    fun `escrita e recusada antes de chegar ao banco`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = assertThrows<QueryRefusedException> {
            executor().execute(workspaceId, profile(AccessMode.READ_WRITE), "INSERT INTO servidor (nome) VALUES ('Zé')")
        }

        assertEquals(SqlStatementType.WRITE, failure.statementType)
        // E a tabela continua com as três linhas originais.
        val outcome = executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT count(*) AS total FROM servidor")
        assertEquals("3", outcome.rows.single().single())
    }

    /**
     * Datasource marcado como `READ_WRITE` — a política do workspace pode permitir escrita por
     * outros caminhos, mas **esta operação** é somente-leitura por definição, e quem recusa é o
     * servidor, não a classificação.
     */
    @Test
    fun `datasource gravavel nao torna esta consulta gravavel`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = assertThrows<DataSourceAccessException> {
            executor().execute(workspaceId, profile(AccessMode.READ_WRITE), "SELECT * FROM servidor FOR UPDATE")
        }

        assertTrue(failure.message.orEmpty().isNotBlank())
    }

    @Test
    fun `consulta que demora demais e cortada`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = assertThrows<DataSourceAccessException> {
            executor(timeoutSeconds = 1).execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT pg_sleep(5)")
        }

        assertTrue(failure.message.orEmpty().contains("did not answer in time"), failure.message.orEmpty())
    }

    private fun executor(timeoutSeconds: Int = 15): ReadOnlyQueryExecutor {
        val credentials = InMemoryCredentialProvider()
        credentials.store(CredentialKey(workspaceId, "folha"), container.username, container.password.toCharArray())
        return ReadOnlyQueryExecutor(PostgresConnectionFactory(credentials), timeoutSeconds)
    }

    private fun profile(accessMode: AccessMode) = DataSourceProfile(
        id = "folha",
        name = "Folha",
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        accessMode = accessMode,
        sslMode = SslMode.DISABLE,
    )

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
                        CREATE TABLE servidor (
                            id serial PRIMARY KEY,
                            nome text NOT NULL,
                            senha text NOT NULL
                        );
                        INSERT INTO servidor (nome, senha) VALUES
                            ('Ana', 'trocar123'), ('Bruno', 'trocar123'), ('Carla', 'trocar123');
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
