package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.ConfigureWorkspaceAction
import io.prumo.mcp.ui.WorkspaceViewModel
import io.prumo.mcp.ui.pack.ApprovalQueuePanel
import io.prumo.mcp.ui.pack.PackExchangePanel
import javax.swing.Icon

/**
 * Identidade do workspace e as políticas que valem nele.
 *
 * A lista de todos os workspaces é tela administrativa e não passa por aqui: enxergar os demais
 * workspaces já é uma quebra de isolamento, mesmo sem acesso ao conteúdo deles.
 */
class WorkspaceTab(project: Project) : WorkspaceBackedTab(project) {

    override val id = "workspace"

    override val titleKey = "toolwindow.tab.workspace"

    override val icon: Icon = AllIcons.General.ProjectStructure

    override fun Panel.configured(model: WorkspaceViewModel.Configured) {
        row { label(model.workspaceName).bold() }
        row { comment(PrumoBundle.message(model.workspaceTypeKey)) }
        row {
            button(PrumoBundle.message("toolwindow.edit")) { ConfigureWorkspaceAction.edit(project) }
        }

        group(PrumoBundle.message("toolwindow.policies")) {
            model.policies.forEach { policy ->
                row {
                    cell(JBLabel(PrumoBundle.message(policy.labelKey)))
                    comment(PrumoBundle.message(if (policy.allowed) "policy.allow" else "policy.deny"))
                }
            }
        }

        packs(model)
    }

    /** Os packs seguem aqui até a aba de conhecimento existir; sem isso ficariam sem porta de entrada. */
    private fun Panel.packs(model: WorkspaceViewModel.Configured) {
        group(PrumoBundle.message("toolwindow.packs.title")) {
            PackExchangePanel(project, model.workspaceId, model.installedPacks).render(this)
        }

        if (model.pendingPacks > 0) {
            group(PrumoBundle.message("toolwindow.packs.waiting", model.pendingPacks)) {
                row {
                    cell(ApprovalQueuePanel(project, model.workspaceId).component()).align(AlignX.FILL)
                }
            }
        }
    }
}
