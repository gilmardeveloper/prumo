package io.prumo.mcp.settings

import com.intellij.openapi.diagnostic.Logger
import io.prumo.mcp.storage.FileSystemStorageProvider
import io.prumo.mcp.storage.JsonStore
import io.prumo.mcp.storage.LocalStorageProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.io.IOException
import java.nio.file.Path
import java.util.Locale

/**
 * Idioma em que o Prumo fala com o humano.
 *
 * Existe porque o idioma da IDE e o idioma preferido nem sempre coincidem: quem trabalha com a
 * IDE em inglês por hábito ainda pode querer ler as telas do Prumo em português.
 */
@Serializable
enum class PrumoLanguage(val labelKey: String) {

    /** Acompanha a IDE. É o padrão: um plugin não decide o idioma do usuário por conta própria. */
    SYSTEM("settings.language.system"),
    ENGLISH("settings.language.english"),
    BRAZILIAN_PORTUGUESE("settings.language.brazilianPortuguese"),
    ;

    /** `null` significa "o que a IDE estiver usando" — não há locale a impor. */
    fun locale(): Locale? = when (this) {
        SYSTEM -> null
        ENGLISH -> Locale.ENGLISH
        BRAZILIAN_PORTUGUESE -> BRAZIL
    }

    private companion object {
        val BRAZIL: Locale = Locale.forLanguageTag("pt-BR")
    }
}

/**
 * Preferência de idioma do Prumo, guardada junto das demais configurações do produto.
 *
 * Fica na área do sistema operacional, como todo o resto: preferência de interface não entra em
 * repositório do usuário. O valor é lido a cada texto de tela, então é mantido em memória e só
 * volta ao disco quando alguém o altera.
 */
class PrumoLanguageSetting(
    private val storage: LocalStorageProvider,
    private val json: JsonStore = JsonStore(),
) {

    @Volatile
    private var cached: PrumoLanguage? = null

    fun current(): PrumoLanguage = cached ?: read().also { cached = it }

    fun update(language: PrumoLanguage) {
        json.write(file(), serializer<LanguageDocument>(), LanguageDocument(language))
        cached = language
    }

    /**
     * Configuração ilegível não pode derrubar a interface: o produto continua no idioma da IDE e
     * registra o motivo, em vez de deixar o usuário sem tela por causa de um arquivo corrompido.
     */
    private fun read(): PrumoLanguage =
        try {
            json.read(file(), serializer<LanguageDocument>())?.language ?: PrumoLanguage.SYSTEM
        } catch (cause: IOException) {
            LOG.warn("Prumo MCP could not read the language preference at ${file()}; falling back to the IDE language.", cause)
            PrumoLanguage.SYSTEM
        }

    private fun file(): Path = storage.settingsRoot().resolve(FILE)

    companion object {
        private const val FILE = "language.json"
        private val LOG = Logger.getInstance(PrumoLanguageSetting::class.java)

        /** A instância que a interface usa. O construtor fica aberto para o teste apontar outro disco. */
        val shared: PrumoLanguageSetting by lazy {
            PrumoLanguageSetting(FileSystemStorageProvider.forCurrentSystem())
        }
    }
}

@Serializable
private data class LanguageDocument(
    val language: PrumoLanguage = PrumoLanguage.SYSTEM,
)
