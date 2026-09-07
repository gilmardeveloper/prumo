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
 * Repositórios vinculados ao workspace, com o papel e o modo de acesso de cada um.
 *
 * O modo de acesso é o que o Prumo impõe; o papel apenas descreve o repositório ao cliente de IA.
 */
class RepositoriesTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "repositories"

    override val titleKey = "toolwindow.tab.repositories"

    override val icon: Icon = AllIcons.Nodes.Module

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        if (model.repositories.isEmpty()) {
            row { comment(PrumoBundle.message("toolwindow.repositories.none")) }
            return
        }

        val repositories = DefaultListModel<WorkspaceViewModel.RepositoryRow>().apply {
            model.repositories.forEach(::addElement)
        }
        row {
            cell(JBList(repositories).apply { cellRenderer = renderer() }).align(AlignX.FILL)
        }
        row { comment(PrumoBundle.message("toolwindow.repositories.hint")) }
        row {
            button(PrumoBundle.message("toolwindow.edit")) { ConfigureWorkspaceAction.edit(project) }
        }
    }

    /** O repositório do projeto aberto vem em negrito: é o que as ferramentas usam por omissão. */
    private fun renderer() = object : ColoredListCellRenderer<WorkspaceViewModel.RepositoryRow>() {
        override fun customizeCellRenderer(
            list: JList<out WorkspaceViewModel.RepositoryRow>,
            value: WorkspaceViewModel.RepositoryRow,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = if (value.current) AllIcons.Nodes.Module else AllIcons.Nodes.Folder
            append(
                value.name,
                if (value.current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
            append(
                "  ${PrumoBundle.message(value.roleKey)} · ${PrumoBundle.message(value.accessModeKey)}",
                SimpleTextAttributes.GRAYED_ATTRIBUTES,
            )
        }
    }
}
