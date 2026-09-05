package io.prumo.mcp.pack.exchange

import io.prumo.mcp.pack.application.PackStore
import io.prumo.mcp.pack.domain.PackManifest
import io.prumo.mcp.pack.domain.RiskAssessment
import io.prumo.mcp.pack.domain.RiskClassifier
import io.prumo.mcp.pack.domain.RiskLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.security.MessageDigest
import java.util.Locale

/** Falha esperada ao exportar ou importar um pack. */
class PackExchangeException(message: String) : IllegalArgumentException(message)

/**
 * Formato de troca: arquivo único, legível e versionado por schema.
 *
 * O conteúdo de conhecimento viaja junto, em texto.
 */
@Serializable
data class PackEnvelope(
    val prumoPackVersion: Int = CURRENT_VERSION,
    val manifest: PackManifest,
    val knowledge: Map<String, String> = emptyMap(),
    val checksum: String = "",
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * O que o usuário vê antes de decidir: a leitura do arquivo mais a análise de risco.
 *
 * Não instala nada.
 */
data class PackImportPreview(
    val envelope: PackEnvelope,
    val assessment: RiskAssessment,
    val checksum: String,
    val checksumMatches: Boolean,
    val scripts: Map<String, String>,
) {
    val manifest: PackManifest get() = envelope.manifest

    val requiresReinforcedConsent: Boolean get() = assessment.destructive

    val blocked: Boolean get() = assessment.blocked
}

/** Registro do aceite, para a auditoria: quem, quando, qual versão, qual checksum, o que concedeu. */
data class PackAcceptance(
    val acceptedBy: String,
    val acceptedAt: String,
    val packId: String,
    val version: String,
    val checksum: String,
    val capabilities: Set<String>,
)

private val JSON = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Escreve o pack em um arquivo de troca.
 *
 * Recusa exportar manifesto que carregue campo com nome de segredo.
 */
object PackExporter {

    private val SECRET_KEYS = listOf("password", "senha", "secret", "token", "apikey", "api_key", "credential")

    fun export(store: PackStore, workspaceId: String, packId: String): String {
        val manifest = store.load(workspaceId, packId)
            ?: throw PackExchangeException("Pack '$packId' is not installed in this workspace.")

        assertPortable(manifest)

        val knowledge = manifest.knowledge.associate { item ->
            item.id to store.readKnowledge(workspaceId, packId, item.id)
        }
        val envelope = PackEnvelope(manifest = manifest, knowledge = knowledge)
        return JSON.encodeToString(
            serializer<PackEnvelope>(),
            envelope.copy(checksum = checksumOf(envelope)),
        )
    }

    private fun assertPortable(manifest: PackManifest) {
        val serialized = JSON.encodeToString(serializer<PackManifest>(), manifest).lowercase(Locale.ROOT)
        SECRET_KEYS.firstOrNull { serialized.contains("\"$it\"") }?.let {
            throw PackExchangeException(
                "Pack '${manifest.id}' carries a field named '$it'. Secrets never travel inside a pack.",
            )
        }
    }
}

/**
 * Lê um arquivo de troca e prepara a decisão do usuário.
 *
 * A leitura nunca instala, e `BLOCKED` não tem caminho de aceitação.
 */
object PackImporter {

    fun preview(content: String): PackImportPreview {
        val envelope = try {
            JSON.decodeFromString(serializer<PackEnvelope>(), content)
        } catch (failure: Exception) {
            throw PackExchangeException("This file is not a Prumo Pack: ${failure.message?.take(200)}")
        }
        if (envelope.prumoPackVersion != PackEnvelope.CURRENT_VERSION) {
            throw PackExchangeException(
                "This pack uses exchange format ${envelope.prumoPackVersion}; " +
                    "this version of Prumo reads ${PackEnvelope.CURRENT_VERSION}.",
            )
        }

        val expected = checksumOf(envelope.copy(checksum = ""))
        val scripts = envelope.manifest.tools
            .flatMap { tool -> tool.commands.map { (platform, command) -> "${tool.id} ($platform)" to command.joinToString(" ") } }
            .toMap()

        return PackImportPreview(
            envelope = envelope,
            assessment = RiskClassifier.assess(envelope.manifest),
            checksum = expected,
            checksumMatches = envelope.checksum.isBlank() || envelope.checksum == expected,
            scripts = scripts,
        )
    }

    /**
     * Instala o pack revisado.
     *
     * Exige o aceite, recusa `BLOCKED` e recusa quando o checksum não confere.
     */
    fun install(
        store: PackStore,
        workspaceId: String,
        preview: PackImportPreview,
        acceptance: PackAcceptance?,
    ) {
        if (preview.blocked) {
            val reasons = preview.assessment.findings
                .filter { it.level == RiskLevel.BLOCKED }
                .joinToString("; ") { it.explanation }
            throw PackExchangeException("Prumo will not install this pack: $reasons")
        }
        if (!preview.checksumMatches) {
            throw PackExchangeException(
                "The pack file changed after it was packaged. Ask the author for a fresh export.",
            )
        }
        val accepted = acceptance
            ?: throw PackExchangeException("A pack is only installed after the consent screen is accepted.")
        if (accepted.packId != preview.manifest.id || accepted.checksum != preview.checksum) {
            throw PackExchangeException("The acceptance does not match the pack being installed.")
        }

        store.save(workspaceId, preview.manifest)
        preview.manifest.knowledge.forEach { item ->
            store.writeKnowledge(
                workspaceId,
                preview.manifest.id,
                item,
                preview.envelope.knowledge[item.id].orEmpty(),
            )
        }
    }
}

/** Checksum sobre o conteúdo canônico, sem o próprio campo de checksum. */
internal fun checksumOf(envelope: PackEnvelope): String {
    val canonical = JSON.encodeToString(serializer<PackEnvelope>(), envelope.copy(checksum = ""))
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }
}
