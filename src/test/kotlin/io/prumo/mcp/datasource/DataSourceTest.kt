package io.prumo.mcp.datasource

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.datasource.domain.SslMode
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

private const val SECRET = "s3nh4-que-nao-pode-vazar"

private fun profile(
    id: String = "folha",
    user: String = "prumo_leitura",
    accessMode: AccessMode = AccessMode.READ_ONLY,
) = DataSourceProfile(
    id = id,
    name = "Folha de pagamento",
    host = "db.interno.example",
    port = 5432,
    database = "folha",
    user = user,
    accessMode = accessMode,
    sslMode = SslMode.REQUIRE,
)

@Tag("security")
class DataSourceProfileTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `o perfil nao tem campo algum de segredo`() {
        val fields = serializer<DataSourceProfile>().descriptor.let { descriptor ->
            (0 until descriptor.elementsCount).map { descriptor.getElementName(it).lowercase() }
        }

        listOf("password", "senha", "secret", "token", "credential", "passphrase").forEach { forbidden ->
            assertFalse(
                fields.any { it.contains(forbidden) },
                "DataSourceProfile nao pode declarar campo '$forbidden'",
            )
        }
    }

    @Test
    fun `a serializacao do perfil nao carrega senha`() {
        val serialized = json.encodeToString(serializer<DataSourceProfile>(), profile())

        assertFalse(serialized.contains(SECRET))
        assertFalse(serialized.lowercase().contains("password"))
    }

    @Test
    fun `a URL de conexao nao leva usuario nem senha`() {
        val url = profile().jdbcUrl()

        assertEquals("jdbc:postgresql://db.interno.example:5432/folha", url)
        assertFalse(url.contains("prumo_leitura"))
        assertFalse(url.contains("@"))
        assertFalse(url.lowercase().contains("password"))
    }

    @Test
    fun `datasource nasce somente leitura`() {
        val created = DataSourceProfile(
            id = "novo",
            name = "Novo",
            host = "localhost",
            database = "app",
            user = "leitor",
        )

        assertEquals(AccessMode.READ_ONLY, created.accessMode)
        assertFalse(created.writable)
        assertEquals(DataSourceProfile.DEFAULT_PORT, created.port)
    }

    @Test
    fun `identificador e porta invalidos sao recusados antes de virar configuracao`() {
        assertThrows<IllegalArgumentException> { profile(id = "../escapa") }
        assertThrows<IllegalArgumentException> {
            DataSourceProfile(id = "x", name = "x", host = "h", port = 0, database = "d", user = "u")
        }
        assertThrows<IllegalArgumentException> {
            DataSourceProfile(id = "x", name = "x", host = " ", database = "d", user = "u")
        }
    }

    @Test
    fun `o arquivo do workspace guarda o datasource sem a senha`(@TempDir root: Path) {
        val storage = FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        )
        val store = WorkspaceStore(storage)
        val workspace = Workspace(
            id = "folha-2026",
            name = "Folha 2026",
            type = WorkspaceType.LEGACY_MAINTENANCE,
            datasources = listOf(profile()),
            createdAt = "2026-09-05T00:00:00Z",
            updatedAt = "2026-09-05T00:00:00Z",
        )

        store.save(workspace)
        val reloaded = store.load("folha-2026")
        val file = storage.workspaceRoot("folha-2026").resolve("datasources.json")
        val content = Files.readString(file, StandardCharsets.UTF_8)

        assertEquals(listOf("folha"), reloaded?.datasources?.map { it.id })
        assertEquals(AccessMode.READ_ONLY, reloaded?.datasource("folha")?.accessMode)
        assertFalse(content.contains(SECRET), "a senha nao pode chegar ao disco")
        assertFalse(content.lowercase().contains("password"))
    }
}

class ConnectionFailureClassifierTest {

    @Test
    fun `senha ou usuario recusados viram falha de autenticacao`() {
        assertEquals(
            ConnectionTestOutcome.AUTHENTICATION_FAILED,
            ConnectionFailureClassifier.classify("28P01", "FATAL: password authentication failed for user \"leitor\""),
        )
        assertEquals(
            ConnectionTestOutcome.AUTHENTICATION_FAILED,
            ConnectionFailureClassifier.classify("28000", "FATAL: no pg_hba.conf entry for host"),
        )
    }

