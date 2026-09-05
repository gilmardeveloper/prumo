package io.prumo.mcp.datasource

import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Teste do probe contra um PostgreSQL real.
 *
 * A classificação de falha é verificada linha a linha em `ConnectionFailureClassifierTest`; aqui o
 * que se prova é outra coisa — que o banco de verdade produz os estados que a classificação espera.
 * Regra de porcelana muda de versão para versão, e só um servidor real desmente uma suposição.
 *
 * Sem Docker na máquina, o teste se declara pulado: o resto da suíte continua valendo.
 */
class PostgresConnectionProbeTest {

    private val probe = PostgresConnectionProbe(timeoutSeconds = 5)

    @Test
    fun `credencial correta conecta`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = probe.test(profileFor(container.databaseName), container.password.toCharArray())

        assertEquals(ConnectionTestOutcome.SUCCESS, outcome)
    }

    @Test
    fun `senha errada e reconhecida como falha de autenticacao, nao de rede`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = probe.test(profileFor(container.databaseName), "senha-errada".toCharArray())

        assertEquals(ConnectionTestOutcome.AUTHENTICATION_FAILED, outcome)
    }

    @Test
    fun `banco inexistente e reconhecido como banco inexistente`() {
        assumeTrue(dockerAvailable, "Docker nao esta disponivel nesta maquina")

        val outcome = probe.test(profileFor("banco_que_nao_existe"), container.password.toCharArray())

        assertEquals(ConnectionTestOutcome.DATABASE_NOT_FOUND, outcome)
    }

    @Test
    fun `porta fechada e reconhecida como servidor inalcancavel`() {
        val fechada = DataSourceProfile(
            id = "fechada",
            name = "Porta fechada",
            host = "127.0.0.1",
            port = 1,
            database = "qualquer",
            user = "ninguem",
            sslMode = SslMode.DISABLE,
        )

        assertEquals(ConnectionTestOutcome.NETWORK_UNREACHABLE, probe.test(fechada, "x".toCharArray()))
    }

    private fun profileFor(database: String) = DataSourceProfile(
        id = "container",
        name = "PostgreSQL de teste",
        host = container.host,
        port = container.firstMappedPort,
        database = database,
        user = container.username,
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
