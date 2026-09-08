package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoProjectContext
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.ui.PrumoUiEvents
import io.prumo.mcp.ui.WorkspaceViewModel
import io.prumo.mcp.ui.pack.showPackFailure
import io.prumo.mcp.workspace.application.WorkspaceResolution
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JList

/**
 * O que os clientes de IA destilaram e guardaram neste workspace.
 *
 * Esta é a única superfície em que a IA grava sem passar pelo desenvolvedor, então é aqui que ele vê
 * o que foi gravado, por qual cliente, de que fonte — e apaga o que não quiser manter.
 */
class MemoryTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "memory"

    override val titleKey = "toolwindow.tab.memory"

    override val icon: Icon = AllIcons.Nodes.DataTables

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        if (model.memory.isEmpty()) {
            row { comment(PrumoBundle.message("toolwindow.memory.none")) }
            return
        }

        val entries = DefaultListModel<WorkspaceViewModel.MemoryRow>().apply {
            model.memory.forEach(::addElement)
        }
        val list = JBList(entries).apply { cellRenderer = renderer() }

        row { comment(PrumoBundle.message("toolwindow.memory.hint", model.memory.size)) }
        row { cell(list).align(AlignX.FILL) }
        row {
            button(PrumoBundle.message("toolwindow.memory.forget")) {
                list.selectedValue?.let { forget(it.knowledgeId) }
            }
        }
    }

    /**
     * Remove o registro e republica o estado.
     *
     * Exceção lançada de dentro de um listener de botão não chega à tela: a IDE a engole para o log
     * e o item simplesmente não some. Por isso a falha é capturada e vira notificação.
     */
    private fun forget(knowledgeId: String) {
        try {
            val resolution = PrumoProjectContext.getInstance(project).resolve()
            val workspaceId = (resolution as? WorkspaceResolution.Resolved)?.context?.workspace?.id ?: return
            PrumoWorkspaceService.getInstance().knowledge.remove(workspaceId, knowledgeId)
            PrumoUiEvents.publishStateChanged()
        } catch (failure: Exception) {
            showPackFailure(project, failure, "toolwindow.tab.memory")
        }
    }

    private fun renderer() = object : ColoredListCellRenderer<WorkspaceViewModel.MemoryRow>() {
        override fun customizeCellRenderer(
            list: JList<out WorkspaceViewModel.MemoryRow>,
            value: WorkspaceViewModel.MemoryRow,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            icon = iconFor(value.state)
            append(value.title)
            append("  ${value.sourceId}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append("  ${value.author}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            append("  ${value.updatedAt}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            if (value.state != FRESH) {
                append("  ${labelFor(value.state)}", SimpleTextAttributes.ERROR_ATTRIBUTES)
            }
        }
    }

    private fun labelFor(state: String): String =
        if (state == OUT_OF_REACH) PrumoBundle.message("toolwindow.memory.outOfReach") else state

    private fun iconFor(state: String): Icon = when (state) {
        FRESH -> AllIcons.General.InspectionsOK
        ORPHAN, OUT_OF_REACH -> AllIcons.General.Error
        else -> AllIcons.General.Warning
    }

    private companion object {
        const val FRESH = "FRESH"
        const val ORPHAN = "ORPHAN"
        const val OUT_OF_REACH = WorkspaceViewModel.MemoryRow.OUT_OF_REACH
    }
}
