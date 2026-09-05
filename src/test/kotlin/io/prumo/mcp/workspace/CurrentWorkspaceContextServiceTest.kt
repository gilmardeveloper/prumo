package io.prumo.mcp.workspace

import io.prumo.mcp.platform.PrumoDirectories
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.workspace.application.CurrentWorkspaceContextService
import io.prumo.mcp.workspace.application.ProjectDescriptor
import io.prumo.mcp.workspace.application.WorkspaceResolution
import io.prumo.mcp.workspace.application.WorkspaceResolutionException
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CurrentWorkspaceContextServiceTest {

    private fun store(root: Path) = WorkspaceStore(
        FileSystemStorageProvider(
            PrumoDirectories(root.resolve("config"), root.resolve("data"), root.resolve("cache")),
        ),
    )

    private fun binding(
        id: String,
        remote: String?,
        path: String,
        role: RepositoryRole = RepositoryRole.TARGET,
        access: AccessMode = AccessMode.READ_WRITE,
    ) = RepositoryBinding(
        id = id,
        name = id,
        localPath = path,
        gitRemote = remote,
        role = role,
        accessMode = access,
        fingerprint = RepositoryFingerprint.of(remote, Path.of(path)).value,
    )

    private fun workspace(id: String, repositories: List<RepositoryBinding>) = Workspace(
        id = id,
        name = "Workspace $id",
        type = WorkspaceType.MODERNIZATION,
        repositories = repositories,
        policies = WorkspacePolicies.DENY_ALL,
        createdAt = "2026-09-04T00:00:00Z",
        updatedAt = "2026-09-04T00:00:00Z",
    )

    @Test
    fun `resolve o workspace do projeto aberto`(@TempDir root: Path) {
        val store = store(root)
        store.save(
            workspace(
                "folha",
                listOf(
                    binding("consumidor", "git@github.com:org/consumidor.git", "/repos/consumidor"),
                    binding(
                        "legado",
                        "git@github.com:org/legado.git",
                        "/repos/legado",
                        RepositoryRole.LEGACY_REFERENCE,
                        AccessMode.READ_ONLY,
                    ),
                ),
            ),
        )
        val service = CurrentWorkspaceContextService(store)

        val context = service.require(
            ProjectDescriptor("consumidor", Path.of("/repos/consumidor"), "git@github.com:org/consumidor.git"),
        )

        assertEquals("folha", context.workspace.id)
        assertEquals("consumidor", context.currentRepository.id)
        assertEquals(listOf("legado"), context.relatedRepositories.map { it.id })
    }

    @Test
    fun `reconhece o projeto mesmo depois de mudar de diretorio`(@TempDir root: Path) {
        val store = store(root)
        store.save(
            workspace("folha", listOf(binding("consumidor", "git@github.com:org/consumidor.git", "C:/repos/consumidor"))),
        )
        val service = CurrentWorkspaceContextService(store)

        val context = service.require(
            ProjectDescriptor("consumidor", Path.of("/workspaces/consumidor"), "git@github.com:org/consumidor.git"),
        )

        assertEquals("folha", context.workspace.id)
    }

    @Test
    fun `projeto sem workspace configurado nao resolve`(@TempDir root: Path) {
        val service = CurrentWorkspaceContextService(store(root))

        val resolution = service.resolve(ProjectDescriptor("avulso", Path.of("/repos/avulso")))

        assertTrue(resolution is WorkspaceResolution.NotConfigured)
        val failure = assertThrows(WorkspaceResolutionException::class.java) {
            service.require(ProjectDescriptor("avulso", Path.of("/repos/avulso")))
        }
        assertTrue(failure.message!!.contains("not bound to any Prumo workspace"))
    }

    @Test
    fun `projeto vinculado a dois workspaces e recusado em vez de escolhido`(@TempDir root: Path) {
        val store = store(root)
        val repository = binding("consumidor", "git@github.com:org/consumidor.git", "/repos/consumidor")
        store.save(workspace("alpha", listOf(repository)))
        store.save(workspace("beta", listOf(repository)))
        val service = CurrentWorkspaceContextService(store)

        val resolution = service.resolve(
            ProjectDescriptor("consumidor", Path.of("/repos/consumidor"), "git@github.com:org/consumidor.git"),
        )

        assertTrue(resolution is WorkspaceResolution.Ambiguous)
        assertEquals(listOf("alpha", "beta"), (resolution as WorkspaceResolution.Ambiguous).workspaceIds)
        assertThrows(WorkspaceResolutionException::class.java) {
            service.require(
                ProjectDescriptor("consumidor", Path.of("/repos/consumidor"), "git@github.com:org/consumidor.git"),
            )
        }
    }

    @Test
    fun `o contexto de um workspace nao alcanca repositorio de outro`(@TempDir root: Path) {
        val store = store(root)
        store.save(workspace("alpha", listOf(binding("alpha-repo", "git@github.com:org/alpha.git", "/repos/alpha"))))
        store.save(workspace("beta", listOf(binding("beta-repo", "git@github.com:org/beta.git", "/repos/beta"))))
        val service = CurrentWorkspaceContextService(store)

        val context = service.require(
            ProjectDescriptor("alpha", Path.of("/repos/alpha"), "git@github.com:org/alpha.git"),
        )

        assertThrows(WorkspaceResolutionException::class.java) { context.repository("beta-repo") }
        assertTrue(context.workspace.repositories.none { it.id == "beta-repo" })
    }

    @Test
    fun `projeto sem diretorio nao resolve`(@TempDir root: Path) {
        val service = CurrentWorkspaceContextService(store(root))

        assertTrue(service.resolve(ProjectDescriptor("sem-path", null)) is WorkspaceResolution.NotConfigured)
    }

    @Test
    fun `as politicas do workspace acompanham o contexto`(@TempDir root: Path) {
        val store = store(root)
        val original = workspace("folha", listOf(binding("app", "git@github.com:org/app.git", "/repos/app")))
        store.save(original.copy(policies = WorkspacePolicies(databaseWrite = true)))
        val service = CurrentWorkspaceContextService(store)

        val context = service.require(
            ProjectDescriptor("app", Path.of("/repos/app"), "git@github.com:org/app.git"),
        )

        assertTrue(context.policies.databaseWrite)
        assertTrue(!context.policies.processExecution)
    }
}
