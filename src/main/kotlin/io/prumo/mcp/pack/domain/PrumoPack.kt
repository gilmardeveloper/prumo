package io.prumo.mcp.pack.domain

import io.prumo.mcp.policy.Capability
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Texto que o pack mostra a quem o instala, opcionalmente em mais de um idioma.
 *
 * O inglês é obrigatório e serve de base: falta de tradução cai nele em vez de mostrar a chave.
 *
 * No arquivo do pack aceita as duas formas: `"title": "Payroll rules"` ou
 * `"title": {"default": "Payroll rules", "pt-BR": "Regras da folha"}`.
 */
@Serializable(with = LocalizedTextSerializer::class)
data class LocalizedText(
    val default: String,
    val translations: Map<String, String> = emptyMap(),
) {
    init {
        require(default.isNotBlank()) { "The default (English) text is required." }
    }

    /** Idioma exato, depois só a língua (`pt-BR` → `pt`), depois o inglês. */
    fun forLanguage(tag: String?): String {
        if (tag.isNullOrBlank()) {
            return default
        }
        translations[tag]?.let { return it }
        val language = tag.substringBefore('-').lowercase(Locale.ROOT)
        return translations.entries
            .firstOrNull { it.key.substringBefore('-').lowercase(Locale.ROOT) == language }
            ?.value
            ?: default
    }

    companion object {
        const val DEFAULT_KEY = "default"

        fun of(text: String) = LocalizedText(text)
    }
}

internal object LocalizedTextSerializer : KSerializer<LocalizedText> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("io.prumo.mcp.LocalizedText")

    override fun deserialize(decoder: Decoder): LocalizedText {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("LocalizedText can only be read from JSON.")
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> LocalizedText(element.content)
            is JsonObject -> {
                val translations = element.jsonObject
                    .filterKeys { it != LocalizedText.DEFAULT_KEY }
                    .mapValues { it.value.jsonPrimitive.content }
                val default = element.jsonObject[LocalizedText.DEFAULT_KEY]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Localized text needs a 'default' entry in English.")
                LocalizedText(default, translations)
            }

            else -> throw IllegalArgumentException("Localized text must be a string or an object.")
        }
    }

    override fun serialize(encoder: Encoder, value: LocalizedText) {
        val output = encoder as? JsonEncoder
            ?: throw IllegalStateException("LocalizedText can only be written as JSON.")
        if (value.translations.isEmpty()) {
            output.encodeJsonElement(JsonPrimitive(value.default))
            return
        }
        output.encodeJsonElement(
            buildJsonObject {
                put(LocalizedText.DEFAULT_KEY, value.default)
                value.translations.forEach { (tag, text) -> put(tag, text) }
            },
        )
    }
}

/**
 * Um documento, nota ou dado de apoio que o pack carrega.
 *
 * `file` é sempre relativo à raiz do pack.
 */
@Serializable
data class KnowledgeItem(
    val id: String,
    val title: LocalizedText,
    val file: String,
    val tags: List<String> = emptyList(),
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid knowledge id '$id'." }
        require(file.isNotBlank()) { "Knowledge item '$id' must point to a file." }
    }
}

/** Como a ferramenta do pack é executada. */
@Serializable
enum class PackToolKind {
    /** Consulta salva, executada pelo motor de consulta do produto. */
    QUERY,

    /** Script do usuário, executado confinado e só com a capacidade `PROCESS_EXECUTE` concedida. */
    SCRIPT,
}

/**
 * Uma ferramenta que o pack acrescenta ao workspace.
 *
 * Aparece ao cliente MCP sob o namespace `user.`, marcada como recurso de terceiro. O datasource é
 * referenciado por identificador lógico e resolvido no workspace de destino.
 */
@Serializable
data class PackTool(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val kind: PackToolKind,
    val capabilities: Set<Capability> = emptySet(),
    val datasourceRef: String? = null,
    val sql: String? = null,
    /** Comando por sistema operacional (`windows`, `linux`, `macos`), já quebrado em argumentos. */
    val commands: Map<String, List<String>> = emptyMap(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid tool id '$id'." }
        require(timeoutSeconds in 1..MAX_TIMEOUT_SECONDS) { "Tool '$id' has an invalid timeout." }
        when (kind) {
            PackToolKind.QUERY -> require(!sql.isNullOrBlank()) { "Query tool '$id' must carry its SQL." }
            PackToolKind.SCRIPT -> require(commands.isNotEmpty()) { "Script tool '$id' must declare a command." }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 300
    }
}

/**
 * O que um Prumo Pack declara sobre si.
 *
 * É o que o usuário lê antes de instalar. Capacidade não declarada não funciona, e é a mesma
 * `Capability` que o `PolicyEngine` avalia.
 */
@Serializable
data class PackManifest(
    val id: String,
    val version: String,
    val title: LocalizedText,
    val description: LocalizedText,
    val author: String? = null,
    val capabilities: Set<Capability> = emptySet(),
    val knowledge: List<KnowledgeItem> = emptyList(),
    val tools: List<PackTool> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid pack id '$id'." }
        require(version.isNotBlank()) { "Pack '$id' must declare a version." }
        val duplicated = knowledge.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicated knowledge ids in pack '$id': $duplicated." }
        val duplicatedTools = tools.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicatedTools.isEmpty()) { "Duplicated tool ids in pack '$id': $duplicatedTools." }
    }

    fun knowledge(itemId: String): KnowledgeItem? = knowledge.firstOrNull { it.id == itemId }

    fun tool(toolId: String): PackTool? = tools.firstOrNull { it.id == toolId }
}

/** Identificadores viram nome de diretório: travessia de caminho é barrada aqui. */
private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
