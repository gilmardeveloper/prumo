package io.prumo.mcp.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import io.prumo.mcp.ide.PrumoWorkspaceService
import io.prumo.mcp.pack.authoring.AuthoringSpec
import io.prumo.mcp.pack.authoring.PackAuthoringSpec
import io.prumo.mcp.pack.authoring.PackValidator
import io.prumo.mcp.pack.authoring.SubmissionQueue
import io.prumo.mcp.pack.authoring.ValidationReport
import io.prumo.mcp.pack.exchange.PackImporter
import io.prumo.mcp.pack.exchange.PackOrigin
import io.prumo.mcp.policy.PolicyAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class PackSubmissionResponse(
    val submissionId: String,
    val packId: String,
    val riskLevel: String,
    val installed: Boolean,
    val message: String,
)

/**
 * O que uma LLM precisa para escrever, conferir e propor um pack.
 *
 * `submit` não instala: deixa o pack na fila de aprovação da Tool Window, onde um humano decide.
 */
class PackAuthoringToolset : McpToolset {

    @McpTool(name = GET_SPEC_TOOL)
    @McpDescription(
        "Returns everything needed to write a Prumo Pack that Prumo will accept: the schema, the " +
            "capability catalogue, the security rules, the limits, where it gets installed, valid " +
            "examples and the most common reasons a pack is refused. Read this before writing a pack.",
    )
    suspend fun getAuthoringSpec(): AuthoringSpec =
        prumoToolCall(GET_SPEC_TOOL, "pack.get_authoring_spec", PolicyAction.READ_DOCUMENTATION) {
            PackAuthoringSpec.spec()
        }

    @McpTool(name = VALIDATE_TOOL)
    @McpDescription(
        "Checks a pack draft and returns what is wrong, where, and how to fix it. Installs nothing " +
            "and changes nothing: call it as many times as needed until the draft is valid. The check " +
            "covers structure, declared capabilities and risk — it never runs the SQL or the command " +
            "a tool carries, so a valid draft can still hold a query that fails or scans a whole " +
            "table. Try each statement with the database tools before submitting.",
    )
    suspend fun validatePack(
        @McpDescription("The pack draft, as the JSON exchange file.")
        draft: String,
    ): ValidationReport =
        prumoToolCall(VALIDATE_TOOL, "pack.validate", PolicyAction.READ_DOCUMENTATION) { call ->
            val report = PackValidator.validate(draft)
            call.auditDetails["valid"] = report.valid.toString()
            call.auditDetails["riskLevel"] = report.riskLevel
            report
        }

    @McpTool(name = SUBMIT_TOOL)
    @McpDescription(
        "Submits a pack draft for the developer to review. This does NOT install or activate " +
            "anything: the pack goes to the approval queue in the Prumo tool window, and only the " +
            "developer can accept it there. Validate the draft first.",
    )
    suspend fun submitPack(
        @McpDescription("The pack draft, as the JSON exchange file.")
        draft: String,
    ): PackSubmissionResponse =
        prumoToolCall(SUBMIT_TOOL, "pack.submit", PolicyAction.READ_DOCUMENTATION) { call ->
            val report = PackValidator.validate(draft)
            if (!report.valid) {
                val first = report.errors.firstOrNull()
                return@prumoToolCall PackSubmissionResponse(
                    submissionId = "",
                    packId = "",
                    riskLevel = report.riskLevel,
                    installed = false,
                    message = "The draft was not queued because it is not valid: " +
                        "${first?.problem.orEmpty()} ${first?.fix.orEmpty()}".trim(),
                )
            }

            val preview = PackImporter.preview(draft, PackOrigin.DRAFT)
            val queue = SubmissionQueue(PrumoWorkspaceService.getInstance().storage)
            val submission = withContext(Dispatchers.IO) {
                queue.submit(
                    workspaceId = call.context.workspace.id,
                    packId = preview.manifest.id,
                    version = preview.manifest.version,
                    title = preview.manifest.title.default,
                    riskLevel = report.riskLevel,
                    draft = draft,
                )
            }
            call.auditDetails["packId"] = submission.packId
            call.auditDetails["riskLevel"] = report.riskLevel

            PackSubmissionResponse(
                submissionId = submission.submissionId,
                packId = submission.packId,
                riskLevel = report.riskLevel,
                installed = false,
                message = "The pack is waiting for the developer in the Prumo tool window. " +
                    "Nothing was installed or activated.",
            )
        }

    private companion object {
        const val GET_SPEC_TOOL = "prumo_pack_get_authoring_spec"
        const val VALIDATE_TOOL = "prumo_pack_validate"
        const val SUBMIT_TOOL = "prumo_pack_submit"
    }
}
