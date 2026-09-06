package io.prumo.mcp.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
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
import java.awt.Dimension
import java.awt.Toolkit
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent

/** Edição completa de um workspace: identidade, repositórios vinculados, documentação e políticas. */
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
        title = PrumoBundle.message("workspace.dialog.title")
        setOKButtonText(PrumoBundle.message("workspace.dialog.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = scrollable(form())

    /**
     * Envolve o formulario num painel rolavel limitado a parte da altura da tela.
     *
     * `DialogWrapper` dimensiona pelo tamanho preferido do conteudo e corta o que nao couber na
     * tela, sem oferecer gesto para alcancar o excedente. O teto so entra em acao quando o
     * formulario e mais alto que ele, entao tela grande continua sem barra.
     */
    private fun scrollable(form: JComponent): JComponent = JBScrollPane(form).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBar.unitIncrement = SCROLL_UNIT
        val ceiling = (Toolkit.getDefaultToolkit().screenSize.height * MAX_HEIGHT_RATIO).toInt()
        preferredSize = Dimension(
            form.preferredSize.width + verticalScrollBar.preferredSize.width,
            minOf(form.preferredSize.height, ceiling),
        )
    }

    private fun form(): JComponent = panel {
        row(PrumoBundle.message("workspace.field.name")) {
            textField().bindText(::workspaceName).columns(34).focused()
        }
        row(PrumoBundle.message("workspace.field.type")) {
            comboBox(
                WorkspaceType.entries,
                SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) },
            ).bindItem(
                { workspaceType },
                { workspaceType = it ?: WorkspaceType.STANDALONE },
            )
        }
        row { comment(PrumoBundle.message("workspace.typeHint"), maxLineLength = 72) }

        group(PrumoBundle.message("workspace.section.repositories")) {
            row {
                cell(repositoryList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(PrumoBundle.message("workspace.section.repositories.hint"))
            }
        }

        group(PrumoBundle.message("workspace.section.documentation")) {
            row {
                cell(documentationList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(PrumoBundle.message("workspace.section.documentation.hint"))
            }
        }

        group(PrumoBundle.message("workspace.section.datasources")) {
            row {
                cell(datasourceList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(PrumoBundle.message("workspace.section.datasources.hint"))
            }
        }

        group(PrumoBundle.message("workspace.section.policies")) {
            row { checkBox(PrumoBundle.message("workspace.policy.referenceWrite")).bindSelected(::referenceWrite) }
            row { checkBox(PrumoBundle.message("workspace.policy.databaseWrite")).bindSelected(::databaseWrite) }
            row { checkBox(PrumoBundle.message("workspace.policy.externalPathAccess")).bindSelected(::externalPathAccess) }
            row { checkBox(PrumoBundle.message("workspace.policy.processExecution")).bindSelected(::processExecution) }
            row { checkBox(PrumoBundle.message("workspace.policy.gitWrite")).bindSelected(::gitWrite) }
            row {
                comment(PrumoBundle.message("workspace.section.policies.hint"))
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

    /** Remove o perfil e a senha correspondente no cofre. */
    private fun removeDatasource(index: Int) {
        val profile = datasources.get(index)
        credentials.remove(io.prumo.mcp.credential.CredentialKey(original.id, profile.id), profile.user)
        datasources.remove(index)
    }

    private fun addRepository() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle(PrumoBundle.message("workspace.chooser.repository"))
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
            return
        }
        repositories.remove(index)
    }

    private fun addDocumentation() {
        val descriptor = FileChooserDescriptor(true, true, false, false, false, false)
            .withTitle(PrumoBundle.message("workspace.chooser.documentation"))
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
        com.intellij.ui.components.JBLabel(
            value.name + "  —  " + PrumoBundle.message(value.role.labelKey) + " · " +
                PrumoBundle.message(value.accessMode.labelKey),
        )
    }

    private fun documentationRenderer() = javax.swing.ListCellRenderer<DocumentationSource> { _, value, _, _, _ ->
        com.intellij.ui.components.JBLabel(
            value.name + "  —  " + PrumoBundle.message(value.kind.labelKey) + " · " +
                PrumoBundle.message(value.authority.labelKey),
        )
    }

    private fun datasourceRenderer() = javax.swing.ListCellRenderer<DataSourceProfile> { _, value, _, _, _ ->
        com.intellij.ui.components.JBLabel(
            value.name + "  —  PostgreSQL · " + PrumoBundle.message(value.accessMode.labelKey),
        )
    }

    private companion object {
        const val MAX_HEIGHT_RATIO = 0.75
        const val SCROLL_UNIT = 16
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
        title = PrumoBundle.message("workspace.repository.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(PrumoBundle.message("workspace.repository.field.repository")) { label(path.fileName?.toString() ?: id) }
        row(PrumoBundle.message("workspace.repository.field.role")) {
            comboBox(
                RepositoryRole.entries,
                SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) },
            ).bindItem({ role }, { role = it ?: RepositoryRole.REFERENCE })
        }
        row { comment(PrumoBundle.message("workspace.repository.roleHint"), maxLineLength = 62) }
        row(PrumoBundle.message("workspace.repository.field.access")) {
            comboBox(
                AccessMode.entries,
                SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) },
            ).bindItem({ accessMode }, { accessMode = it ?: AccessMode.READ_ONLY })
        }
        row(PrumoBundle.message("workspace.repository.field.branchPolicy")) {
            textField().bindText(::branchPolicy).columns(24)
        }
        row {
            comment(PrumoBundle.message("workspace.repository.hint"))
        }
    }

    fun toBinding(): RepositoryBinding {
        val remote = io.prumo.mcp.repository.GitRepositoryProbe.readRemoteFor(path)
        return RepositoryBinding(
            id = id,
            name = path.fileName?.toString() ?: id,
            localPath = path.toString(),
            gitRemote = remote,
            role = role,
            accessMode = accessMode,
            branchPolicy = branchPolicy.ifBlank { null },
            fingerprint = RepositoryFingerprint.forDirectory(path).value,
        )
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
    while (hasMoreElements()) add(nextElement())
}