    @Test
    fun `banco inexistente e distinguido de falha de rede`() {
        assertEquals(
            ConnectionTestOutcome.DATABASE_NOT_FOUND,
            ConnectionFailureClassifier.classify("3D000", "FATAL: database \"folha\" does not exist"),
        )
    }

    @Test
    fun `falha de TLS nao e confundida com falha de rede`() {
        // O PostgreSQL reporta os dois casos com 08006; so a mensagem separa.
        assertEquals(
            ConnectionTestOutcome.SSL_ERROR,
            ConnectionFailureClassifier.classify("08006", "SSL error: certificate verify failed"),
        )
        assertEquals(
            ConnectionTestOutcome.NETWORK_UNREACHABLE,
            ConnectionFailureClassifier.classify("08006", "An I/O error occurred: Connection refused"),
        )
    }

    @Test
    fun `espera longa demais vira timeout`() {
        assertEquals(
            ConnectionTestOutcome.TIMEOUT,
            ConnectionFailureClassifier.classify("08001", "The connection attempt timed out."),
        )
    }

    @Test
    fun `consulta cancelada por tempo e timeout, ainda que a mensagem nao diga isso`() {
        // O PostgreSQL responde 57014 com "canceling statement due to user request": sem estado e
        // sem marcador, o desfecho cairia em erro desconhecido. Descoberto contra servidor real.
        assertEquals(
            ConnectionTestOutcome.TIMEOUT,
            ConnectionFailureClassifier.classify("57014", "ERROR: canceling statement due to user request"),
        )
    }

    @Test
    fun `falha desconhecida nao e vestida de falha de rede`() {
        assertEquals(
            ConnectionTestOutcome.UNEXPECTED_ERROR,
            ConnectionFailureClassifier.classify("XX000", "internal error"),
        )
        assertEquals(ConnectionTestOutcome.UNEXPECTED_ERROR, ConnectionFailureClassifier.classify(null, null))
    }

    @Test
    fun `nenhuma frase mostrada ao usuario cita dado de conexao`() {
        ConnectionTestOutcome.entries.forEach { outcome ->
            val message = outcome.hint
            assertTrue(message.isNotBlank(), "todo desfecho precisa de uma frase")
            assertFalse(message.contains("@"))
            assertFalse(message.lowercase().contains("jdbc"))
            assertFalse(message.lowercase().contains("password:"))
        }
    }
}

@Tag("security")
class CredentialKeyTest {

    @Test
    fun `a chave separa datasources de mesmo nome em workspaces diferentes`() {
        val primeira = CredentialKey("folha-2026", "folha").serviceName
        val segunda = CredentialKey("folha-legado", "folha").serviceName

        assertFalse(primeira == segunda)
        assertTrue(primeira.contains("folha-2026"))
        assertTrue(primeira.contains("folha"))
    }

    @Test
    fun `identificador com travessia nao vira chave de cofre`() {
        assertThrows<IllegalArgumentException> { CredentialKey("../outro", "folha") }
        assertThrows<IllegalArgumentException> { CredentialKey("folha-2026", "../outro") }
    }
}

@Tag("security")
class DataSourceAuditTest {

    @Test
    fun `a auditoria do teste registra o desfecho e mais nada`(@TempDir root: Path) {
        val storage = FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        )
        val log = AuditLog(storage)

        DataSourceAudit.recordConnectionTest(
            log = log,
            workspaceId = "folha-2026",
            profile = profile(),
            outcome = ConnectionTestOutcome.AUTHENTICATION_FAILED,
            durationMillis = 12,
        )

        val entry = log.read("folha-2026").single()
        val raw = Files.readString(
            storage.workspaceRoot("folha-2026").resolve("audit").resolve("audit.jsonl"),
            StandardCharsets.UTF_8,
        )

        assertEquals(DataSourceAudit.OPERATION, entry.operation)
        assertEquals("folha", entry.datasourceId)
        assertEquals(mapOf("outcome" to "AUTHENTICATION_FAILED"), entry.details)
        assertFalse(raw.contains(SECRET), "senha jamais na trilha")
        assertFalse(raw.contains("db.interno.example"), "host nao entra na trilha")
        assertFalse(raw.contains("prumo_leitura"), "usuario do banco nao entra na trilha")
    }
}
