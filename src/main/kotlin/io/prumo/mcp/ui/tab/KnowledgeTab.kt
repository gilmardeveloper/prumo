package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.WorkspaceViewModel
import io.prumo.mcp.ui.pack.ApprovalQueuePanel
import io.prumo.mcp.ui.pack.PackExchangePanel
import javax.swing.Icon

/**
 * Packs instalados no workspace e propostas esperando decisão.
 *
 * Um pack proposto por um cliente de IA nunca fica ativo antes do aceite dado aqui.
 */
class KnowledgeTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "knowledge"

    override val titleKey = "toolwindow.tab.knowledge"

    override val icon: Icon = AllIcons.Nodes.Folder

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        group(PrumoBundle.message("toolwindow.packs.title")) {
            PackExchangePanel(project, model.workspaceId, model.installedPacks).render(this)
        }

        group(PrumoBundle.message("toolwindow.packs.queue")) {
            ApprovalQueuePanel(project, model.workspaceId).render(this)
        }

        group(PrumoBundle.message("toolwindow.documentation.title")) {
            if (model.documentation.isEmpty()) {
                row { comment(PrumoBundle.message("toolwindow.documentation.none")) }
                return@group
            }
            row { comment(PrumoBundle.message("toolwindow.documentation.hint")) }
            model.documentation.forEach { fonte ->
                row(fonte.name) {
                    val estado = when {
                        !fonte.readable -> PrumoBundle.message("toolwindow.documentation.unreadable")
                        fonte.indexedPassages == 0 -> PrumoBundle.message("toolwindow.documentation.notIndexed")
                        else -> PrumoBundle.message("toolwindow.documentation.indexed", fonte.indexedPassages)
                    }
                    comment(estado)
                }
            }
        }
    }
}
