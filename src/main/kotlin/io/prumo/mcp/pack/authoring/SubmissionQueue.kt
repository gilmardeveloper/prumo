package io.prumo.mcp.pack.authoring

import io.prumo.mcp.storage.JsonStore
import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

@Serializable
data class PackSubmission(
    val submissionId: String,
    val packId: String,
    val version: String,
    val title: String,
    val submittedAt: String,
    val submittedBy: String,
    val riskLevel: String,
    val draft: String,
)

/**
 * Fila de packs propostos, aguardando decisão do desenvolvedor.
 *
 * Submeter **não** instala, não ativa e não executa nada: deixa o pack aqui, visível na Tool
 * Window, que é o único ponto em que um recurso é ativado (P11). A fila existe justamente para que
 * exista um lugar onde o pack fica parado esperando um humano.
 */
class SubmissionQueue(
    private val storage: LocalStorageProvider,
    private val json: JsonStore = JsonStore(),
    private val clock: () -> Instant = Instant::now,
) {

    fun submit(workspaceId: String, packId: String, version: String, title: String, riskLevel: String, draft: String): PackSubmission {
        val submission = PackSubmission(
            submissionId = "$packId-${clock().toEpochMilli()}",
            packId = packId,
            version = version,
            title = title,
            submittedAt = clock().toString(),
            submittedBy = MCP_CLIENT,
            riskLevel = riskLevel,
            draft = draft,
        )
        json.write(fileFor(workspaceId, submission.submissionId), serializer<PackSubmission>(), submission)
        return submission
    }

    fun pending(workspaceId: String): List<PackSubmission> {
        val root = queueRoot(workspaceId)
        if (!Files.isDirectory(root)) {
            return emptyList()
        }
        return Files.list(root).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }
                .map { json.read(it, serializer<PackSubmission>()) }
                .toList()
                .filterNotNull()
                .sortedBy { it.submittedAt }
        }
    }

    fun discard(workspaceId: String, submissionId: String) {
        Files.deleteIfExists(fileFor(workspaceId, submissionId))
    }

    private fun queueRoot(workspaceId: String): Path =
        storage.workspaceRoot(workspaceId).resolve(QUEUE_DIRECTORY)

    private fun fileFor(workspaceId: String, submissionId: String): Path {
        require(submissionId.matches(SAFE_ID)) { "Invalid submission id." }
        return queueRoot(workspaceId).resolve("$submissionId.json")
    }

    private companion object {
        const val QUEUE_DIRECTORY = "pack-submissions"
        const val MCP_CLIENT = "mcp-client"
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,96}$")
    }
}
