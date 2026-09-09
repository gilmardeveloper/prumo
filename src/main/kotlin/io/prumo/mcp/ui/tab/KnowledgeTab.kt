package io.prumo.mcp.ui.tab

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.ui.PrumoUiEvents
import io.prumo.mcp.ui.WorkspaceViewModel
import io.prumo.mcp.ui.pack.ApprovalQueuePanel
import io.prumo.mcp.ui.pack.PackExchangePanel
import io.prumo.mcp.ui.pack.showPackFailure
import javax.swing.Icon

/**
 * Packs instalados no workspace, propostas esperando decisão, e o que a IA alcança na documentação.
 *
 * Um pack proposto por um cliente de IA nunca fica ativo antes do aceite dado aqui, e o modelo da
 * busca por sentido nunca é baixado sem o botão desta tela.
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

        group(PrumoBundle.message("toolwindow.semantic.title")) {
            val busca = model.semanticSearch
            if (!busca.runtimeAvailable) {
                row { comment(PrumoBundle.message("toolwindow.semantic.unavailable")) }
                return@group
            }
            row {
                comment(
                    if (busca.installed) {
                        PrumoBundle.message("toolwindow.semantic.installed", megabytes(busca.bytes))
                    } else {
                        PrumoBundle.message("toolwindow.semantic.absent", megabytes(busca.bytes))
                    },
                )
            }
            row {
                if (busca.installed) {
                    button(PrumoBundle.message("toolwindow.semantic.remove")) { removeModel() }
                } else {
                    button(PrumoBundle.message("toolwindow.semantic.download")) { downloadModel() }
                }
            }
        }
    }

    /**
     * Busca o modelo fora da thread de interface, e traz a falha para a tela.
     *
     * Exceção lançada de dentro de um listener de botão não chega ao desenvolvedor: a IDE a engole
     * para o log e o botão parece não ter feito nada. E baixar mais de cem megabytes na thread de
     * interface congelaria a IDE inteira.
     */
    private fun downloadModel() {
        val service = PrumoWorkspaceService.getInstance()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.embeddingModels.install(service.embeddingModelDescriptor)
                service.forgetEmbedder()
                service.forgetIndexing()
                PrumoUiEvents.publishStateChanged()
            } catch (failure: Exception) {
                showPackFailure(project, failure, "toolwindow.tab.knowledge")
            }
        }
    }

    /**
     * Apaga o modelo e esquece o que foi indexado com ele.
     *
     * O embutidor é fechado antes de o arquivo sair: no Windows, apagar arquivo que ainda está aberto
     * falha, e a remoção pareceria não ter funcionado.
     */
    private fun removeModel() {
        val service = PrumoWorkspaceService.getInstance()
        try {
            service.forgetEmbedder()
            service.embeddingModels.remove(service.embeddingModelDescriptor)
            service.forgetIndexing()
            PrumoUiEvents.publishStateChanged()
        } catch (failure: Exception) {
            showPackFailure(project, failure, "toolwindow.tab.knowledge")
        }
    }

    private fun megabytes(bytes: Long): String = "%.0f".format(bytes / 1048576.0)
}
