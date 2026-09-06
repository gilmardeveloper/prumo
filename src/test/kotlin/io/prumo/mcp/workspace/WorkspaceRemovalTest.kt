package io.prumo.mcp.workspace

import io.prumo.mcp.credential.CredentialKey
import io.prumo.mcp.credential.CredentialProvider
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.application.WorkspaceRemoval
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Sobrescrever um workspace não pode deixar para trás nem arquivo nem senha do anterior. */
@Tag("security")
class WorkspaceRemovalTest {

    private class MemoryCredentials(
        private val failFor: Set<String> = emptySet(),
    ) : CredentialProvider {

        val stored = mutableMapOf<String, String>()

        override fun store(key: CredentialKey, user: String, password: CharArray) {
            stored["${key.serviceName}|$user"] = password.concatToString()
        }

        override fun password(key: CredentialKey, user: String): CharArray? =
            stored["${key.serviceName}|$user"]?.toCharArray()

        override fun remove(key: CredentialKey, user: String) {
            if (key.datasourceId in failFor) {
                error("safe unavailable")
            }
            stored.remove("${key.serviceName}|$user")
        }
    }

    private fun store(root: Path) = WorkspaceStore(
        FileSystemStorageProvider(
            PrumoDirectories(
                config = root.resolve("config"),
                data = root.resolve("data"),
                cache = root.resolve("cache"),
            ),
        ),
    )

    private fun datasource(id: String, user: String) = DataSourceProfile(
        id = id,
        name = "Banco $id",
        host = "localhost",
        database = "folha",
        user = user,
    )

    private fun workspace(vararg datasources: DataSourceProfile) = Workspace(
        id = "folha-esocial",
        name = "folha-esocial",
        type = WorkspaceType.STANDALONE,
        repositories = listOf(
            RepositoryBinding(
                id = "folha",
                name = "folha",
                localPath = "C:/repos/folha",
                role = RepositoryRole.PRIMARY,
                accessMode = AccessMode.READ_WRITE,
            ),
        ),
        datasources = datasources.toList(),
        createdAt = "2026-09-06T00:00:00Z",
        updatedAt = "2026-09-06T00:00:00Z",
    )

    @Test
    fun `apaga os arquivos e as senhas dos bancos do workspace`(@TempDir root: Path) {
        val store = store(root)
        val credentials = MemoryCredentials()
        val workspace = workspace(datasource("teste", "postgres"), datasource("prod", "app"))
        store.save(workspace)
        credentials.store(CredentialKey(workspace.id, "teste"), "postgres", "s1".toCharArray())
        credentials.store(CredentialKey(workspace.id, "prod"), "app", "s2".toCharArray())

        val leftover = WorkspaceRemoval(store, credentials).erase(workspace)

        assertTrue(leftover.isEmpty(), "nada deveria ter sobrado: $leftover")
        assertNull(store.load(workspace.id))
        assertTrue(credentials.stored.isEmpty(), "senha sobrando: ${credentials.stored.keys}")
    }

    @Test
    fun `o banco recriado com o mesmo id nao herda a senha anterior`(@TempDir root: Path) {
        val store = store(root)
        val credentials = MemoryCredentials()
        val workspace = workspace(datasource("teste", "postgres"))
        store.save(workspace)
        credentials.store(CredentialKey(workspace.id, "teste"), "postgres", "antiga".toCharArray())

        WorkspaceRemoval(store, credentials).erase(workspace)

        assertNull(credentials.password(CredentialKey(workspace.id, "teste"), "postgres"))
    }

    @Test
    fun `senha que o cofre recusa apagar nao impede a remocao do workspace`(@TempDir root: Path) {
        val store = store(root)
        val credentials = MemoryCredentials(failFor = setOf("prod"))
        val workspace = workspace(datasource("teste", "postgres"), datasource("prod", "app"))
        store.save(workspace)
        credentials.store(CredentialKey(workspace.id, "teste"), "postgres", "s1".toCharArray())
        credentials.store(CredentialKey(workspace.id, "prod"), "app", "s2".toCharArray())

        val leftover = WorkspaceRemoval(store, credentials).erase(workspace)

        assertEquals(listOf("prod"), leftover)
        assertNull(store.load(workspace.id))
        assertEquals(1, credentials.stored.size)
    }

    @Test
    fun `workspace sem banco nenhum e apagado normalmente`(@TempDir root: Path) {
        val store = store(root)
        val credentials = MemoryCredentials()
        val workspace = workspace()
        store.save(workspace)

        assertTrue(WorkspaceRemoval(store, credentials).erase(workspace).isEmpty())
        assertNull(store.load(workspace.id))
    }
}
