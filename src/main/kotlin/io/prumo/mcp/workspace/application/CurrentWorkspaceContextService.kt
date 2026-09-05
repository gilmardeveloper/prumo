package io.prumo.mcp.workspace.application

import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.infrastructure.WorkspaceStore
import java.nio.file.Path

/**
 * O projeto aberto na IDE, reduzido ao que o Prumo precisa para resolver o workspace.
 *
 * Existe para manter o serviço de contexto livre de tipos da IDE — o que permite testar toda a
 * regra de isolamento sem subir o ambiente.
 */
data class ProjectDescriptor(
    val name: String,
    val basePath: Path?,
    val gitRemote: String? = null,
)

/**
 * Contexto do workspace corrente, entregue a toda ferramenta do Prumo.
 */
data class WorkspaceContext(
    val workspace: Workspace,
    val currentRepository: RepositoryBinding,
) {
    val policies: WorkspacePolicies get() = workspace.policies

    val relatedRepositories: List<RepositoryBinding>
        get() = workspace.repositories.filter { it.id != currentRepository.id }

    fun repository(repositoryId: String): RepositoryBinding =
        workspace.repository(repositoryId)
            ?: throw WorkspaceResolutionException(
                "Repository '$repositoryId' is not bound to the current workspace.",
            )
}

class WorkspaceResolutionException(message: String) : IllegalStateException(message)

sealed interface WorkspaceResolution {

    data class Resolved(val context: WorkspaceContext) : WorkspaceResolution

    data class NotConfigured(val projectName: String) : WorkspaceResolution

    data class Ambiguous(val projectName: String, val workspaceIds: List<String>) : WorkspaceResolution
}

/**
 * Resolvedor único de contexto.
 *
 * Toda ferramenta do Prumo passa por aqui. Concentrar a resolução em um só lugar é o que torna o
 * isolamento verificável: não existe caminho alternativo que alcance um workspace sem esta decisão.
 *
 * A resolução falha fechada. Projeto sem workspace configurado é erro; projeto vinculado a mais de
 * um workspace também é — escolher um deles por conveniência exporia o conteúdo errado.
 */
class CurrentWorkspaceContextService(
    private val store: WorkspaceStore,
) {

    fun resolve(project: ProjectDescriptor): WorkspaceResolution {
        val fingerprint = project.basePath
            ?.let { RepositoryFingerprint.of(project.gitRemote, it) }
            ?: return WorkspaceResolution.NotConfigured(project.name)

        val matches = store.listForAdministration()
            .mapNotNull { store.load(it.id) }
            .mapNotNull { workspace ->
                workspace.repositories
                    .firstOrNull { it.matches(fingerprint) }
                    ?.let { workspace to it }
            }

        return when (matches.size) {
            0 -> WorkspaceResolution.NotConfigured(project.name)
            1 -> WorkspaceResolution.Resolved(
                WorkspaceContext(matches.single().first, matches.single().second),
            )
            else -> WorkspaceResolution.Ambiguous(
                project.name,
                matches.map { it.first.id }.sorted(),
            )
        }
    }

    fun require(project: ProjectDescriptor): WorkspaceContext =
        when (val resolution = resolve(project)) {
            is WorkspaceResolution.Resolved -> resolution.context
            is WorkspaceResolution.NotConfigured -> throw WorkspaceResolutionException(
                "The project '${resolution.projectName}' is not bound to any Prumo workspace. " +
                    "Configure the workspace in the Prumo MCP tool window first.",
            )
            is WorkspaceResolution.Ambiguous -> throw WorkspaceResolutionException(
                "The project '${resolution.projectName}' is bound to more than one Prumo workspace " +
                    "(${resolution.workspaceIds.joinToString(", ")}). Remove the duplicate binding: " +
                    "Prumo will not choose one on your behalf.",
            )
        }

    private fun RepositoryBinding.matches(fingerprint: RepositoryFingerprint): Boolean {
        val ownFingerprint = this.fingerprint
            ?: RepositoryFingerprint.of(gitRemote, Path.of(localPath)).value
        return ownFingerprint == fingerprint.value
    }
}
