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
 * O inglês é obrigatório e é a base: um pack circula entre equipes e entre países, e um formato que
 * admite só uma língua obriga migração depois. Traduções são acréscimo, nunca substituição — falta
 * de tradução cai no inglês em vez de mostrar chave crua.
 *
 * No arquivo do pack aceita as duas formas, para que escrever um pack à mão continue simples:
 * `"title": "Payroll rules"` ou `"title": {"default": "Payroll rules", "pt-BR": "Regras da folha"}`.
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
 * `file` é sempre relativo à raiz do pack: caminho de máquina não viaja entre colegas, e é a
 * regra 5 da seção 8.3 do prompt mestre.
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

/**
 * O que um Prumo Pack declara sobre si.
 *
 * O manifesto é o que o usuário lê antes de instalar: o que o pack é, de onde veio e o que ele
 * precisa poder fazer. Capacidade não declarada não funciona — e é a mesma `Capability` que o
 * `PolicyEngine` avalia, para que não existam duas listas de permissões contando histórias
 * diferentes.
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
    val createdAt: String,
    val updatedAt: String,
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid pack id '$id'." }
        require(version.isNotBlank()) { "Pack '$id' must declare a version." }
        val duplicated = knowledge.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) { "Duplicated knowledge ids in pack '$id': $duplicated." }
    }

    fun knowledge(itemId: String): KnowledgeItem? = knowledge.firstOrNull { it.id == itemId }
}

/** Identificadores viram nome de diretório: travessia de caminho é barrada aqui. */
private val IDENTIFIER = Regex("^[A-Za-z0-9_-]{1,64}$")
