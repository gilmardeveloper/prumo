package io.prumo.mcp.ui.pack

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.pack.application.PackFileExchange
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.ui.PrumoToolWindowFactory
import io.prumo.mcp.ui.WorkspaceViewModel
import java.nio.file.Path
import javax.swing.Icon

/**
 * Entrada e saída de packs pelo disco: importar um arquivo recebido, exportar um pack instalado.
 *
 * A importação passa pelo mesmo termo de consentimento da fila de submissões — um pack vindo de
 * arquivo não é mais confiável que um proposto por um cliente MCP.
 */
class PackExchangePanel(
    private val project: Project,
    private val workspaceId: String,
    private val installed: List<WorkspaceViewModel.PackRow>,
) {

    private val status = JBLabel(" ")

    fun render(panel: Panel) = with(panel) {
        if (installed.isEmpty()) {
            row { comment(PrumoBundle.message("toolwindow.packs.none")) }
        }
        installed.forEach { pack ->
            row {
                cell(JBLabel("${pack.title}  ·  ${pack.version}"))
                comment(pack.packId)
            }
        }
        row {
            button(PrumoBundle.message("pack.exchange.import")) { importFromFile() }
            button(PrumoBundle.message("pack.exchange.export")) { exportToFile() }
            button(PrumoBundle.message("pack.exchange.remove")) { removeInstalled() }
            cell(status)
        }
        row { comment(PrumoBundle.message("pack.exchange.hint")) }
    }

    /**
     * Importa o arquivo escolhido.
     *
     * Toda falha da leitura vira diálogo: uma exceção que sobe daqui é engolida pela IDE, e o
     * usuário ficaria com um botão que não faz nada.
     */
    private fun importFromFile() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle(PrumoBundle.message("pack.exchange.importTitle"))
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return

        val service = PrumoWorkspaceService.getInstance()
        val store = PackStore(service.storage)
        val preview = try {
            PackFileExchange(store).read(Path.of(file.path))
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.exchange.importError")
            return
        }

        val dialog = PackConsentDialog(project, preview)
        if (!dialog.showAndGet()) {
            status.text = PrumoBundle.message("pack.queue.notInstalled")
            return
        }
        try {
            io.prumo.mcp.pack.exchange.PackImporter.install(
                store = store,
                workspaceId = workspaceId,
                preview = preview,
                acceptance = dialog.acceptance(System.getProperty("user.name") ?: "developer"),
            )
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.exchange.importError")
            return
        }
        service.audit.record(
            workspaceId = workspaceId,
            tool = "prumo_ide",
            operation = "pack.import",
            result = AuditResult.SUCCESS,
            durationMillis = 0,
            packId = preview.manifest.id,
            details = mapOf(
                "version" to preview.manifest.version,
                "checksum" to preview.checksum,
                "capabilities" to preview.manifest.capabilities.joinToString(",") { it.name },
            ),
        )
        status.text = PrumoBundle.message("pack.exchange.imported", preview.manifest.id)
        ApplicationManager.getApplication().invokeLater { PrumoToolWindowFactory.refreshOpenProjects() }
    }

    /** Exporta o pack escolhido. Com um só instalado, não há o que escolher. */
    private fun exportToFile() {
        val pack = chosenPack(
            emptyKey = "pack.exchange.noneToExport",
            titleKey = "pack.exchange.chooseTitle",
            messageKey = "pack.exchange.chooseMessage",
        ) ?: return
        val descriptor = FileSaverDescriptor(
            PrumoBundle.message("pack.exchange.exportTitle"),
            PrumoBundle.message("pack.exchange.exportDescription"),
            "json",
        )
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as Path?, "${pack.packId}.json") ?: return

        val service = PrumoWorkspaceService.getInstance()
        val written = try {
            PackFileExchange(PackStore(service.storage)).write(workspaceId, pack.packId, wrapper.file.toPath())
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.exchange.exportError")
            return
        }
        service.audit.record(
            workspaceId = workspaceId,
            tool = "prumo_ide",
            operation = "pack.export",
            result = AuditResult.SUCCESS,
            durationMillis = 0,
            packId = pack.packId,
            details = mapOf("version" to pack.version),
        )
        status.text = PrumoBundle.message("pack.exchange.exported", written.fileName.toString())
    }

    /**
     * Remove um pack instalado.
     *
     * A remoção apaga o pack do workspace — as ferramentas dele deixam de existir para os clientes
     * de IA e os documentos de conhecimento saem do disco. Repositório e banco não são tocados.
     */
    private fun removeInstalled() {
        val pack = chosenPack(
            emptyKey = "pack.exchange.noneToRemove",
            titleKey = "pack.exchange.chooseRemoveTitle",
            messageKey = "pack.exchange.chooseRemoveMessage",
        ) ?: return
        val confirmed = Messages.showYesNoDialog(
            project,
            PrumoBundle.message("pack.exchange.confirmRemove", pack.title),
            PrumoBundle.message("pack.exchange.chooseRemoveTitle"),
            null as Icon?,
        )
        if (confirmed != Messages.YES) {
            return
        }

        val service = PrumoWorkspaceService.getInstance()
        try {
            PackStore(service.storage).remove(workspaceId, pack.packId)
        } catch (failure: Exception) {
            showPackFailure(project, failure, "pack.exchange.removeError")
            return
        }
        service.audit.record(
            workspaceId = workspaceId,
            tool = "prumo_ide",
            operation = "pack.remove",
            result = AuditResult.SUCCESS,
            durationMillis = 0,
            packId = pack.packId,
            details = mapOf("version" to pack.version),
        )
        status.text = PrumoBundle.message("pack.exchange.removed", pack.packId)
        ApplicationManager.getApplication().invokeLater { PrumoToolWindowFactory.refreshOpenProjects() }
    }

    private fun chosenPack(emptyKey: String, titleKey: String, messageKey: String): WorkspaceViewModel.PackRow? {
        if (installed.isEmpty()) {
            status.text = PrumoBundle.message(emptyKey)
            return null
        }
        if (installed.size == 1) {
            return installed.first()
        }
        val labels = installed.map { "${it.title} · ${it.version} (${it.packId})" }
        val chosen = Messages.showDialog(
            project,
            PrumoBundle.message(messageKey),
            PrumoBundle.message(titleKey),
            labels.toTypedArray(),
            0,
            null as Icon?,
        )
        return installed.getOrNull(chosen)
    }
}
