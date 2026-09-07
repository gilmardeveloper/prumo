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
