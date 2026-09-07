package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.WorkspaceViewModel
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList

/**
 * O que os clientes de IA fizeram neste workspace.
 *
 * Mostra o que aconteceu, não o que foi lido: a trilha nunca guardou conteúdo de arquivo, linha de
 * resultado nem valor de parâmetro.
 */
class ActivityTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "activity"

    override val titleKey = "toolwindow.tab.activity"

    override val icon: Icon = AllIcons.General.InspectionsEye

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        if (model.activity.isEmpty()) {
            row { comment(PrumoBundle.message("toolwindow.activity.none")) }
            return
        }

        val entries = DefaultListModel<WorkspaceViewModel.ActivityRow>().apply {
            model.activity.asReversed().forEach(::addElement)
        }
        row {
            cell(JBList(entries).apply { cellRenderer = renderer() }).align(AlignX.FILL)
        }
        row { comment(PrumoBundle.message("toolwindow.activity.hint", model.activity.size)) }
    }

    private fun renderer() = object : ColoredListCellRenderer<WorkspaceViewModel.ActivityRow>() {
        override fun customizeCellRenderer(
            list: JList<out WorkspaceViewModel.ActivityRow>,
            value: WorkspaceViewModel.ActivityRow,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = iconFor(value.result)
            append(value.operation)
            append("  ${value.tool}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ${value.timestamp}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            append("  ${value.durationMillis} ms", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (value.result != SUCCESS) {
                append("  ${value.result}", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
    }

    private fun iconFor(result: String): Icon = when (result) {
        SUCCESS -> AllIcons.General.InspectionsOK
        DENIED -> AllIcons.General.Warning
        else -> AllIcons.General.Error
    }

    private companion object {
        const val SUCCESS = "SUCCESS"
        const val DENIED = "DENIED"
    }
}
