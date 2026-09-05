package io.prumo.mcp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.ide.PrumoWorkspaceService
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Painel do Prumo, sempre restrito ao workspace do projeto aberto.
 *
 * A lista de todos os workspaces é tela administrativa e não passa por aqui: enxergar os demais
 * workspaces já é uma quebra de isolamento, mesmo sem acesso ao conteúdo deles.
 */
class PrumoToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PrumoWorkspacePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

class PrumoWorkspacePanel(private val project: Project) {

    val component: JComponent get() = render()

    private fun render(): JPanel {
        val service = PrumoWorkspaceService.getInstance()
        val resolution = service.resolve(project)
        val pending = (resolution as? io.prumo.mcp.workspace.application.WorkspaceResolution.Resolved)
            ?.let { io.prumo.mcp.pack.authoring.SubmissionQueue(service.storage).pending(it.context.workspace.id).size }
            ?: 0
        val model = WorkspaceViewModel.from(resolution, pending)

        return panel {
            when (model) {
                is WorkspaceViewModel.NotConfigured -> notConfigured(model)
                is WorkspaceViewModel.Ambiguous -> ambiguous(model)
                is WorkspaceViewModel.Configured -> configured(model)
            }
        }.apply {
            border = JBUI.Borders.empty(12)
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.notConfigured(model: WorkspaceViewModel.NotConfigured) {
        row {
            label("The project “${model.projectName}” is not part of a Prumo workspace yet.")
        }
        row {
            comment(
                "A workspace is the boundary your AI client is allowed to work within: which " +
                    "repositories, which documentation, which databases — and what stays off limits.",
            )
        }
        row {
            button("Configure Workspace") { ConfigureWorkspaceAction.run(project) }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.ambiguous(model: WorkspaceViewModel.Ambiguous) {
        row {
            label("“${model.projectName}” is bound to ${model.workspaceCount} workspaces.")
        }
        row {
            comment(
                "Prumo will not pick one on your behalf, because the wrong choice would expose the " +
                    "wrong content. Remove the duplicate binding to continue.",
            )
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.configured(model: WorkspaceViewModel.Configured) {
        row { label(model.workspaceName).bold() }
        row { comment(model.workspaceType) }
        row {
            button("Edit Workspace") { ConfigureWorkspaceAction.edit(project) }
        }

        group("Repositories") {
            model.repositories.forEach { repository ->
                row {
                    val marker = if (repository.current) "▸ " else ""
                    cell(JBLabel("$marker${repository.name}"))
                    comment("${repository.role} · ${repository.accessMode}")
                }
            }
        }

        group("Policies") {
            model.policies.forEach { policy ->
                row {
                    cell(JBLabel(policy.label))
                    comment(if (policy.allowed) "ALLOW" else "DENY")
                }
            }
        }

        // A fila de aprovacao so aparece quando ha algo esperando decisao: painel que mostra area
        // vazia todo dia ensina o usuario a ignorar a area.
        if (model.pendingPacks > 0) {
            group("Prumo Packs waiting for you (${model.pendingPacks})") {
                row {
                    cell(
                        io.prumo.mcp.ui.pack.ApprovalQueuePanel(project, model.workspaceId).component(),
                    ).align(com.intellij.ui.dsl.builder.AlignX.FILL)
                }
            }
        }
    }
}
