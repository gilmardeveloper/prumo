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

    @Test
    fun `coluna inexistente devolve o nome recusado, e nao falha de conexao`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = assertThrows<DataSourceAccessException> {
            executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT coluna_que_nao_existe FROM servidor")
        }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("coluna_que_nao_existe"), message)
        assertFalse(message.contains("connect", ignoreCase = true), message)
    }

    /**
     * O driver traduz os rótulos conforme o locale da JVM; a resposta ao cliente MCP é sempre em
     * inglês, e a sugestão do servidor é o que permite acertar a coluna na tentativa seguinte.
     */
    @Test
    fun `o rotulo e a sugestao do servidor chegam em ingles`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = assertThrows<DataSourceAccessException> {
            executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT nom FROM servidor")
        }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("Position: "), message)
        assertTrue(message.contains("Hint: "), message)
        assertTrue(message.contains("servidor.nome"), message)
    }

    /**
     * Sintaxe que o parser recusa nunca chega ao servidor; o agrupamento inválido chega, e volta
     * pela mesma classe 42 da coluna inexistente.
     */
    @Test
    fun `tabela inexistente e agrupamento invalido tambem chegam descritos`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val semTabela = assertThrows<DataSourceAccessException> {
            executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT id FROM tabela_que_nao_existe")
        }
        assertTrue(semTabela.message.orEmpty().contains("tabela_que_nao_existe"), semTabela.message.orEmpty())

        val semGroupBy = assertThrows<DataSourceAccessException> {
            executor().execute(workspaceId, profile(AccessMode.READ_ONLY), "SELECT nome, count(*) FROM servidor")
        }
        assertTrue(semGroupBy.message.orEmpty().contains("GROUP BY"), semGroupBy.message.orEmpty())
    }

    /**
     * A mascara precisa seguir a origem do dado. Decidir pelo rotulo de saida deixava o cliente
     * desfaze-la com um `AS`, e a garantia escrita diz que nao se pode desmascarar essas colunas.
     */
    @Test
    fun `apelido inocente nao desmascara coluna de segredo`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(
            workspaceId,
            profile(AccessMode.READ_ONLY),
            "SELECT senha AS num_matricula FROM servidor ORDER BY id",
        )

        assertTrue(outcome.columns.single().masked, outcome.columns.toString())
        assertTrue(outcome.rows.all { it.single() == "[masked]" }, outcome.rows.toString())
        assertFalse(outcome.rows.any { it.contains("trocar123") }, "o valor real nao pode aparecer")
    }

    @Test
    fun `expressao sobre coluna de segredo tambem volta mascarada`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(
            workspaceId,
            profile(AccessMode.READ_ONLY),
            "SELECT length(senha) AS n FROM servidor ORDER BY id",
        )

        assertTrue(outcome.columns.single().masked, outcome.columns.toString())
        assertTrue(outcome.rows.all { it.single() == "[masked]" }, outcome.rows.toString())
    }

    @Test
    fun `coluna comum com apelido comum continua legivel`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = executor().execute(
            workspaceId,
            profile(AccessMode.READ_ONLY),
            "SELECT nome AS titular FROM servidor ORDER BY id",
        )

        assertFalse(outcome.columns.single().masked)
        assertEquals(listOf("Ana", "Bruno", "Carla"), outcome.rows.map { it.single() })
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
