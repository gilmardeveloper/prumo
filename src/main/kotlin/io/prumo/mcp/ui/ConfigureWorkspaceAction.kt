package io.prumo.mcp.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.workspace.application.ProjectDescriptor
import io.prumo.mcp.workspace.application.WorkspaceRemoval
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import java.nio.file.Path
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

    /**
     * Pede nome e tipo, e grava o workspace com o projeto aberto como repositório primário.
     *
     * Nome cujo identificador já pertence a um workspace leva à pergunta de sobrescrita. Recusada a
     * sobrescrita, o formulário reabre com o nome digitado, para que outro seja escolhido.
     */
    fun run(project: Project) {
        val service = PrumoWorkspaceService.getInstance()
        val descriptor = service.describe(project)
        val basePath = descriptor.basePath
        if (basePath == null) {
            Messages.showErrorDialog(
                project,
                PrumoBundle.message("workspace.error.noDirectory"),
                "Prumo MCP",
            )
            return
        }

        var suggestedName = descriptor.name
        while (true) {
            val dialog = ConfigureWorkspaceDialog(project, suggestedName)
            if (!dialog.showAndGet()) {
                return
            }

            val id = slug(dialog.workspaceName)
            val existing = service.store.load(id)
            if (existing != null) {
                if (!confirmOverwrite(project, existing.name)) {
                    suggestedName = dialog.workspaceName
                    continue
                }
                val leftover = WorkspaceRemoval(service.store, service.credentials).erase(existing)
                if (leftover.isNotEmpty()) {
                    leftover.forEach {
                        LOG.warn("Password left in the safe for datasource '${it.datasourceId}'.", it.cause)
                    }
                    Messages.showWarningDialog(
                        project,
                        PrumoBundle.message(
                            "workspace.overwrite.leftover",
                            leftover.joinToString(", ") { it.datasourceId },
                        ),
                        "Prumo MCP",
                    )
                }
            }

            create(service, id, dialog, descriptor, basePath)
            return
        }
    }

    /**
     * Pergunta se o workspace existente pode ser apagado para dar lugar a um novo.
     *
     * O botão padrão é o de cancelar: a confirmação apaga configuração e senhas sem volta.
     */
    private fun confirmOverwrite(project: Project, existingName: String): Boolean {
        val chosen = Messages.showDialog(
            project,
            PrumoBundle.message("workspace.overwrite.question", existingName),
            PrumoBundle.message("workspace.overwrite.title"),
            arrayOf(
                PrumoBundle.message("workspace.overwrite.confirm"),
                PrumoBundle.message("workspace.overwrite.cancel"),
            ),
            1,
            Messages.getWarningIcon(),
        )
        return chosen == 0
    }

    private fun create(
        service: PrumoWorkspaceService,
        id: String,
        dialog: ConfigureWorkspaceDialog,
        descriptor: ProjectDescriptor,
        basePath: Path,
    ) {
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
        PrumoUiEvents.publishStateChanged()
    }

    /**
     * Abre o editor do workspace já vinculado ao projeto aberto.
     *
     * Só edita o workspace corrente: não existe caminho por aqui para alcançar outro.
     */
    fun edit(project: Project) {
        val service = PrumoWorkspaceService.getInstance()
        val context = runCatching { service.require(project) }.getOrElse { failure ->
            Messages.showErrorDialog(project, failure.message ?: PrumoBundle.message("workspace.error.unresolved"), "Prumo MCP")
            return
        }

        val dialog = WorkspaceEditorDialog(project, context.workspace, context.currentRepository.id)
        if (dialog.showAndGet()) {
            service.store.save(dialog.toWorkspace(Instant.now().toString()))
            PrumoUiEvents.publishStateChanged()
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

    private val LOG = Logger.getInstance(ConfigureWorkspaceAction::class.java)
}

class ConfigureWorkspaceDialog(
    project: Project,
    suggestedName: String,
) : DialogWrapper(project) {

    private val nameField = JBTextField(suggestedName)

    private val typeBox = ComboBox(WorkspaceType.entries.toTypedArray()).apply {
        selectedItem = WorkspaceType.STANDALONE
        renderer = SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) }
    }

    val workspaceName: String get() = nameField.text.trim()
    val workspaceType: WorkspaceType get() = typeBox.selectedItem as? WorkspaceType ?: WorkspaceType.STANDALONE

    init {
        title = PrumoBundle.message("workspace.create.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(PrumoBundle.message("workspace.create.name")) {
            cell(nameField).columns(30).focused()
        }
        row(PrumoBundle.message("workspace.field.type")) { cell(typeBox) }
        row {
            comment(PrumoBundle.message("workspace.create.hint"))
        }
    }
}
