package io.prumo.mcp.ui.pack

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.pack.domain.RiskLevel
import io.prumo.mcp.pack.exchange.PackAcceptance
import io.prumo.mcp.pack.exchange.PackImportPreview
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JTextArea

/**
 * Termo de consentimento do import de pack.
 *
 * Consentimento sem informação não é consentimento: a tela mostra origem, versão, checksum, o que
 * cada capacidade permite em linguagem simples, cada achado do classificador **com o trecho exato**
 * e o script na íntegra. Nada é ativado antes do aceite.
 *
 * Achado destrutivo exige marcação item a item, e nada vem pré-marcado — a caixa já marcada é o
 * truque clássico para transformar consentimento em formalidade. Pack `BLOCKED` não tem botão de
 * aceitar (P9).
 */
class PackConsentDialog(
    project: Project,
    private val preview: PackImportPreview,
) : DialogWrapper(project) {

    private val acknowledgements = preview.assessment.findings
        .filter { it.level == RiskLevel.DESTRUCTIVE }
        .map { finding -> finding to JBCheckBox(finding.explanation, false) }

    private val responsibility = JBCheckBox(
        "I understand that this pack was written by someone else and that I am responsible for what it does.",
        false,
    )

    init {
        title = "Install Prumo Pack"
        setOKButtonText("Install")
        init()
        updateOkButton()
        acknowledgements.forEach { (_, box) -> box.addActionListener { updateOkButton() } }
        responsibility.addActionListener { updateOkButton() }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("${preview.manifest.title.default}  ·  ${preview.manifest.version}") }
        row { comment(preview.manifest.description.default) }
        preview.manifest.author?.let { author -> row("Author:") { label(author) } }
        row("Checksum:") { label(preview.checksum.removePrefix("sha256:").take(CHECKSUM_DIGITS)) }
        if (!preview.checksumMatches) {
            row {
                comment("<b>This file changed after it was packaged.</b> Ask the author for a fresh export.")
            }
        }

        group("What it will be able to do") {
            if (preview.manifest.capabilities.isEmpty()) {
                row { comment("Nothing beyond reading its own knowledge.") }
            }
            preview.manifest.capabilities.forEach { capability ->
                row { label("• " + capabilityText(capability.name)) }
            }
        }

        if (preview.assessment.findings.isNotEmpty()) {
            group("What Prumo found in it") {
                preview.assessment.findings.forEach { finding ->
                    row { label("[${finding.level}] ${finding.explanation}") }
                    row { comment("<code>${finding.evidence}</code> — ${finding.location}") }
                }
                row {
                    comment(
                        "This is static analysis: it detects known signals, it does not prove the pack is safe. " +
                            "A script written to hide what it does can pass.",
                    )
                }
            }
        }

        if (preview.scripts.isNotEmpty()) {
            group("Scripts, in full") {
                preview.scripts.forEach { (location, command) ->
                    row { label(location) }
                    row {
                        cell(JBScrollPane(JTextArea(command, SCRIPT_ROWS, SCRIPT_COLUMNS).apply { isEditable = false }))
                            .align(AlignX.FILL)
                    }
                }
            }
        }

        if (preview.blocked) {
            group("Refused") {
                row {
                    comment(
                        "Prumo will not install this pack. What it does cannot be reviewed or is out of bounds, " +
                            "so there is no way to accept it here.",
                    )
                }
            }
        } else {
            group("Your decision") {
                acknowledgements.forEach { (_, box) -> row { cell(box) } }
                row { cell(responsibility) }
            }
        }
    }.apply { border = JBUI.Borders.empty(8) }

    override fun doValidate() = null

    /**
     * O botão de instalar só existe quando há caminho legítimo até ele: pack bloqueado nunca o
     * habilita, e destrutivo exige cada item marcado.
     */
    private fun updateOkButton() {
        isOKActionEnabled = !preview.blocked &&
            responsibility.isSelected &&
            acknowledgements.all { (_, box) -> box.isSelected }
    }

    fun acceptance(user: String): PackAcceptance = PackAcceptance(
        acceptedBy = user,
        acceptedAt = Instant.now().toString(),
        packId = preview.manifest.id,
        version = preview.manifest.version,
        checksum = preview.checksum,
        capabilities = preview.manifest.capabilities.map { it.name }.toSortedSet(),
    )

    private fun capabilityText(capability: String): String = when (capability) {
        "REPOSITORY_READ" -> "Read files from the repositories bound to this workspace"
        "DATASOURCE_QUERY" -> "Run read-only queries on the databases of this workspace"
        "DOCUMENTATION_READ" -> "Read the documentation attached to this workspace"
        "PROCESS_EXECUTE" -> "Run a command on this machine, confined to the pack directory"
        else -> capability
    }

    private companion object {
        const val CHECKSUM_DIGITS = 16
        const val SCRIPT_ROWS = 6
        const val SCRIPT_COLUMNS = 70
    }
}
