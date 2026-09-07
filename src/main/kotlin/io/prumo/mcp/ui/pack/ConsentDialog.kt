package io.prumo.mcp.ui.pack

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.prumo.mcp.i18n.PrumoBundle
import io.prumo.mcp.pack.domain.RiskLevel
import io.prumo.mcp.pack.exchange.PackAcceptance
import io.prumo.mcp.pack.exchange.PackImportPreview
import io.prumo.mcp.ui.PrumoSeverity
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.SwingConstants

/**
 * Termo de consentimento do import de pack.
 *
 * Mostra origem, versão, checksum, o que cada capacidade permite, cada achado do classificador com
 * o trecho exato, e o script na íntegra. Nada é ativado antes do aceite.
 *
 * Achado destrutivo exige marcação item a item, sem nada pré-marcado. Pack `BLOCKED` não tem botão
 * de aceitar.
 */
class PackConsentDialog(
    project: Project,
    private val preview: PackImportPreview,
) : DialogWrapper(project) {

    private val acknowledgements = preview.assessment.findings
        .filter { it.level == RiskLevel.DESTRUCTIVE }
        .map { finding -> finding to JBCheckBox(finding.explanation, false) }

    private val responsibility = JBCheckBox(PrumoBundle.message("pack.consent.responsibility"), false)

    init {
        title = PrumoBundle.message("pack.consent.title")
        setOKButtonText(PrumoBundle.message("pack.consent.install"))
        init()
        updateOkButton()
        acknowledgements.forEach { (_, box) -> box.addActionListener { updateOkButton() } }
        responsibility.addActionListener { updateOkButton() }
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label("${preview.manifest.title.default}  ·  ${preview.manifest.version}") }
        row { comment(preview.manifest.description.default) }
        preview.manifest.author?.let { author -> row(PrumoBundle.message("pack.consent.author")) { label(author) } }
        row(PrumoBundle.message("pack.consent.checksum")) { label(preview.checksum.removePrefix("sha256:").take(CHECKSUM_DIGITS)) }
        if (!preview.checksumMatches) {
            row {
                comment(
                    if (preview.envelope.checksum.isBlank()) {
                        PrumoBundle.message("pack.consent.unsigned")
                    } else {
                        PrumoBundle.message("pack.consent.changed")
                    },
                )
            }
        }

        group(PrumoBundle.message("pack.consent.capabilities")) {
            if (preview.manifest.capabilities.isEmpty()) {
                row { comment(PrumoBundle.message("pack.consent.capabilities.none")) }
            }
            preview.manifest.capabilities.forEach { capability ->
                row { label("• " + capabilityText(capability.name)) }
            }
        }

        if (preview.assessment.findings.isNotEmpty()) {
            group(PrumoBundle.message("pack.consent.findings")) {
                preview.assessment.findings.forEach { finding ->
                    row {
                        cell(
                            JBLabel(
                                "${finding.level} — ${finding.explanation}",
                                PrumoSeverity.iconFor(finding.level),
                                SwingConstants.LEADING,
                            ),
                        )
                    }
                    row { comment("<code>${finding.evidence}</code> — ${finding.location}") }
                }
                row {
                    comment(PrumoBundle.message("pack.consent.findings.limits"))
                }
            }
        }

        if (preview.scripts.isNotEmpty()) {
            group(PrumoBundle.message("pack.consent.scripts")) {
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
            group(PrumoBundle.message("pack.consent.refused")) {
                row {
                    comment(PrumoBundle.message("pack.consent.refused.explanation"))
                }
            }
        } else {
            group(PrumoBundle.message("pack.consent.decision")) {
                acknowledgements.forEach { (_, box) -> row { cell(box) } }
                row { cell(responsibility) }
            }
        }
    }.apply { border = JBUI.Borders.empty(8) }

    override fun doValidate() = null

    /** O botão de instalar só habilita sem bloqueio e com todos os achados destrutivos marcados. */
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
        "REPOSITORY_READ" -> PrumoBundle.message("pack.capability.repositoryRead")
        "DATASOURCE_QUERY" -> PrumoBundle.message("pack.capability.datasourceQuery")
        "DOCUMENTATION_READ" -> PrumoBundle.message("pack.capability.documentationRead")
        "PROCESS_EXECUTE" -> PrumoBundle.message("pack.capability.processExecute")
        else -> capability
    }

    private companion object {
        const val CHECKSUM_DIGITS = 16
        const val SCRIPT_ROWS = 6
        const val SCRIPT_COLUMNS = 70
    }
}
