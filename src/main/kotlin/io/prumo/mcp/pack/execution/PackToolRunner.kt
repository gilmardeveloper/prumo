package io.prumo.mcp.pack.execution

import io.prumo.mcp.audit.AuditLog
import io.prumo.mcp.audit.AuditResult
import io.prumo.mcp.pack.application.PackAccessException
import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.PackTool
import io.prumo.mcp.pack.domain.PackToolKind
import io.prumo.mcp.platform.EnvironmentProbe
import io.prumo.mcp.platform.OperatingSystemFamily
import io.prumo.mcp.platform.ProcessExecutor
import io.prumo.mcp.platform.ProcessOutcome
import io.prumo.mcp.platform.ProcessRequest
import io.prumo.mcp.platform.SystemEnvironmentProbe
import io.prumo.mcp.policy.Capability
import io.prumo.mcp.policy.PolicyAction
import io.prumo.mcp.policy.PolicyEngine
import io.prumo.mcp.policy.PolicyRequest
import io.prumo.mcp.policy.WorkspacePolicies
import io.prumo.mcp.storage.LocalStorageProvider
import java.nio.file.Files
import java.nio.file.Path

data class ScriptResult(
    val packId: String,
    val toolId: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMillis: Long,
)

/**
 * Executa a ferramenta de script de um pack.
 *
 * A política do workspace decide se execução de processo é permitida, a capacidade declarada pelo
 * pack é conferida, o comando do sistema corrente é escolhido, e então o processo roda confinado ao
 * diretório de trabalho do pack.
 *
 * A invocação é auditada com o pack de origem. A saída do script não entra na trilha.
 */
class PackToolRunner(
    private val storage: LocalStorageProvider,
    private val store: PackStore = PackStore(storage),
    private val executor: ProcessExecutor = ProcessExecutor(),
    private val environmentProbe: EnvironmentProbe = SystemEnvironmentProbe,
) {

    fun run(
        workspaceId: String,
        policies: WorkspacePolicies,
        packId: String,
        toolId: String,
        audit: AuditLog? = null,
    ): ScriptResult {
        val manifest = store.load(workspaceId, packId)
            ?: throw PackAccessException("Pack '$packId' is not installed in this workspace.")
        val tool = manifest.tool(toolId)
            ?: throw PackAccessException("Pack '$packId' has no tool '$toolId'.")
        if (tool.kind != PackToolKind.SCRIPT) {
            throw PackAccessException("Tool '$toolId' is not a script.")
        }

        assertAllowed(manifest, tool, policies)

        val command = commandFor(tool)
        val workingDirectory = workingDirectory(workspaceId, packId)
        val outcome = executor.run(
            ProcessRequest(
                command = command,
                workingDirectory = workingDirectory,
                timeoutSeconds = tool.timeoutSeconds,
                environment = mapOf("PRUMO_PACK_ID" to packId, "PRUMO_TOOL_ID" to toolId),
            ),
        )

        audit?.record(
            workspaceId = workspaceId,
            tool = "user.$packId.$toolId",
            operation = "pack.run_tool",
            result = if (outcome.succeeded) AuditResult.SUCCESS else AuditResult.ERROR,
            durationMillis = outcome.durationMillis,
            packId = packId,
            details = auditDetails(outcome),
        )

        return ScriptResult(
            packId = packId,
            toolId = toolId,
            exitCode = outcome.exitCode,
            stdout = outcome.stdout,
            stderr = outcome.stderr,
            timedOut = outcome.timedOut,
            truncated = outcome.truncated,
            durationMillis = outcome.durationMillis,
        )
    }

    /** Exige as duas permissões: a política do workspace e a capacidade declarada pelo pack. */
    private fun assertAllowed(manifest: PackManifest, tool: PackTool, policies: WorkspacePolicies) {
        PolicyEngine.require(
            PolicyRequest(
                action = PolicyAction.EXECUTE_PROCESS,
                policies = policies,
                grantedCapabilities = manifest.capabilities,
                fromUserResource = true,
            ),
        )
        if (Capability.PROCESS_EXECUTE !in manifest.capabilities) {
            throw PackAccessException(
                "Pack '${manifest.id}' did not declare PROCESS_EXECUTE, so tool '${tool.id}' cannot run.",
            )
        }
    }

    private fun commandFor(tool: PackTool): List<String> {
        val key = when (environmentProbe.operatingSystem) {
            OperatingSystemFamily.WINDOWS -> "windows"
            OperatingSystemFamily.MACOS -> "macos"
            else -> "linux"
        }
        return tool.commands[key]
            ?: tool.commands["default"]
            ?: throw PackAccessException(
                "Tool '${tool.id}' has no command declared for this operating system ($key).",
            )
    }

    /** Diretório de trabalho do pack, em `workspaces/<id>/packs/<pack>/work`. */
    private fun workingDirectory(workspaceId: String, packId: String): Path {
        val directory = storage.workspaceRoot(workspaceId)
            .resolve("packs")
            .resolve(packId)
            .resolve(WORK_DIRECTORY)
        Files.createDirectories(directory)
        return directory
    }

    /** Só o desfecho: a saída do script pode conter qualquer coisa que ele tenha lido. */
    private fun auditDetails(outcome: ProcessOutcome): Map<String, String> = mapOf(
        "exitCode" to (outcome.exitCode?.toString() ?: "killed"),
        "timedOut" to outcome.timedOut.toString(),
        "truncated" to outcome.truncated.toString(),
    )

    private companion object {
        const val WORK_DIRECTORY = "work"
    }
}
