package io.prumo.mcp.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
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
import io.prumo.mcp.workspace.domain.RepositoryRole
import io.prumo.mcp.workspace.domain.Workspace
import io.prumo.mcp.workspace.domain.WorkspaceType
import java.awt.Dimension
import java.awt.Toolkit
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JComponent

/** Edição completa de um workspace: identidade, repositórios vinculados, documentação e políticas. */
class WorkspaceEditorDialog(
    private val project: Project,
    private val original: Workspace,
    private val primaryRepositoryId: String,
) : DialogWrapper(project) {

    private val nameField = JBTextField(original.name)

    private val typeBox = ComboBox(WorkspaceType.entries.toTypedArray()).apply {
        selectedItem = original.type
        renderer = SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) }
    }

    val workspaceName: String get() = nameField.text.trim()
    val workspaceType: WorkspaceType get() = typeBox.selectedItem as? WorkspaceType ?: original.type

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

    private val referenceWriteBox = policyBox("workspace.policy.referenceWrite", original.policies.referenceWrite)
    private val databaseWriteBox = policyBox("workspace.policy.databaseWrite", original.policies.databaseWrite)
    private val processExecutionBox = policyBox("workspace.policy.processExecution", original.policies.processExecution)
    private val gitWriteBox = policyBox("workspace.policy.gitWrite", original.policies.gitWrite)

    private fun policyBox(labelKey: String, selected: Boolean) =
        JBCheckBox(PrumoBundle.message(labelKey), selected)

    init {
        title = PrumoBundle.message("workspace.dialog.title")
        setOKButtonText(PrumoBundle.message("workspace.dialog.save"))
        init()
    }

    override fun createCenterPanel(): JComponent = scrollable(form())

    /**
     * Envolve o formulário num painel rolável limitado a parte da altura da tela.
     *
     * `DialogWrapper` dimensiona pelo tamanho preferido do conteúdo e corta o que não couber na
     * tela, sem oferecer gesto para alcançar o excedente. O teto só entra em ação quando o
     * formulário é mais alto que ele, então tela grande continua sem barra.
     *
     * Envolver o painel faz `DialogWrapper` deixar de reconhecê-lo como `DialogPanel`, e com isso
     * `apply()` nunca roda — por isso todo campo deste diálogo é lido do próprio componente.
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
            cell(nameField).columns(34).focused()
        }
        row(PrumoBundle.message("workspace.field.type")) { cell(typeBox) }
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
            row { cell(referenceWriteBox) }
            row { cell(databaseWriteBox) }
            row { cell(processExecutionBox) }
            row { cell(gitWriteBox) }
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
            referenceWrite = referenceWriteBox.isSelected,
            databaseWrite = databaseWriteBox.isSelected,
            processExecution = processExecutionBox.isSelected,
            gitWrite = gitWriteBox.isSelected,
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
    private val project: Project,
    private val id: String,
    private val path: Path,
    private val existing: RepositoryBinding? = null,
) : DialogWrapper(project) {

    private val roleBox = ComboBox(RepositoryRole.entries.toTypedArray()).apply {
        selectedItem = existing?.role ?: RepositoryRole.REFERENCE
        renderer = SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) }
    }

    private val accessModeBox = ComboBox(AccessMode.entries.toTypedArray()).apply {
        selectedItem = existing?.accessMode ?: AccessMode.READ_ONLY
        renderer = SimpleListCellRenderer.create("") { PrumoBundle.message(it.labelKey) }
    }

    private val branchPolicyField = JBTextField(existing?.branchPolicy.orEmpty())

    private val descriptionArea = JBTextArea(existing?.description.orEmpty(), DESCRIPTION_ROWS, DESCRIPTION_COLUMNS)
        .apply { lineWrap = true; wrapStyleWord = true }

    private val role: RepositoryRole get() = roleBox.selectedItem as? RepositoryRole ?: RepositoryRole.REFERENCE
    private val accessMode: AccessMode get() = accessModeBox.selectedItem as? AccessMode ?: AccessMode.READ_ONLY
    private val branchPolicy: String get() = branchPolicyField.text.trim()
    private val description: String get() = descriptionArea.text.trim()

    private val excluded = DefaultListModel<String>().apply {
        existing?.excludedPaths?.forEach(::addElement)
    }

    init {
        title = PrumoBundle.message("workspace.repository.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(PrumoBundle.message("workspace.repository.field.repository")) { label(path.fileName?.toString() ?: id) }
        row(PrumoBundle.message("workspace.repository.field.role")) { cell(roleBox) }
        row { comment(PrumoBundle.message("workspace.repository.roleHint"), maxLineLength = 62) }
        row(PrumoBundle.message("workspace.repository.field.access")) { cell(accessModeBox) }
        row(PrumoBundle.message("workspace.repository.field.branchPolicy")) {
            cell(branchPolicyField).columns(24)
        }
        row(PrumoBundle.message("workspace.repository.field.description")) {
            cell(JBScrollPane(descriptionArea))
        }
        row { comment(PrumoBundle.message("workspace.repository.descriptionHint"), maxLineLength = 62) }

        group(PrumoBundle.message("workspace.repository.section.excluded")) {
            row {
                cell(excludedList()).align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row {
                comment(PrumoBundle.message("workspace.repository.excludedHint"), maxLineLength = 62)
            }
        }
        row {
            comment(PrumoBundle.message("workspace.repository.hint"))
        }
    }

    override fun doValidate(): ValidationInfo? = when {
        description.length > RepositoryBinding.MAX_DESCRIPTION_LENGTH -> ValidationInfo(
            PrumoBundle.message(
                "workspace.repository.validation.description",
                RepositoryBinding.MAX_DESCRIPTION_LENGTH,
                description.length,
            ),
            descriptionArea,
        )

        else -> null
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
            description = description.ifBlank { null },
            excludedPaths = excluded.elements().toList(),
        )
    }

    /**
     * Lista editável dos caminhos que o Prumo recusa neste repositório.
     *
     * O `.git` não aparece aqui: ele é recusado sempre, sem depender de configuração.
     */
    private fun excludedList(): JComponent {
        val list = JBList(excluded).apply { visibleRowCount = 4 }
        return ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val digitado = Messages.showInputDialog(
                    project,
                    PrumoBundle.message("workspace.repository.excluded.prompt"),
                    PrumoBundle.message("workspace.repository.section.excluded"),
                    null,
                )?.trim().orEmpty()
                if (digitado.isNotBlank() && !excluded.contains(digitado)) {
                    excluded.addElement(digitado)
                }
            }
            .setRemoveAction { list.selectedIndex.takeIf { it >= 0 }?.let(excluded::remove) }
            .createPanel()
    }

    private companion object {
        const val DESCRIPTION_ROWS = 4
        const val DESCRIPTION_COLUMNS = 44
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
    while (hasMoreElements()) add(nextElement())
}
