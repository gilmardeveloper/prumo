package io.prumo.mcp.ui

import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.settings.PrumoLanguageConfigurable
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Painel do Prumo, sempre restrito ao workspace do projeto aberto.
 *
 * A lista de todos os workspaces é tela administrativa e não passa por aqui: enxergar os demais
 * workspaces já é uma quebra de isolamento, mesmo sem acesso ao conteúdo deles.
 */
class PrumoToolWindowFactory : ToolWindowFactory {

    /**
     * O título da faixa é resolvido aqui, e não pelo `<resource-bundle>` do `plugin.xml`: aquele
     * obedece só ao idioma da IDE e ignoraria a preferência de idioma do próprio Prumo.
     */
    override fun init(toolWindow: ToolWindow) {
        toolWindow.stripeTitle = PrumoBundle.message("toolwindow.title")
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setAdditionalGearActions(DefaultActionGroup(settingsAction(project)))
        toolWindow.contentManager.addContent(content(project))
    }

    /** Caminho visível para a preferência de idioma, que de outro modo só existe em *Settings*. */
    private fun settingsAction(project: Project) =
        DumbAwareAction.create(PrumoBundle.message("toolwindow.settings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PrumoLanguageConfigurable::class.java)
        }

    companion object {
        private const val TOOL_WINDOW_ID = "Prumo MCP"

        /**
         * Remonta o painel dos projetos abertos.
         *
         * O painel é montado uma vez, quando a janela nasce. Sem remontar, gravar o workspace ou
         * trocar o idioma deixa na tela o conteúdo anterior até a IDE reiniciar.
         */
        fun refreshOpenProjects() {
            ProjectManager.getInstance().openProjects.forEach { project ->
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)
                    ?: return@forEach
                toolWindow.stripeTitle = PrumoBundle.message("toolwindow.title")
                toolWindow.contentManager.apply {
                    removeAllContents(true)
                    addContent(content(project))
                }
            }
        }

        private fun content(project: Project): Content =
            ContentFactory.getInstance().createContent(PrumoWorkspacePanel(project).component, null, false)
    }
}

class PrumoWorkspacePanel(private val project: Project) {

    val component: JComponent get() = render()

    private fun render(): JPanel {
        val service = PrumoWorkspaceService.getInstance()
        val resolution = service.resolve(project)
        val workspaceId = (resolution as? io.prumo.mcp.workspace.application.WorkspaceResolution.Resolved)
            ?.context?.workspace?.id
        val pending = workspaceId
            ?.let { io.prumo.mcp.pack.authoring.SubmissionQueue(service.storage).pending(it).size }
            ?: 0
        val installed = workspaceId
            ?.let { id ->
                io.prumo.mcp.pack.application.PackStore(service.storage).list(id).map {
                    WorkspaceViewModel.PackRow(it.id, it.title.default, it.version)
                }
            }
            .orEmpty()
        val model = WorkspaceViewModel.from(resolution, pending, installed)

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
            label(PrumoBundle.message("toolwindow.notConfigured.project", model.projectName))
        }
        row {
            comment(PrumoBundle.message("toolwindow.notConfigured.explanation"))
        }
        row {
            button(PrumoBundle.message("toolwindow.notConfigured.action")) { ConfigureWorkspaceAction.run(project) }
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.ambiguous(model: WorkspaceViewModel.Ambiguous) {
        row {
            label(PrumoBundle.message("toolwindow.ambiguous.project", model.projectName, model.workspaceCount))
        }
        row {
            comment(PrumoBundle.message("toolwindow.ambiguous.explanation", model.workspaceCount))
        }
    }

    private fun com.intellij.ui.dsl.builder.Panel.configured(model: WorkspaceViewModel.Configured) {
        row { label(model.workspaceName).bold() }
        row { comment(PrumoBundle.message(model.workspaceTypeKey)) }
        row {
            button(PrumoBundle.message("toolwindow.edit")) { ConfigureWorkspaceAction.edit(project) }
        }

        group(PrumoBundle.message("toolwindow.repositories")) {
            model.repositories.forEach { repository ->
                row {
                    val marker = if (repository.current) "▸ " else ""
                    cell(JBLabel("$marker${repository.name}"))
                    comment(
                        PrumoBundle.message(repository.roleKey) + " · " +
                            PrumoBundle.message(repository.accessModeKey),
                    )
                }
            }
        }

        group(PrumoBundle.message("toolwindow.policies")) {
            model.policies.forEach { policy ->
                row {
                    cell(JBLabel(PrumoBundle.message(policy.labelKey)))
                    comment(PrumoBundle.message(if (policy.allowed) "policy.allow" else "policy.deny"))
                }
            }
        }

        group(PrumoBundle.message("toolwindow.packs.title")) {
            io.prumo.mcp.ui.pack.PackExchangePanel(project, model.workspaceId, model.installedPacks).render(this)
        }

        if (model.pendingPacks > 0) {
            group(PrumoBundle.message("toolwindow.packs.waiting", model.pendingPacks)) {
                row {
                    cell(
                        io.prumo.mcp.ui.pack.ApprovalQueuePanel(project, model.workspaceId).component(),
                    ).align(com.intellij.ui.dsl.builder.AlignX.FILL)
                }
            }
        }
    }
}
