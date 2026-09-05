package io.prumo.mcp.pack.authoring

import io.prumo.mcp.pack.domain.RiskAssessment
import io.prumo.mcp.pack.domain.RiskLevel
import io.prumo.mcp.pack.exchange.PackExchangeException
import io.prumo.mcp.pack.exchange.PackImportPreview
import io.prumo.mcp.pack.exchange.PackImporter
import kotlinx.serialization.Serializable

@Serializable
data class ValidationIssue(
    val where: String,
    val problem: String,
    val fix: String,
)

@Serializable
data class ValidationReport(
    val valid: Boolean,
    val riskLevel: String,
    val errors: List<ValidationIssue>,
    val warnings: List<ValidationIssue>,
)

/**
 * Confere um rascunho de pack sem instalar nada.
 *
 * Cada problema apontado diz onde está e qual é a forma correta.
 */
object PackValidator {

    fun validate(draft: String): ValidationReport {
        val preview = try {
            PackImporter.preview(draft)
        } catch (failure: PackExchangeException) {
            return ValidationReport(
                valid = false,
                riskLevel = RiskLevel.BLOCKED.name,
                errors = listOf(
                    ValidationIssue(
                        where = "file",
                        problem = failure.message ?: "The draft could not be read.",
                        fix = "Send a single JSON object with 'prumoPackVersion' and 'manifest'. " +
                            "Call the authoring spec tool to see a complete example.",
                    ),
                ),
                warnings = emptyList(),
            )
        } catch (failure: Exception) {
            return ValidationReport(
                valid = false,
                riskLevel = RiskLevel.BLOCKED.name,
                errors = listOf(
                    ValidationIssue(
                        where = "manifest",
                        problem = failure.message?.take(300) ?: "The manifest does not follow the schema.",
                        fix = "Check the required fields and the identifier format in the authoring spec.",
                    ),
                ),
                warnings = emptyList(),
            )
        }

        return report(preview)
    }

    private fun report(preview: PackImportPreview): ValidationReport {
        val assessment: RiskAssessment = preview.assessment
        val errors = assessment.findings
            .filter { it.level == RiskLevel.BLOCKED }
            .map { finding ->
                ValidationIssue(
                    where = finding.location,
                    problem = finding.explanation + " Found in: " + finding.evidence,
                    fix = fixFor(finding.rule),
                )
            }

        val warnings = assessment.findings
            .filter { it.level == RiskLevel.DESTRUCTIVE || it.level == RiskLevel.SENSITIVE }
            .map { finding ->
                ValidationIssue(
                    where = finding.location,
                    problem = "[${finding.level}] ${finding.explanation} Found in: ${finding.evidence}",
                    fix = "The pack can still be installed, but the developer will have to accept this " +
                        "explicitly. Remove it if the tool does not really need it.",
                )
            }

        return ValidationReport(
            valid = errors.isEmpty(),
            riskLevel = assessment.level.name,
            errors = errors,
            warnings = warnings,
        )
    }

    private fun fixFor(rule: String): String = when (rule) {
        "undeclared-capability" -> "Add the capability to the 'capabilities' list of the manifest."
        "sql-not-read-only" -> "Rewrite the query as SELECT, WITH … SELECT or EXPLAIN without ANALYZE."
        "download-and-execute" -> "Carry the script inside the pack and run it from there, so it can be reviewed."
        "obfuscated-command" -> "Write the command in plain text. Encoded or dynamically evaluated commands are refused."
        "credential-access" -> "Reference data sources by their logical id. Packs never read credentials from disk."
        "other-workspace" -> "Use only the workspace where the pack is installed."
        else -> "See the authoring spec for the accepted form."
    }
}
