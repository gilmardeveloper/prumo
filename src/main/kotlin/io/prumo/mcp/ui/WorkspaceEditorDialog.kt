package io.prumo.mcp.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.credential.PasswordSafeCredentialProvider
import io.prumo.mcp.datasource.domain.DataSourceProfile
import io.prumo.mcp.documentation.DocumentAuthority
import io.prumo.mcp.documentation.DocumentationKind
import io.prumo.mcp.documentation.DocumentationSource
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.repository.RepositoryFingerprint
import io.prumo.mcp.ui.datasource.DataSourceDialog
import io.prumo.mcp.workspace.domain.AccessMode
import io.prumo.mcp.workspace.domain.RepositoryBinding
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent

/**
 * Edição completa de um workspace: identidade, repositórios vinculados, documentação e políticas.
 *
 * É um formulário com seções, e não um assistente de vários passos, porque o desenvolvedor volta
 * aqui com frequência para ajustar um vínculo — e reabrir seis telas para trocar um `accessMode`
 * seria hostil. A ordem das seções segue a ordem em que as decisões acontecem.
 */
class WorkspaceEditorDialog(
    private val project: Project,
    private val original: Workspace,
    private val primaryRepositoryId: String,
) : DialogWrapper(project) {

    var workspaceName: String = original.name
    var workspaceType: WorkspaceType = original.type

    private val repositories = DefaultListModel<RepositoryBinding>().apply {
        original.repositories.forEach(::addElement)
    }
    private val documentation = DefaultListModel<DocumentationSource>().apply {
        original.documentation.forEach(::addElement)
    }
    private val datasources = DefaultListModel<DataSourceProfile>().apply {
        original.datasources.forEach(::addElement)
    }
    private val credentials = PasswordSafeCredentialProvider()

    private var referenceWrite = original.policies.referenceWrite
    private var databaseWrite = original.policies.databaseWrite
    private var externalPathAccess = original.policies.externalPathAccess
    private var processExecution = original.policies.processExecution
    private var gitWrite = original.policies.gitWrite

    init {
        title = "Prumo Workspace"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            textField().bindText(::workspaceName).columns(34).focused()
        }
        row("Type:") {
            comboBox(WorkspaceType.entries).bindItem(
                { workspaceType },
                { workspaceType = it ?: WorkspaceType.STANDALONE },
            )
        }

        group("Repositories") {
            row {
                cell(repositoryList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(
                    "A repository bound as READ_ONLY stays read-only for every tool, no matter what " +
                        "the AI client asks for.",
                )
            }
        }

        group("Documentation") {
            row {
                cell(documentationList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment("Markdown, TXT, JSON and YAML are read as text. PDF is catalogued only in this version.")
            }
        }

        group("Data sources") {
            row {
                cell(datasourceList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(
                    "PostgreSQL in this version. The password goes to the IDE password safe; the " +
                        "workspace file keeps only how to reach the database.",
                )
            }
        }

        group("Policies") {
            row { checkBox("Allow writes to reference repositories").bindSelected(::referenceWrite) }
            row { checkBox("Allow database writes").bindSelected(::databaseWrite) }
            row { checkBox("Allow access to paths outside bound repositories").bindSelected(::externalPathAccess) }
            row { checkBox("Allow process execution from user resources").bindSelected(::processExecution) }
            row { checkBox("Allow Git write operations").bindSelected(::gitWrite) }
            row {
                comment("Everything is denied by default. Each of these is a deliberate decision.")
            }
        }
    }.apply { border = JBUI.Borders.empty(8) }

    private fun repositoryList(): JComponent {
        val list = JBList(repositories).apply {
            cellRenderer = repositoryRenderer()
            visibleRowCount = 5
        }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction { addRepository() }
            .setEditAction { list.selectedIndex.takeIf { it >= 0 }?.let(::editRepository) }
            .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let(::removeRepository) }
            .createPanel()
    }

    private fun documentationList(): JComponent {
        val list = JBList(documentation).apply {
            cellRenderer = documentationRenderer()
            visibleRowCount = 4
        }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction { addDocumentation() }
            .setRemoveAction {
                list.selectedIndex.takeIf { it >= 0 }?.let(documentation::remove)
            }
            .createPanel()
    }

    private fun datasourceList(): JComponent {
        val list = JBList(datasources).apply {
            cellRenderer = datasourceRenderer()
            visibleRowCount = 4
        }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction { addDatasource() }
            .setEditAction { list.selectedIndex.takeIf { it >= 0 }?.let(::editDatasource) }
            .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let(::removeDatasource) }
            .createPanel()
    }

    private fun addDatasource() {
        val dialog = DataSourceDialog(project, original.id, credentials = credentials)
        if (!dialog.showAndGet()) {
            return
        }
        val profile = dialog.toProfile()
        if (datasources.elements().toList().any { it.id == profile.id }) {
            return
        }
        datasources.addElement(profile)
    }

    private fun editDatasource(index: Int) {
        val dialog = DataSourceDialog(project, original.id, datasources.get(index), credentials)
        if (!dialog.showAndGet()) {
            return
        }
        datasources.set(index, dialog.toProfile())
    }

    /**
     * Remover o perfil apaga também a senha: deixar a credencial órfã no cofre seria guardar um
     * segredo que ninguém mais sabe explicar.
     */
    private fun removeDatasource(index: Int) {
        val profile = datasources.get(index)
        credentials.remove(io.prumo.mcp.credential.CredentialKey(original.id, profile.id), profile.user)
        datasources.remove(index)
    }

    private fun addRepository() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Select Repository Directory")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = Path.of(chosen.path)
        val id = ConfigureWorkspaceAction.slug(path.fileName?.toString() ?: chosen.name)
        if (repositories.elements().toList().any { it.id == id }) {
            return
        }

        val binding = RepositoryBindingDialog(project, id, path).let { dialog ->
            if (!dialog.showAndGet()) return
            dialog.toBinding()
        }
        repositories.addElement(binding)
    }

    private fun editRepository(index: Int) {
        val current = repositories.get(index)
        val dialog = RepositoryBindingDialog(project, current.id, Path.of(current.localPath), current)
        if (!dialog.showAndGet()) {
            return
        }
        repositories.set(index, dialog.toBinding())
    }

    private fun removeRepository(index: Int) {
        val binding = repositories.get(index)
        if (binding.id == primaryRepositoryId) {
            // O repositório do projeto aberto é o que amarra o workspace ao que está na tela;
            // removê-lo deixaria a configuração sem âncora.
            return
        }
        repositories.remove(index)
    }

    private fun addDocumentation() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
            .withTitle("Select Documentation File or Folder")
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val path = Path.of(chosen.path)
        val id = ConfigureWorkspaceAction.slug(path.fileName?.toString() ?: chosen.name)
        if (documentation.elements().toList().any { it.id == id }) {
            return
        }
        documentation.addElement(
            DocumentationSource(
                id = id,
                name = path.fileName?.toString() ?: chosen.name,
                kind = if (chosen.isDirectory) DocumentationKind.DIRECTORY else DocumentationKind.FILE,
                location = chosen.path,
                authority = DocumentAuthority.REFERENCE,
            ),
        )
    }

    fun toWorkspace(now: String): Workspace = original.copy(
        name = workspaceName.ifBlank { original.name },
        type = workspaceType,
        repositories = repositories.elements().toList(),
        documentation = documentation.elements().toList(),
        datasources = datasources.elements().toList(),
        policies = WorkspacePolicies(
            referenceWrite = referenceWrite,
            databaseWrite = databaseWrite,
            externalPathAccess = externalPathAccess,
            processExecution = processExecution,
            gitWrite = gitWrite,
        ),
        updatedAt = now,
    )

    private fun repositoryRenderer() = javax.swing.ListCellRenderer<RepositoryBinding> { _, value, _, _, _ ->
        com.intellij.ui.components.JBLabel("${value.name}  —  ${value.role} · ${value.accessMode}")
    }

    private fun documentationRenderer() = javax.swing.ListCellRenderer<DocumentationSource> { _, value, _, _, _ ->
        com.intellij.ui.components.JBLabel("${value.name}  —  ${value.kind} · ${value.authority}")
    }

    private fun datasourceRenderer() = javax.swing.ListCellRenderer<DataSourceProfile> { _, value, _, _, _ ->
        com.intellij.ui.components.JBLabel("${value.name}  —  PostgreSQL · ${value.accessMode}")
    }
}

