package io.prumo.mcp.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.repository.GitRepositoryProbe
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.storage.LocalStorageProvider
import io.prumo.mcp.workspace.application.CurrentWorkspaceContextService
import io.prumo.mcp.workspace.application.ProjectDescriptor
import io.prumo.mcp.workspace.application.WorkspaceResolution
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import java.nio.file.Path

/**
 * Ponte entre a IDE e o núcleo do Prumo.
 *
 * É o único ponto em que um `Project` da plataforma vira o descritor neutro que o núcleo entende.
 * Manter essa conversão isolada é o que permite que toda a regra de isolamento seja testada sem a
 * IDE, e o que limita o estrago quando a API da plataforma muda entre versões.
 */
@Service(Service.Level.APP)
class PrumoWorkspaceService {

    val storage: LocalStorageProvider = FileSystemStorageProvider.forCurrentSystem()
    val store: WorkspaceStore = WorkspaceStore(storage)
    val audit: AuditLog = AuditLog(storage)
    private val contextService = CurrentWorkspaceContextService(store)

    fun describe(project: Project): ProjectDescriptor {
        val basePath = project.basePath?.let(Path::of)
        val repositoryRoot = basePath?.let(GitRepositoryProbe::findRepositoryRoot)
        return ProjectDescriptor(
            name = project.name,
            basePath = repositoryRoot ?: basePath,
            gitRemote = repositoryRoot?.let(GitRepositoryProbe::readOriginRemote),
        )
    }

    fun resolve(project: Project): WorkspaceResolution = contextService.resolve(describe(project))

    fun require(project: Project) = contextService.require(describe(project))

    companion object {
        fun getInstance(): PrumoWorkspaceService = service()
    }
}
