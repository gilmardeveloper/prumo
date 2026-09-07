package io.prumo.mcp.ui.pack

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.authoring.PackSubmission
import io.prumo.mcp.pack.authoring.SubmissionQueue
import io.prumo.mcp.pack.exchange.PackImporter
import io.prumo.mcp.pack.exchange.PackOrigin
import io.prumo.mcp.ui.PrumoToolWindowFactory
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.ListCellRenderer

/**
 * Fila de packs propostos por um cliente MCP, esperando o desenvolvedor.
 *
 * É o único ponto do produto que ativa um recurso de usuário.
 */
class ApprovalQueuePanel(
    private val project: Project,
    private val workspaceId: String,
) {

    private val submissions = DefaultListModel<PackSubmission>()
    private val status = JBLabel(" ")

    fun component(): JComponent {
        refresh()
        return panel {
            row { label(PrumoBundle.message("pack.queue.title")) }
            row {
                cell(JBList(submissions).apply { cellRenderer = renderer() }).align(AlignX.FILL)
            }
            row {
                button(PrumoBundle.message("pack.queue.review")) { review() }
                button(PrumoBundle.message("pack.queue.discard")) { discard() }
                cell(status)
            }
            row {
                comment(PrumoBundle.message("pack.queue.hint"))
            }
        }
    }

    private fun refresh() {
        submissions.clear()
        SubmissionQueue(PrumoWorkspaceService.getInstance().storage).pending(workspaceId).forEach(submissions::addElement)
    }

    private fun selected(): PackSubmission? = submissions.elements().toList().firstOrNull()

    /**
     * Revisar abre o termo de consentimento com o pack inteiro à vista. A instalação só acontece
     * depois do aceite — e um pack bloqueado não tem botão para aceitar.
     */
    private fun review() {
        val submission = selected() ?: run {
            status.text = PrumoBundle.message("pack.queue.empty")
            return
        }
        val preview = try {
            PackImporter.preview(submission.draft, PackOrigin.DRAFT)
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.queue.installError")
            return
        }
        val dialog = PackConsentDialog(project, preview)
        if (!dialog.showAndGet()) {
            status.text = PrumoBundle.message("pack.queue.notInstalled")
            return
        }

        val service = PrumoWorkspaceService.getInstance()
        try {
            PackImporter.install(
                store = PackStore(service.storage),
                workspaceId = workspaceId,
                preview = preview,
                acceptance = dialog.acceptance(System.getProperty("user.name") ?: "developer"),
            )
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.queue.installError")
            return
        }
        service.audit.record(
            workspaceId = workspaceId,
            tool = "prumo_ide",
            operation = "pack.install",
            result = io.prumo.mcp.audit.AuditResult.SUCCESS,
            durationMillis = 0,
            packId = submission.packId,
            details = mapOf(
                "version" to submission.version,
                "checksum" to preview.checksum,
                "riskLevel" to submission.riskLevel,
                "capabilities" to preview.manifest.capabilities.joinToString(",") { it.name },
            ),
        )
        SubmissionQueue(service.storage).discard(workspaceId, submission.submissionId)
        status.text = PrumoBundle.message("pack.queue.installed")
        redraw()
    }

    private fun discard() {
        val submission = selected() ?: return
        SubmissionQueue(PrumoWorkspaceService.getInstance().storage).discard(workspaceId, submission.submissionId)
        status.text = PrumoBundle.message("pack.queue.discarded")
        redraw()
    }

    /**
     * Remonta o painel inteiro.
     *
     * Instalar muda duas seções que este painel não desenha — os packs instalados e a contagem de
     * propostas no título da fila. Redesenhar só a lista deixaria a tela afirmando que não há pack
     * instalado logo depois de instalar um.
     */
    private fun redraw() {
        ApplicationManager.getApplication().invokeLater { PrumoToolWindowFactory.refreshOpenProjects() }
    }

    private fun renderer() = ListCellRenderer<PackSubmission> { _, value, _, _, _ ->
        JBLabel("${value.title}  —  ${value.packId} ${value.version} · risk ${value.riskLevel}")
    }
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList {
    while (hasMoreElements()) {
        add(nextElement())
    }
}
