package io.prumo.mcp.datasource.postgres

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.datasource.DataSourceAccessException
import io.prumo.mcp.datasource.PostgresConnectionFactory
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.workspace.domain.AccessMode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.SQLException

/** Cofre de teste: guarda em memória e nunca toca o PasswordSafe da máquina de quem roda a suíte. */
private class InMemoryCredentialProvider : CredentialProvider {

    private val entries = mutableMapOf<String, CharArray>()

    override fun store(key: CredentialKey, user: String, password: CharArray) {
        entries["${key.serviceName}|$user"] = password.copyOf()
    }

    override fun password(key: CredentialKey, user: String): CharArray? =
        entries["${key.serviceName}|$user"]?.copyOf()

    override fun remove(key: CredentialKey, user: String) {
        entries.remove("${key.serviceName}|$user")
    }
}

/**
 * Introspecção contra um PostgreSQL real.
 *
 * O que se prova aqui é o que só um servidor de verdade responde: o catálogo devolve o que o
 * Prumo espera, e um nome de tabela com SQL dentro é comparado como texto em vez de executado.
 */
class PostgresIntrospectorTest {

    private val introspector = PostgresIntrospector()

    @Test
    fun `lista os schemas do usuario e omite os do sistema`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val schemas = connection.use { introspector.schemas(it) }

        val folha = schemas.single { it.name == "folha" }
        assertEquals(3, folha.objectCount, "duas tabelas e uma view")
        assertTrue(schemas.none { it.name == "pg_catalog" || it.name == "information_schema" })
    }

    @Test
    fun `lista tabelas e views de um schema`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val tables = connection.use { introspector.tables(it, "folha") }

        assertEquals(listOf("resumo", "rubrica", "servidor"), tables.map { it.name }.sorted())
        assertEquals("VIEW", tables.single { it.name == "resumo" }.kind)
        assertEquals("Servidores da folha", tables.single { it.name == "servidor" }.comment)
    }

    @Test
    fun `descreve colunas, restricoes e indices`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val detail = connection.use { introspector.describe(it, "folha", "servidor") }

        assertEquals(listOf("id", "nome", "matricula", "admissao"), detail.columns.map { it.name })
        assertFalse(detail.columns.single { it.name == "nome" }.nullable)
        assertEquals("Nome completo", detail.columns.single { it.name == "nome" }.comment)
        assertTrue(detail.constraints.any { it.kind == "PRIMARY_KEY" })
        assertTrue(detail.constraints.any { it.kind == "UNIQUE" })
        assertTrue(detail.indexes.any { it.unique })
    }

    @Test
    fun `chave estrangeira aparece com o alvo`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val detail = connection.use { introspector.describe(it, "folha", "rubrica") }

        val foreignKey = detail.constraints.single { it.kind == "FOREIGN_KEY" }
        assertTrue(foreignKey.definition.contains("servidor"), foreignKey.definition)
    }

    @Test
    fun `nome de tabela com SQL dentro nao executa nada`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        connection.use { open ->
            assertThrows<DataSourceAccessException> {
                introspector.describe(open, "folha", "servidor'; DROP TABLE folha.rubrica; --")
            }
            // A tabela que a injeção tentaria derrubar continua lá.
            assertTrue(introspector.tables(open, "folha").any { it.name == "rubrica" })
        }
    }

    /**
     * Segunda camada de defesa da seção 9: mesmo com credencial de escrita no banco, a conexão de
     * um datasource `READ_ONLY` recusa qualquer statement que escreva.
     */
    @Test
    fun `conexao de datasource somente leitura recusa escrita`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val credentials = InMemoryCredentialProvider()
        val profile = profile(AccessMode.READ_ONLY)
        credentials.store(CredentialKey("folha-2026", profile.id), profile.user, containerPassword())

        PostgresConnectionFactory(credentials).open("folha-2026", profile).use { open ->
            assertTrue(open.isReadOnly, "a conexao precisa nascer somente-leitura")
            val failure = assertThrows<SQLException> {
                open.createStatement().use { it.execute("CREATE TABLE folha.proibida (id int)") }
            }
            assertTrue(failure.message.orEmpty().lowercase().contains("read-only"), failure.message.orEmpty())
        }
    }

    @Test
    fun `tabela inexistente falha com mensagem que diz onde procurou`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val failure = connection.use {
            assertThrows<DataSourceAccessException> { introspector.describe(it, "folha", "inexistente") }
        }

        assertTrue(failure.message.orEmpty().contains("inexistente"))
        assertTrue(failure.message.orEmpty().contains("folha"))
    }

    private val connection: Connection get() = container.createConnection("")

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
                        CREATE SCHEMA folha;
                        CREATE TABLE folha.servidor (
                            id bigserial PRIMARY KEY,
                            nome text NOT NULL,
                            matricula text UNIQUE,
                            admissao date DEFAULT now()
                        );
                        COMMENT ON TABLE folha.servidor IS 'Servidores da folha';
                        COMMENT ON COLUMN folha.servidor.nome IS 'Nome completo';
                        CREATE TABLE folha.rubrica (
                            id bigserial PRIMARY KEY,
                            servidor_id bigint NOT NULL REFERENCES folha.servidor(id),
                            valor numeric(12,2) NOT NULL
                        );
                        CREATE INDEX idx_rubrica_servidor ON folha.rubrica (servidor_id);
                        CREATE VIEW folha.resumo AS
                            SELECT servidor_id, sum(valor) AS total FROM folha.rubrica GROUP BY servidor_id;
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

        private fun containerPassword(): CharArray = container.password.toCharArray()
    }
}

/** A parte da fábrica que não precisa de banco algum para ser verificada. */
class PostgresConnectionFactoryTest {

    private val credentials = InMemoryCredentialProvider()

    @Test
    fun `sem senha no cofre a falha diz o que fazer, sem tentar conectar`() {
        val profile = DataSourceProfile(
            id = "sem-credencial",
            name = "Sem credencial",
            host = "127.0.0.1",
            database = "qualquer",
            user = "ninguem",
        )

        val failure = assertThrows<DataSourceAccessException> {
            PostgresConnectionFactory(credentials).open("folha-2026", profile)
        }

        assertTrue(failure.message.orEmpty().contains("No password is stored"))
        assertTrue(failure.message.orEmpty().contains("Sem credencial"))
        assertFalse(failure.message.orEmpty().contains("127.0.0.1"), "a mensagem nao expoe o host")
    }
}
