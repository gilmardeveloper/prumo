package io.prumo.mcp.ui.tab

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ui.PrumoTab
import io.prumo.mcp.ui.WorkspaceLoader
import io.prumo.mcp.ui.WorkspaceViewModel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Aba cujo conteúdo vem do workspace do projeto aberto.
 *
 * Concentra o que toda aba desse tipo faz igual: ler o disco fora da thread de interface, mostrar o
 * andamento, tratar a falha, rolar o conteúdo que não cabe na janela e desistir quando a aba já foi
 * descartada. A subclasse só desenha o estado configurado.
 */
abstract class WorkspaceBackedTab(protected val project: Project) : PrumoTab {

    @Volatile
    private var disposed = false

    /** Desenha o conteúdo da aba com o workspace já resolvido. */
    protected abstract fun Panel.configured(model: WorkspaceViewModel.Configured)

    /**
     * A aba nasce dentro de um `JScrollPane`: o conteúdo cresce com o workspace — a trilha de
     * atividade sozinha chega a duzentas linhas — e sem rolagem o que passa da altura da janela fica
     * inalcançável.
     */
    final override fun createComponent(): JComponent {
        val container = ScrollableContent()
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
        return JBScrollPane(container).apply { border = JBUI.Borders.empty() }
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

    private fun Panel.failed(model: WorkspaceViewModel.Failed) {
        row { label(PrumoBundle.message(model.messageKey)) }
        row { comment(PrumoBundle.message("toolwindow.loadFailed.hint")) }
    }

    private fun Panel.notConfigured(model: WorkspaceViewModel.NotConfigured) {
        row { label(PrumoBundle.message("toolwindow.notConfigured.project", model.projectName)) }
        row { comment(PrumoBundle.message("toolwindow.notConfigured.explanation")) }
        row {
            button(PrumoBundle.message("toolwindow.notConfigured.action")) {
                io.prumo.mcp.ui.ConfigureWorkspaceAction.run(project)
            }
        }
    }

    private fun Panel.ambiguous(model: WorkspaceViewModel.Ambiguous) {
        row { label(PrumoBundle.message("toolwindow.ambiguous.project", model.projectName, model.workspaceCount)) }
        row { comment(PrumoBundle.message("toolwindow.ambiguous.explanation", model.workspaceCount)) }
    }
}