class RepositoryBindingDialog(
    project: Project,
    private val id: String,
    private val path: Path,
    private val existing: RepositoryBinding? = null,
) : DialogWrapper(project) {

    private var role: RepositoryRole = existing?.role ?: RepositoryRole.REFERENCE
    private var accessMode: AccessMode = existing?.accessMode ?: AccessMode.READ_ONLY
    private var branchPolicy: String = existing?.branchPolicy.orEmpty()

    init {
        title = "Repository Binding"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Repository:") { label(path.fileName?.toString() ?: id) }
        row("Role:") {
            comboBox(RepositoryRole.entries).bindItem({ role }, { role = it ?: RepositoryRole.REFERENCE })
        }
        row("Access:") {
            comboBox(AccessMode.entries).bindItem({ accessMode }, { accessMode = it ?: AccessMode.READ_ONLY })
        }
        row("Branch policy:") {
            textField().bindText(::branchPolicy).columns(24)
        }
        row {
            comment("New bindings default to READ_ONLY. Granting write access is an explicit choice.")
        }
    }

    fun toBinding(): RepositoryBinding {
        val remote = io.prumo.mcp.repository.GitRepositoryProbe.readOriginRemote(path)
        return RepositoryBinding(
            id = id,
            name = path.fileName?.toString() ?: id,
            localPath = path.toString(),
            gitRemote = remote,
            role = role,
            accessMode = accessMode,
            branchPolicy = branchPolicy.ifBlank { null },
            fingerprint = RepositoryFingerprint.of(remote, path).value,
        )
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
    while (hasMoreElements()) add(nextElement())
}
