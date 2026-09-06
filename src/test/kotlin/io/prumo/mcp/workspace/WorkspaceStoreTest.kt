package io.prumo.mcp.workspace

import io.prumo.mcp.documentation.DocumentAuthority
import io.prumo.mcp.documentation.DocumentationKind
import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Tag("security")
class WorkspaceStoreTest {

    private fun store(root: Path) = WorkspaceStore(
        FileSystemStorageProvider(
            PrumoDirectories(
                config = root.resolve("config"),
                data = root.resolve("data"),
                cache = root.resolve("cache"),
            ),
        ),
    )

    private fun workspace(
        id: String,
        name: String = "Workspace $id",
        repositories: List<RepositoryBinding> = emptyList(),
    ) = Workspace(
        id = id,
        name = name,
        type = WorkspaceType.MODERNIZATION,
        repositories = repositories,
        createdAt = "2026-09-04T00:00:00Z",
        updatedAt = "2026-09-04T00:00:00Z",
    )

    private fun binding(
        id: String,
        role: RepositoryRole = RepositoryRole.PRIMARY,
        access: AccessMode = AccessMode.READ_WRITE,
        path: String = "/repos/$id",
    ) = RepositoryBinding(
        id = id,
        name = id,
        localPath = path,
        gitRemote = "git@example.com:org/$id.git",
        role = role,
        accessMode = access,
    )

    @Test
    fun `grava e recupera o workspace com seus repositorios`(@TempDir root: Path) {
        val store = store(root)
        val original = workspace(
            id = "modernizacao-folha",
            repositories = listOf(
                binding("consumidor"),
                binding("legado", RepositoryRole.LEGACY_REFERENCE, AccessMode.READ_ONLY),
            ),
        )

        store.save(original)

        assertEquals(original, store.load("modernizacao-folha"))
    }

    @Test
    fun `workspace inexistente devolve nulo`(@TempDir root: Path) {
        assertNull(store(root).load("nao-existe"))
    }

    @Test
    fun `o conteudo de um workspace nao aparece em outro`(@TempDir root: Path) {
        val store = store(root)
        store.save(workspace("alpha", "Alpha", listOf(binding("segredo-alpha"))))
        store.save(workspace("beta", "Beta", listOf(binding("segredo-beta"))))

        val beta = store.load("beta")!!

        assertEquals(listOf("segredo-beta"), beta.repositories.map { it.id })
        assertNull(beta.repository("segredo-alpha"))
        assertTrue(beta.repositories.none { it.localPath.contains("alpha") })
    }

    @Test
    fun `cada workspace ocupa o proprio diretorio`(@TempDir root: Path) {
        val store = store(root)
        val storage = FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        )
        store.save(workspace("alpha"))
        store.save(workspace("beta"))

        val alphaFiles = Files.walk(storage.workspaceRoot("alpha")).use { it.toList() }

        assertTrue(alphaFiles.none { it.toString().contains("beta") })
    }

    @Test
    fun `remocao apaga o workspace sem tocar nos demais`(@TempDir root: Path) {
        val store = store(root)
        store.save(workspace("alpha"))
        store.save(workspace("beta"))

        store.delete("alpha")

        assertNull(store.load("alpha"))
        assertEquals("Workspace beta", store.load("beta")?.name)
    }

    @Test
    fun `listagem administrativa enxerga todos, e so ela`(@TempDir root: Path) {
        val store = store(root)
        store.save(workspace("alpha", "Alpha"))
        store.save(workspace("beta", "Beta"))

        assertEquals(listOf("Alpha", "Beta"), store.listForAdministration().map { it.name })
    }

    @Test
    fun `identificador invalido e recusado antes de tocar o disco`(@TempDir root: Path) {
        val store = store(root)

        assertThrows(IllegalArgumentException::class.java) { store.load("../outro") }
        assertThrows(IllegalArgumentException::class.java) { store.load("a/b") }
    }

    @Test
    fun `repositorio duplicado no mesmo workspace e recusado`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            workspace("alpha", repositories = listOf(binding("mesmo"), binding("mesmo")))
        }

        assertTrue(failure.message!!.contains("mesmo"))
    }

    @Test
    fun `somente leitura nao e gravavel`() {
        assertTrue(!binding("legado", RepositoryRole.LEGACY_REFERENCE, AccessMode.READ_ONLY).writable)
        assertTrue(binding("alvo", RepositoryRole.PRIMARY, AccessMode.READ_WRITE).writable)
    }
}

class WorkspaceDocumentationPersistenceTest {

    @Test
    fun `documentacao e politicas sobrevivem ao ciclo de gravacao`(@TempDir root: Path) {
        val storage = FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        )
        val store = WorkspaceStore(storage)
        val original = Workspace(
            id = "folha",
            name = "Folha",
            type = WorkspaceType.MODERNIZATION,
            repositories = listOf(
                RepositoryBinding(
                    id = "app",
                    name = "app",
                    localPath = "/repos/app",
                    role = RepositoryRole.PRIMARY,
                    accessMode = AccessMode.READ_WRITE,
                ),
            ),
            documentation = listOf(
                DocumentationSource("regras", "Regras de Folha", DocumentationKind.FILE, "/docs/regras.md", DocumentAuthority.OFFICIAL),
            ),
            policies = WorkspacePolicies(databaseWrite = true, processExecution = true),
            createdAt = "2026-09-04T00:00:00Z",
            updatedAt = "2026-09-04T00:00:00Z",
        )

        store.save(original)

        assertEquals(original, store.load("folha"))
    }
}
