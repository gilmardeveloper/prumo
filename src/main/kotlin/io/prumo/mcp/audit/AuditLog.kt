package io.prumo.mcp.audit

import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant

enum class AuditResult { SUCCESS, DENIED, ERROR }

/**
 * Um evento da trilha de auditoria.
 *
 * Registra o que aconteceu, não o que foi lido: nem conteúdo de arquivo, nem linhas de resultado,
 * nem valor de parâmetro. Auditoria que copia o dado transforma o log em uma segunda cópia daquilo
 * que se pretendia proteger.
 */
@Serializable
data class AuditEntry(
    val timestamp: String,
    val workspaceId: String,
    val tool: String,
    val operation: String,
    val result: AuditResult,
    val durationMillis: Long,
    val repositoryId: String? = null,
    val datasourceId: String? = null,
    val packId: String? = null,
    val details: Map<String, String> = emptyMap(),
)

/**
 * Remove do registro qualquer coisa que possa ser segredo.
 *
 * A defesa é por lista de bloqueio de chaves conhecidas somada a um teto de tamanho: um valor longo
 * demais para ser um rótulo provavelmente é conteúdo, e conteúdo não entra na auditoria.
 */
object AuditSanitizer {

    private const val REDACTED = "[redacted]"
    private const val MAX_VALUE_LENGTH = 120

    private val SENSITIVE_KEYS = listOf(
        "password", "senha", "secret", "token", "credential", "credencial",
        "apikey", "api_key", "authorization", "auth", "jdbc", "connectionstring",
        "connection_string", "privatekey", "private_key", "passphrase", "cookie",
    )

    private val SENSITIVE_VALUES = listOf(
        Regex("(?i)jdbc:[^\\s]+"),
        Regex("(?i)(password|senha|pwd)\\s*=\\s*[^\\s;]+"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*"),
    )

    fun sanitize(entry: AuditEntry): AuditEntry = entry.copy(details = sanitize(entry.details))

    fun sanitize(details: Map<String, String>): Map<String, String> =
        details.mapValues { (key, value) ->
            when {
                isSensitiveKey(key) -> REDACTED
                SENSITIVE_VALUES.any { it.containsMatchIn(value) } -> REDACTED
                value.length > MAX_VALUE_LENGTH -> value.take(MAX_VALUE_LENGTH) + "…"
                else -> value
            }
        }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("-", "").replace("_", "")
        return SENSITIVE_KEYS.any { normalized.contains(it.replace("_", "")) }
    }
}

/**
 * Trilha de auditoria local, um arquivo por workspace, em linhas JSON.
 *
 * Fica sob o diretório do workspace: a auditoria de um workspace é tão isolada quanto o resto do
 * conteúdo dele.
 */
class AuditLog(
    private val storage: LocalStorageProvider,
    private val clock: () -> Instant = Instant::now,
) {

    private val json = Json { encodeDefaults = false }

    fun record(
        workspaceId: String,
        tool: String,
        operation: String,
        result: AuditResult,
        durationMillis: Long,
        repositoryId: String? = null,
        datasourceId: String? = null,
        packId: String? = null,
        details: Map<String, String> = emptyMap(),
    ) {
        val entry = AuditSanitizer.sanitize(
            AuditEntry(
                timestamp = clock().toString(),
                workspaceId = workspaceId,
                tool = tool,
                operation = operation,
                result = result,
                durationMillis = durationMillis,
                repositoryId = repositoryId,
                datasourceId = datasourceId,
                packId = packId,
                details = details,
            ),
        )
        append(workspaceId, entry)
    }

    fun read(workspaceId: String): List<AuditEntry> {
        val file = fileFor(workspaceId)
        if (!Files.exists(file)) {
            return emptyList()
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { json.decodeFromString(AuditEntry.serializer(), it) }
    }

    private fun append(workspaceId: String, entry: AuditEntry) {
        val file = fileFor(workspaceId)
        Files.createDirectories(file.parent)
        Files.writeString(
            file,
            json.encodeToString(AuditEntry.serializer(), entry) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private fun fileFor(workspaceId: String) =
        storage.workspaceRoot(workspaceId).resolve("audit").resolve("audit.jsonl")
}
