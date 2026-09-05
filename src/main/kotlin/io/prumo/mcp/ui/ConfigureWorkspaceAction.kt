package io.prumo.mcp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import java.time.Instant
import java.util.Locale
import javax.swing.JComponent

/**
 * Cria o workspace do projeto aberto.
 *
 * Nesta etapa cadastra o projeto corrente como repositório primário. Os repositórios relacionados,
 * a documentação e as fontes de dados entram pelo assistente completo.
 */
object ConfigureWorkspaceAction {

    fun run(project: Project) {
        val service = PrumoWorkspaceService.getInstance()
        val descriptor = service.describe(project)
        val basePath = descriptor.basePath
        if (basePath == null) {
            Messages.showErrorDialog(
                project,
                "Prumo could not determine this project's directory, so it cannot bind a repository.",
                "Prumo MCP",
            )
            return
        }

        val dialog = ConfigureWorkspaceDialog(project, descriptor.name)
        if (!dialog.showAndGet()) {
            return
        }

        val id = slug(dialog.workspaceName)
        if (service.store.load(id) != null) {
            Messages.showErrorDialog(
                project,
                "A workspace named “${dialog.workspaceName}” already exists.",
                "Prumo MCP",
            )
            return
        }

        val now = Instant.now().toString()
        service.store.save(
            Workspace(
                id = id,
                name = dialog.workspaceName,
                type = dialog.workspaceType,
                repositories = listOf(
                    RepositoryBinding(
                        id = slug(descriptor.name),
                        name = descriptor.name,
                        localPath = basePath.toString(),
                        gitRemote = descriptor.gitRemote,
                        role = RepositoryRole.PRIMARY,
                        accessMode = AccessMode.READ_WRITE,
                        fingerprint = RepositoryFingerprint.of(descriptor.gitRemote, basePath).value,
                    ),
                ),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Abre o editor do workspace já vinculado ao projeto aberto.
     *
     * Só edita o workspace corrente: não existe caminho por aqui para alcançar outro.
     */
    fun edit(project: Project) {
        val service = PrumoWorkspaceService.getInstance()
        val context = runCatching { service.require(project) }.getOrElse { failure ->
            Messages.showErrorDialog(project, failure.message ?: "Unresolved workspace.", "Prumo MCP")
            return
        }

        val dialog = WorkspaceEditorDialog(project, context.workspace, context.currentRepository.id)
        if (dialog.showAndGet()) {
            service.store.save(dialog.toWorkspace(Instant.now().toString()))
        }
    }

    /** O identificador vira nome de diretório, então precisa ser restrito antes de tocar o disco. */
    internal fun slug(name: String): String {
        val normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(64)
        return normalized.ifEmpty { "workspace-${System.currentTimeMillis()}" }
    }
}

class ConfigureWorkspaceDialog(
    project: Project,
    suggestedName: String,
) : DialogWrapper(project) {

    var workspaceName: String = suggestedName
    var workspaceType: WorkspaceType = WorkspaceType.STANDALONE

    init {
        title = "Configure Prumo Workspace"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Workspace name:") {
            textField()
                .bindText(::workspaceName)
                .columns(30)
                .focused()
        }
        row("Type:") {
            comboBox(WorkspaceType.entries).bindItem(
                { workspaceType },
                { workspaceType = it ?: WorkspaceType.STANDALONE },
            )
        }
        row {
            comment(
                "The open project is bound as the primary repository. Related repositories, " +
                    "documentation and databases are added afterwards.",
            )
        }
    }
}
