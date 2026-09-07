package io.prumo.mcp.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.settings.PrumoLanguageConfigurable
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Janela do Prumo: uma aba por domínio, montadas a partir de [PrumoTabRegistry].
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
        subscribe(project, toolWindow)
        install(project, toolWindow)
    }

    /**
     * Liga esta janela ao canal de mudança de estado.
     *
     * A assinatura vive enquanto a janela viver: `toolWindow.disposable` a desfaz quando o projeto
     * fecha, e sem isso um projeto fechado continuaria reagindo a eventos.
     */
    private fun subscribe(project: Project, toolWindow: ToolWindow) {
        ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
            .subscribe(
                PrumoUiEvents.STATE_CHANGED,
                object : PrumoStateListener {
                    override fun prumoStateChanged() {
                        ApplicationManager.getApplication().invokeLater { refresh(project, toolWindow) }
                    }
                },
            )
    }

    /** Caminho visível para a preferência de idioma, que de outro modo só existe em *Settings*. */
    private fun settingsAction(project: Project) =
        DumbAwareAction.create(PrumoBundle.message("toolwindow.settings")) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, PrumoLanguageConfigurable::class.java)
        }

    companion object {

        /**
         * Remonta a janela de um projeto.
         *
         * As abas são montadas uma vez, quando a janela nasce. Sem remontar, gravar o workspace ou
         * trocar o idioma deixa na tela o conteúdo anterior até a IDE reiniciar.
         */
        private fun refresh(project: Project, toolWindow: ToolWindow) {
            toolWindow.stripeTitle = PrumoBundle.message("toolwindow.title")
            toolWindow.contentManager.removeAllContents(true)
            install(project, toolWindow)
        }

        /**
         * Monta uma aba por entrada do registro.
         *
         * `setDisposer` amarra a aba ao conteúdo: quando a janela é remontada, `removeAllContents`
         * descarta as abas antigas antes de as novas nascerem.
         */
        private fun install(project: Project, toolWindow: ToolWindow) {
            val factory = ContentFactory.getInstance()
            PrumoTabRegistry.tabsFor(project).forEach { tab ->
                val content: Content = factory.createContent(
                    tab.createComponent(),
                    PrumoBundle.message(tab.titleKey),
                    false,
                )
                content.icon = tab.icon
                content.isCloseable = false
                content.setDisposer(tab)
                toolWindow.contentManager.addContent(content)
            }
        }
    }
}

/**
 * Aba do workspace: identidade, repositórios, políticas e packs do projeto aberto.
 *
 * A lista de todos os workspaces é tela administrativa e não passa por aqui: enxergar os demais
 * workspaces já é uma quebra de isolamento, mesmo sem acesso ao conteúdo deles.
 */
class WorkspaceTab(private val project: Project) : PrumoTab {

    override val id = "workspace"

    override val titleKey = "toolwindow.tab.workspace"

    override val icon: Icon = AllIcons.General.ProjectStructure

    private var disposed = false

    /**
     * Mostra o estado de carregamento e troca pelo conteúdo quando o disco responde.
     *
     * A leitura sai da thread de interface; a troca volta para ela, porque Swing só aceita mudança
     * de componente na EDT. Aba já descartada não é tocada: o carregamento pode terminar depois de
     * a janela ter sido remontada.
     */
    override fun createComponent(): JComponent {
        val container = JPanel(BorderLayout())
        container.add(render(WorkspaceViewModel.Loading), BorderLayout.CENTER)

        ApplicationManager.getApplication().executeOnPooledThread {
            val model = WorkspaceLoader.load(project)
            ApplicationManager.getApplication().invokeLater {
                if (!disposed) {
                    container.removeAll()
                    container.add(render(model), BorderLayout.CENTER)
                    container.revalidate()
                    container.repaint()
                }
            }
        }
        return container
    }

    override fun dispose() {
        disposed = true
    }

    private fun render(model: WorkspaceViewModel): JPanel = panel {
        when (model) {
            is WorkspaceViewModel.Loading -> row { label(PrumoBundle.message("toolwindow.loading")) }
            is WorkspaceViewModel.Failed -> failed(model)
            is WorkspaceViewModel.NotConfigured -> notConfigured(model)
            is WorkspaceViewModel.Ambiguous -> ambiguous(model)
            is WorkspaceViewModel.Configured -> configured(model)
        }
    }.apply {
        border = JBUI.Borders.empty(12)
    }

    private fun com.intellij.ui.dsl.builder.Panel.failed(model: WorkspaceViewModel.Failed) {
        row {
            label(PrumoBundle.message(model.messageKey))
        }
        row {
            comment(PrumoBundle.message("toolwindow.loadFailed.hint"))
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
