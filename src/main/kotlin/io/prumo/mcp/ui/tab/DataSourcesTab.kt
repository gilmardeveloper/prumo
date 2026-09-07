package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.ConfigureWorkspaceAction
import io.prumo.mcp.ui.WorkspaceViewModel
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList

/**
 * Bancos vinculados ao workspace.
 *
 * Mostra o que decide o alcance da IA — modo de acesso, schema padrão e ofuscação de dado pessoal —
 * e nada que identifique o servidor.
 */
class DataSourcesTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "datasources"

    override val titleKey = "toolwindow.tab.datasources"

    override val icon: Icon = AllIcons.Nodes.DataTables

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        if (model.dataSources.isEmpty()) {
            row { comment(PrumoBundle.message("toolwindow.datasources.none")) }
            row {
                button(PrumoBundle.message("toolwindow.edit")) { ConfigureWorkspaceAction.edit(project) }
            }
            return
        }

        val sources = DefaultListModel<WorkspaceViewModel.DataSourceRow>().apply {
            model.dataSources.forEach(::addElement)
        }
        row {
            cell(JBList(sources).apply { cellRenderer = renderer() }).align(AlignX.FILL)
        }
        row { comment(PrumoBundle.message("toolwindow.datasources.hint")) }
        row {
            button(PrumoBundle.message("toolwindow.edit")) { ConfigureWorkspaceAction.edit(project) }
        }
    }

    /**
     * Banco sem ofuscação de dado pessoal recebe o ícone de atenção: é a diferença entre a IA
     * receber um CPF mascarado e recebê-lo inteiro.
     */
    private fun renderer() = object : ColoredListCellRenderer<WorkspaceViewModel.DataSourceRow>() {
        override fun customizeCellRenderer(
            list: JList<out WorkspaceViewModel.DataSourceRow>,
            value: WorkspaceViewModel.DataSourceRow,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = if (value.personalDataObfuscated) AllIcons.Nodes.DataTables else AllIcons.General.Warning
            append(value.name)
            append("  ${PrumoBundle.message(value.accessModeKey)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            value.defaultSchema?.takeIf { it.isNotBlank() }?.let { schema ->
                append("  ·  $schema", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            if (!value.personalDataObfuscated) {
                append(
                    "  ${PrumoBundle.message("toolwindow.datasources.exposed")}",
                    SimpleTextAttributes.ERROR_ATTRIBUTES,
                )
            }
        }
    }
}
